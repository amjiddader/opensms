#!/usr/bin/env python3
"""OpenSMS API example (Python 3.8+, standard library only).

    export OPENSMS_KEY=osk_...            # API key from the app: Settings -> API keys
    python3 example.py                    # read-only: show quota and the last messages
    python3 example.py +919876543210      # also send "Hello from OpenSMS" and follow it to the end

Optional: OPENSMS_BASE (default https://opensms.wansaw.com)
"""
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("OPENSMS_BASE", "https://opensms.wansaw.com").rstrip("/")
KEY = os.environ["OPENSMS_KEY"]
FINAL = {"sent", "delivered", "failed", "undelivered", "unknown", "expired"}


def api(method, path, body=None, headers=None):
    """Returns (http_status, parsed_json). Errors come back as {"error": {"code", "message"}}."""
    req = urllib.request.Request(
        BASE + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Authorization": f"Bearer {KEY}",
            "Content-Type": "application/json",
            "Accept": "application/json",
            # Cloudflare rejects Python's default "Python-urllib" User-Agent with a bare 403, so always set one.
            "User-Agent": "opensms-example/1.0",
            **(headers or {}),
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except ValueError:
            return e.code, {}


def show_quota(q):
    """quota = messages (not SMS parts) for the SIMs that are enabled in the app right now."""
    limit = "unlimited" if q["limit"] is None else q["limit"]
    left = "unlimited" if q["remaining"] is None else q["remaining"]
    print(f"quota today ({q['date']}): limit={limit} sent={q['sent']} pending={q['pending']} left={left}")
    for s in q["sims"]:
        state = "on" if s["enabled"] and s["present"] else "off"
        print(f"  {s['sim']} [{state}] limit={s['limit']} sent={s['sent']} left={s['remaining']}")


def send_sms(to, text, sim="auto"):
    """Queues one SMS (max 100 characters). Idempotency-Key makes a retry after a network error safe."""
    status, body = api(
        "POST", "/v1/sms/send",
        {"recipient": to, "message": text, "sim": sim, "ttl_seconds": 120},
        {"Idempotency-Key": str(uuid.uuid4())},
    )
    if status not in (200, 202):
        # e.g. invalid_recipient, destination_not_supported, message_too_long, quota_exceeded (429), device_not_configured
        raise SystemExit(f"send failed: HTTP {status} {body.get('error')}")
    return body


def wait_for_result(job_id, timeout=90):
    """Polls GET /v1/sms/{id} until the message has a final status."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        status, job = api("GET", f"/v1/sms/{job_id}")
        if status == 200 and job["status"] in FINAL:
            return job
        time.sleep(2)
    raise SystemExit("timed out waiting for a result")


def verify_callback(secret, headers, raw_body):
    """Check a webhook from OpenSMS. `secret` is shown in the app (Callbacks). Use the RAW request body."""
    ts = headers["X-OpenSMS-Timestamp"]
    if abs(time.time() - int(ts)) > 300:
        return False
    expected = "v1=" + hmac.new(secret.encode(), f"{ts}.".encode() + raw_body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, headers.get("X-OpenSMS-Signature", ""))


if __name__ == "__main__":
    status, q = api("GET", "/v1/quota")
    if status == 200:
        show_quota(q)

    if len(sys.argv) > 1:
        job = send_sms(sys.argv[1], "Hello from OpenSMS")
        print("queued:", job["id"], job["status"])
        if "quota" in job:
            show_quota(job["quota"])
        final = wait_for_result(job["id"])
        print("final:", final["status"], "via", final["sim_used"], final["error"] or "")
    else:
        status, page = api("GET", "/v1/sms?limit=5")
        for m in page.get("data", []):
            print(m["created_at"], m["recipient"], m["status"], m["error"] or "")
