# WhatsApp OTP (Reverse OTP over WhatsApp / "WABA verify")

Lets your users prove they own a phone number by **sending you a WhatsApp message**. The platform owns one WhatsApp Business number (Meta Cloud API). Every phone running the OpenSMS app has its own public **identifier** (for example `wansaw`). Your backend asks OpenSMS for a one-time code, shows the user a "Verify with WhatsApp" button, the user sends `wansaw#<code>` to the business number, and OpenSMS calls your webhook with the user's phone number.

It is the same idea as Reverse SMS (`sms.received`), but the message travels over WhatsApp, costs the user nothing extra and does not need a SIM to receive it. The phone is only used as the **owner** of the identifier and as an **online check**; no WebSocket protocol change was needed.

## 1. Flow

```
 your backend                  OpenSMS Worker                       Meta / WhatsApp           end user
      │ POST /v1/whatsapp/init  (Bearer osk_…)                          │                        │
      │ ───────────────────────►  checks key, switches, phone online    │                        │
      │ ◄───────────────────────  {code, message "wansaw#123456789",    │                        │
      │                            waba_number, deeplink, session_id}   │                        │
      │ show "Verify using WhatsApp" button (deeplink) ─────────────────┼───────────────────────►│
      │                                                                 │ ◄── user sends "wansaw#123456789"
      │                           POST /v1/waba/webhook (X-Hub-Signature-256) ◄──┘
      │                           verify signature → parse → identifier → device
      │                           online rule → session lookup → atomic claim
      │ ◄─ POST your webhook "whatsapp.verified" {phone, name, code, ref …} (signed, X-OpenSMS-Signature)
      │ (or poll GET /v1/whatsapp/sessions/{id})
```

## 2. API (integrator)

### `POST /v1/whatsapp/init`

`Authorization: Bearer osk_…` (scope `sms:send`). Optional header `Idempotency-Key`.

```json
{ "ref": "login-8841", "expected_phone": "+919812345678", "ttl_seconds": 300 }
```

All fields are optional. `ref` (≤128 chars) is your own id and is echoed back everywhere. `expected_phone`: only this WhatsApp number can complete the session. `ttl_seconds` 60–900 (default from the admin page, 300).

Response `201` (`200` for an idempotent replay, header `Idempotent-Replayed: true`):

```json
{
  "status": "success",
  "session_id": "wvs_k3m9x1q7t5a2c8d4",
  "code": "482913057",
  "message": "wansaw#482913057",
  "identifier": "wansaw",
  "waba_number": "+15551234567",
  "deeplink": "https://wa.me/15551234567?text=wansaw%23482913057",
  "ref": "login-8841",
  "expected_phone": "+919812345678",
  "timestamp": "2026-10-28T10:00:00.000Z",
  "expires_at": "2026-10-28T10:05:00.000Z",
  "expires_in_seconds": 300,
  "replay": false
}
```

Show a button that opens `deeplink` (WhatsApp opens with `message` prefilled; the user only presses send). The `#` **must** be percent-encoded (`%23`) in links, otherwise it is treated as a URL fragment and dropped; use the returned `deeplink` instead of building your own.

Errors:

| HTTP | code | Meaning |
| --- | --- | --- |
| 401/403 | `unauthorized`, `key_*`, `scope_missing`, `ip_not_allowed`, `device_banned` | normal API-key errors |
| 403 | `whatsapp_disabled` | master switch is off |
| 409 | `whatsapp_not_configured` | opensms has not enabled the business number |
| 409 | `identifier_not_set` | the phone has no identifier, or WhatsApp OTP is off in the app |
| 409 | `device_offline` | the phone is not connected **right now** |
| 409 | `conflict` | the `Idempotency-Key` belongs to a session that is no longer pending |

### `GET /v1/whatsapp/sessions/{id}`

Only sessions created with the same API key are visible.

```json
{ "session_id": "wvs_…", "status": "verified", "ref": "login-8841", "created_at": "…", "expires_at": "…", "verified_at": "…",
  "phone": "+919812345678", "phone_masked": "+91********78", "name": "Asha" }
```

`status`: `pending`, `verified`, `expired` (pending past `expires_at`), `cancelled` (identifier changed). `phone` and `name` are only present for about an hour after verification (then `null`; `phone_masked` stays).

## 3. The webhook you receive: `whatsapp.verified`

Sent to the phone's **WhatsApp webhook URL** (App → Settings → WhatsApp OTP). If no URL is set nothing is posted; poll the session instead. Same headers, signature and URL rules as [callbacks](callbacks.md); the signing secret is the separate `ows_…` secret shown on that screen.

