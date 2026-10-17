// OpenSMS API example (Go 1.20+, standard library only).
//
//	export OPENSMS_KEY=osk_...            # API key from the app: Settings -> API keys
//	go run example.go                     # read-only: show quota and the last messages
//	go run example.go +919876543210       # also send "Hello from OpenSMS" and follow it to the end
//
// Optional: OPENSMS_BASE (default https://opensms.wansaw.com)
package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

var (
	base   = strings.TrimRight(envOr("OPENSMS_BASE", "https://opensms.wansaw.com"), "/")
	key    = os.Getenv("OPENSMS_KEY")
	client = &http.Client{Timeout: 30 * time.Second}
	final  = map[string]bool{"sent": true, "delivered": true, "failed": true, "undelivered": true, "unknown": true, "expired": true}
)

type apiError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type simQuota struct {
	Sim       string `json:"sim"`
	Enabled   bool   `json:"enabled"`
	Present   bool   `json:"present"`
	Limit     *int   `json:"limit"`
	Sent      int    `json:"sent"`
	Remaining *int   `json:"remaining"`
}

// Quota counts messages (not SMS parts) for the SIMs that are enabled in the app right now. nil = unlimited.
type Quota struct {
	Date      string     `json:"date"`
	Limit     *int       `json:"limit"`
	Sent      int        `json:"sent"`
	Pending   int        `json:"pending"`
	Remaining *int       `json:"remaining"`
	Sims      []simQuota `json:"sims"`
}

type Message struct {
	ID        string    `json:"id"`
	Status    string    `json:"status"`
	Recipient string    `json:"recipient"`
	SimUsed   *string   `json:"sim_used"`
	CreatedAt string    `json:"created_at"`
	Error     *apiError `json:"error"`
	Quota     *Quota    `json:"quota"`
}

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func num(p *int) string {
	if p == nil {
		return "unlimited"
	}
	return strconv.Itoa(*p)
}

// api returns the HTTP status and decodes the JSON body into out (if not nil).
func api(method, path string, body any, headers map[string]string, out any) (int, error) {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, base+path, rd)
	req.Header.Set("Authorization", "Bearer "+key)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "opensms-example/1.0")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	res, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	defer res.Body.Close()
	data, _ := io.ReadAll(res.Body)
	if out != nil {
		_ = json.Unmarshal(data, out)
	}
	return res.StatusCode, nil
}

func showQuota(q *Quota) {
	fmt.Printf("quota today (%s): limit=%s sent=%d pending=%d left=%s\n", q.Date, num(q.Limit), q.Sent, q.Pending, num(q.Remaining))
	for _, s := range q.Sims {
		state := "off"
		if s.Enabled && s.Present {
			state = "on"
		}
		fmt.Printf("  %s [%s] limit=%s sent=%d left=%s\n", s.Sim, state, num(s.Limit), s.Sent, num(s.Remaining))
	}
}

func uuid() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// sendSMS queues one SMS (max 100 characters). The Idempotency-Key makes a retry after a network error safe.
func sendSMS(to, text string) (*Message, error) {
	var out struct {
		Message
		Err *apiError `json:"error"`
	}
	status, err := api("POST", "/v1/sms/send",
		map[string]any{"recipient": to, "message": text, "sim": "auto", "ttl_seconds": 120},
		map[string]string{"Idempotency-Key": uuid()}, &out)
	if err != nil {
		return nil, err
	}
	if status != 200 && status != 202 {
		// e.g. invalid_recipient, destination_not_supported, message_too_long, quota_exceeded (429), device_not_configured
		return nil, fmt.Errorf("send failed: HTTP %d %+v", status, out.Err)
	}
	return &out.Message, nil
}

// waitForResult polls GET /v1/sms/{id} until the message has a final status.
func waitForResult(id string) (*Message, error) {
	deadline := time.Now().Add(90 * time.Second)
	for time.Now().Before(deadline) {
		var m Message
		if status, err := api("GET", "/v1/sms/"+id, nil, nil, &m); err == nil && status == 200 && final[m.Status] {
			return &m, nil
		}
		time.Sleep(2 * time.Second)
	}
	return nil, fmt.Errorf("timed out waiting for a result")
}

// verifyCallback checks a webhook from OpenSMS. secret is shown in the app (Callbacks). Pass the RAW request body.
func verifyCallback(secret string, header http.Header, rawBody []byte) bool {
	ts := header.Get("X-OpenSMS-Timestamp")
	sec, err := strconv.ParseInt(ts, 10, 64)
	if err != nil || math.Abs(float64(time.Now().Unix()-sec)) > 300 {
		return false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(ts + "."))
	mac.Write(rawBody)
	expected := "v1=" + hex.EncodeToString(mac.Sum(nil))
	return hmac.Equal([]byte(expected), []byte(header.Get("X-OpenSMS-Signature")))
}

func main() {
	if key == "" {
		log.Fatal("set OPENSMS_KEY")
	}
	var q Quota
	if status, err := api("GET", "/v1/quota", nil, nil, &q); err == nil && status == 200 {
		showQuota(&q)
	}

	if len(os.Args) > 1 {
		job, err := sendSMS(os.Args[1], "Hello from OpenSMS")
		if err != nil {
			log.Fatal(err)
		}
		fmt.Println("queued:", job.ID, job.Status)
		if job.Quota != nil {
			showQuota(job.Quota)
		}
		done, err := waitForResult(job.ID)
		if err != nil {
			log.Fatal(err)
		}
		sim := "-"
		if done.SimUsed != nil {
			sim = *done.SimUsed
		}
		fmt.Println("final:", done.Status, "via", sim, done.Error)
		return
	}

	var page struct {
		Data []Message `json:"data"`
	}
	_, _ = api("GET", "/v1/sms?limit=5", nil, nil, &page)
	for _, m := range page.Data {
		fmt.Println(m.CreatedAt, m.Recipient, m.Status, m.Error)
	}
}
