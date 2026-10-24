# Callbacks (webhooks)

Callbacks tell your server what happened to a message. They are configured **in the Android app** (Callback settings: URL + on/off switch), stored on the backend and **sent by the backend** (not by the phone), so they work even if the phone is offline when the result is known (for example `expired`).

When callbacks are switched off, nothing is posted.

## Request

`POST <your URL>` with `Content-Type: application/json`, `User-Agent: OpenSMS-Webhook/1`.

| Header | Meaning |
| --- | --- |
| `X-OpenSMS-Event` | `sms.sent`, `sms.delivered`, `sms.failed`, `sms.unknown`, `sms.received` or `sms.test` |
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

## ReverseOTP: `sms.received`

With **Reverse SMS** switched on in the app (Settings → Reverse SMS), every SMS received on either SIM whose text matches the global hint (for example `ABC#`) is forwarded to the same callback URL, signed in the same way. Use it to let a user prove they own a phone number: they send `ABC# <code>` to your gateway number and you receive the sender's number.

```json
{
  "id": "cbd_01k7z0q5m2k3t7x9a1b2c3d4e8",
  "event": "sms.received",
  "created_at": "2026-10-17T08:05:01.450Z",
  "data": {
    "device_id": "dev_01k7z0q5m2k3t7x9a1b2c3d4e1",
    "sim": "sim_2",
    "from": "+919812345678",
    "message": "ABC# 482913",
    "hint": "ABC#",
    "received_at": "2026-10-17T08:05:00.000Z",
    "forwarded_at": "2026-10-17T08:05:01.450Z",
    "job_id": null
  }
}
```

* `from` is the real sender address and `message` the complete text (these are the only events that contain them). `sim` is the SIM that received it (`null` if unknown). `received_at` is when the phone got the SMS; `forwarded_at` when the backend queued the callback.
* Matching is done on the phone: case-insensitive, either *starts with* (default) or *contains* the hint. All other messages are ignored and never leave the phone.
* There is **no rate limit**: every matching message produces one callback. Deduplicate on `X-OpenSMS-Delivery` (retries reuse the id).
* Delivery, signing, retries and the delivery log are the same as for the other events. `data.from` and `data.message` are stored encrypted and erased from the delivery log once the delivery is final.
* If the phone is offline, the message is kept and forwarded when it reconnects (up to 7 days). It needs the callback to be enabled; otherwise the message is dropped.
* Android does not allow a normal app to delete or hide incoming messages, so a forwarded message also remains in the phone's own SMS app.

## Delivery rules

* Success = HTTP 2xx within 10 seconds. Redirects are **not** followed (a 3xx counts as a failure).
* Retries: after 30 s, 2 min, 10 min, 1 h and 6 h (6 attempts in total); then the delivery is marked `failed` (visible in the app and the admin dashboard).
* URL rules: public `https://`, port 443, a domain name (no IP addresses, `localhost`, `.local`/`.internal` names), no credentials in the URL.