```json
{
  "id": "cbd_01k8…",
  "event": "whatsapp.verified",
  "created_at": "2026-10-28T10:00:41.200Z",
  "data": {
    "status": "success",
    "session_id": "wvs_k3m9x1q7t5a2c8d4",
    "ref": "login-8841",
    "code": "482913057",
    "message": "wansaw#482913057",
    "identifier": "wansaw",
    "phone": "+919812345678",
    "name": "Asha",
    "device_id": "dev_01k7…",
    "verified_at": "2026-10-28T10:00:41.200Z"
  }
}
```

* `phone` is the sender's WhatsApp number from Meta (`wa_id` with a `+`). `name` is the WhatsApp profile name (or `null`).
* **Always compare `phone` with the number you expect** when your flow knows it (or pass `expected_phone` to `init`). A code proves "whoever sent this message holds this number", not "the person I started the login for".
* Retries: immediately, then after 5 s, 30 s and 2 min (4 attempts) because codes are short-lived. Deduplicate on `X-OpenSMS-Delivery`. Use `GET /v1/whatsapp/sessions/{id}` as a fallback.
* `whatsapp.test` is sent by the app's "Send test event" button.

## 4. What happens to an incoming WhatsApp message

`POST /v1/waba/webhook` (public, authenticated by signature), in order:

1. `WABA_APP_SECRET` must exist (else 503). Body ≤ 512 KB (else 413).
2. `X-Hub-Signature-256` = `sha256=` + HMAC-SHA256(app secret, **raw body**), compared in constant time. Missing/invalid → **401**, before any database access.
3. JSON is parsed; the master switch must be on; messages from other `phone_number_id`s, status updates and non-text messages are dropped.
4. The text must contain `identifier#code` (case-insensitive, optional spaces around `#`, code 4–12 digits). Ordinary chat is ignored and **not logged**.
5. Duplicate Meta delivery (same `wamid`) → ignored.
6. Identifier → phone. Then, recording one outcome in the log:

| Outcome | Reason | Callback? |
| --- | --- | --- |
| `unknown_identifier` | no phone has this identifier | no |
| `disabled` | WhatsApp OTP is off on that phone | no |
| `device_unavailable` | device is not approved / banned | no |
| `device_offline` | not connected now and last seen more than `waba_offline_grace_minutes` (default **30**) ago | no |
| `no_session` | no pending session with this code (wrong, already used, cancelled) | no |
| `expired` | the session's `expires_at` passed | no |
| `mismatch` | session has `expected_phone` and the sender differs (the session stays pending) | no |
| `verified` | success | yes, if a WhatsApp webhook URL is set |

7. Success path: the session is claimed with a single conditional update (`pending → verified`), so a code works **once** even with concurrent deliveries. The callback is stored encrypted and its first attempt is sent after the HTTP response (`waitUntil`).

The webhook always answers `200` once the signature is valid and processing succeeded. If processing throws, it answers 500 so Meta retries; handling is idempotent per message id.

"Online" has two meanings: **`init` needs the phone connected now** (so you do not show a button that cannot work); **the callback needs the phone connected now or last seen within 30 minutes**, so a brief network drop between init and the user's message does not lose the verification.

## 5. Codes and sessions

* 9 digits by default (admin setting 6–12), from the platform CSPRNG with rejection sampling (no modulo bias). Leading zeros are kept.
* Lifetime 5 minutes by default (60–900 s). Single use.
* Uniqueness: `(device, HMAC(code))`; the DB unique index makes a collision impossible, and `init` redraws on the (≈10⁻⁹) collision.
* Codes are stored only as an HMAC (keyed with `STATIC_ID_PEPPER`). When an `Idempotency-Key` is used, the code is additionally kept AES-GCM encrypted until the session ends so a replay can return it.
* The verified phone number and name are kept AES-GCM encrypted on the session for one hour (for polling), then erased.


## 6. Example: verifying on your server

```js
// 1) start
const r = await fetch("https://opensms.wansaw.com/v1/whatsapp/init", {
  method: "POST",
  headers: { Authorization: `Bearer ${KEY}`, "Content-Type": "application/json", "Idempotency-Key": loginAttemptId },
  body: JSON.stringify({ ref: loginAttemptId, expected_phone: userPhone }),
}).then((x) => x.json());
// show <a href={r.deeplink}>Verify using WhatsApp</a>

// 2) webhook handler: verify X-OpenSMS-Signature (see callbacks.md), then
if (event.event === "whatsapp.verified" && event.data.ref === loginAttemptId && event.data.phone === userPhone) markVerified();
```
