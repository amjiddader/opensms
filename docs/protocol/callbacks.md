# Callbacks (webhooks)

Callbacks tell your server what happened to a message. They are configured **in the Android app** (Callback settings: URL + on/off switch), stored on the backend and **sent by the backend** (not by the phone), so they work even if the phone is offline when the result is known (for example `expired`).

When callbacks are switched off, nothing is posted.

## Request

`POST <your URL>` with `Content-Type: application/json`, `User-Agent: OpenSMS-Webhook/1`.

| Header | Meaning |
| --- | --- |
| `X-OpenSMS-Event` | `sms.sent`, `sms.delivered`, `sms.failed`, `sms.unknown` or `sms.test` |
| `X-OpenSMS-Delivery` | unique delivery id (`cbd_…`); the same id is used for retries – **deduplicate on it** |
| `X-OpenSMS-Timestamp` | Unix seconds |
| `X-OpenSMS-Signature` | `v1=` + hex `HMAC_SHA256(secret, timestamp + "." + rawBody)` |

The signing secret (`oss_…`) is shown in the app (Callback settings) and can be rotated there. Verify the signature over the **raw** body and reject timestamps older than 5 minutes.

```js
// Node.js
import { createHmac, timingSafeEqual } from "node:crypto";
function verify(secret, headers, rawBody) {
  const ts = headers["x-opensms-timestamp"];
  if (Math.abs(Date.now() / 1000 - Number(ts)) > 300) return false;
  const expected = "v1=" + createHmac("sha256", secret).update(`${ts}.${rawBody}`).digest("hex");
  const got = headers["x-opensms-signature"] ?? "";
  return got.length === expected.length && timingSafeEqual(Buffer.from(got), Buffer.from(expected));
}
```

## Body

```json
{
  "id": "cbd_01j9…",
  "event": "sms.sent",
  "created_at": "2026-10-17T08:00:03.120Z",
  "data": {
    "job_id": "job_01j9…",
    "status": "sent",
    "recipient": "+91******3210",
    "sim": "sim_1",
    "segments": { "estimated": 1, "total": 1, "sent": 1 },
    "error": null,
    "created_at": "…", "sent_at": "…", "delivered_at": null, "finished_at": "…"
  }
}
```

The message text and the full number are never included (the number is masked); match on `job_id` (returned by `POST /v1/sms/send`).

| `event` | `data.status` | When |
| --- | --- | --- |
| `sms.sent` | `sent` | all parts were handed to the network |
| `sms.delivered` | `delivered` | delivery report received (only if delivery reports are enabled and supported) |
| `sms.failed` | `failed` | confirmed not sent (`error.code`, e.g. `no_service`, `device_banned`) |
| `sms.failed` | `expired` | the phone did not accept the message before `ttl_seconds` |
| `sms.failed` | `undelivered` | sent, but the carrier reported non-delivery |
| `sms.unknown` | `unknown` | outcome ambiguous – it may or may not have been sent; never resent automatically |
| `sms.test` | – | sent by the "Test callback" button (`data.message`) |

A later event can supersede an earlier one for the same job (for example `sms.unknown` → `sms.sent`).

## Delivery rules

* Success = HTTP 2xx within 10 seconds. Redirects are **not** followed (a 3xx counts as a failure).
* Retries: after 30 s, 2 min, 10 min, 1 h and 6 h (6 attempts in total); then the delivery is marked `failed` (visible in the app and the admin dashboard).
* URL rules: public `https://`, port 443, a domain name (no IP addresses, `localhost`, `.local`/`.internal` names), no credentials in the URL.
