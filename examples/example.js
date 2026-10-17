#!/usr/bin/env node
// OpenSMS API example (Node.js 18+, no dependencies).
//
//   export OPENSMS_KEY=osk_...            # API key from the app: Settings -> API keys
//   node example.js                       # read-only: show quota and the last messages
//   node example.js +919876543210         # also send "Hello from OpenSMS" and follow it to the end
//
// Optional: OPENSMS_BASE (default https://opensms.wansaw.com)
import { createHmac, randomUUID, timingSafeEqual } from "node:crypto";

const BASE = (process.env.OPENSMS_BASE ?? "https://opensms.wansaw.com").replace(/\/$/, "");
const KEY = process.env.OPENSMS_KEY;
if (!KEY) throw new Error("set OPENSMS_KEY");
const FINAL = new Set(["sent", "delivered", "failed", "undelivered", "unknown", "expired"]);

/** Returns { status, body }. Errors come back as body.error = { code, message }. */
async function api(method, path, body, headers = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json", Accept: "application/json", "User-Agent": "opensms-example/1.0", ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

/** quota = messages (not SMS parts) for the SIMs that are enabled in the app right now. */
function showQuota(q) {
  console.log(`quota today (${q.date}): limit=${q.limit ?? "unlimited"} sent=${q.sent} pending=${q.pending} left=${q.remaining ?? "unlimited"}`);
  for (const s of q.sims) console.log(`  ${s.sim} [${s.enabled && s.present ? "on" : "off"}] limit=${s.limit} sent=${s.sent} left=${s.remaining}`);
}

/** Queues one SMS (max 100 characters). The Idempotency-Key makes a retry after a network error safe. */
async function sendSms(to, text, sim = "auto") {
  const { status, body } = await api("POST", "/v1/sms/send", { recipient: to, message: text, sim, ttl_seconds: 120 }, { "Idempotency-Key": randomUUID() });
  if (status !== 200 && status !== 202) {
    // e.g. invalid_recipient, destination_not_supported, message_too_long, quota_exceeded (429), device_not_configured
    throw new Error(`send failed: HTTP ${status} ${JSON.stringify(body.error)}`);
  }
  return body;
}

/** Polls GET /v1/sms/{id} until the message has a final status. */
async function waitForResult(id, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const { status, body } = await api("GET", `/v1/sms/${id}`);
    if (status === 200 && FINAL.has(body.status)) return body;
    await new Promise((r) => setTimeout(r, 2000));
  }
  throw new Error("timed out waiting for a result");
}

/** Check a webhook from OpenSMS. `secret` is shown in the app (Callbacks). Pass the RAW request body (string/Buffer). */
export function verifyCallback(secret, headers, rawBody) {
  const ts = headers["x-opensms-timestamp"];
  if (Math.abs(Date.now() / 1000 - Number(ts)) > 300) return false;
  const expected = "v1=" + createHmac("sha256", secret).update(`${ts}.`).update(rawBody).digest("hex");
  const got = headers["x-opensms-signature"] ?? "";
  return got.length === expected.length && timingSafeEqual(Buffer.from(got), Buffer.from(expected));
}

const quota = await api("GET", "/v1/quota");
if (quota.status === 200) showQuota(quota.body);

const to = process.argv[2];
if (to) {
  const job = await sendSms(to, "Hello from OpenSMS");
  console.log("queued:", job.id, job.status);
  if (job.quota) showQuota(job.quota);
  const done = await waitForResult(job.id);
  console.log("final:", done.status, "via", done.sim_used, done.error ?? "");
} else {
  const { body } = await api("GET", "/v1/sms?limit=5");
  for (const m of body.data ?? []) console.log(m.created_at, m.recipient, m.status, m.error ?? "");
}
