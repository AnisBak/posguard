# Telemetry event schema (v1)

Events are produced by `posguard-telemetry` and sent to sinks as JSON objects (one per line in `JsonLinesFileSink`). Every string field has passed through the scrubber **before** buffering.

## Fields

| Field | Type | Notes |
|---|---|---|
| `schemaVersion` | int | `1`. A field may be added in a minor version. Removing or renaming one bumps this number. |
| `eventId` | string | UUID. Unique per event. Receivers can use it to drop duplicates (a batch may be redelivered after a sink failure). |
| `type` | string | `PREFLIGHT_EVALUATED`, `TXN_STATE_CHANGED`, `APP_ERROR`, `APP_EVENT`, `RELAY_RISK_FLAGGED`, `RELAY_RISK_REVIEWED`. New types may be added in a minor version, so ignore types you don't know. |
| `timestamp` | string | ISO-8601 UTC instant. |
| `verdict` | string \| null | `ALLOW`, `WARN`, `BLOCK`. Set on `PREFLIGHT_EVALUATED`. |
| `reasonCodes` | string[] | Stable `ReasonCode.code` values. Can be non-empty even with `ALLOW`. |
| `txn` | object \| null | Set on `TXN_STATE_CHANGED`: `txnId` (opaque), `from` (null on creation), `to`. |
| `device` | object | `manufacturer`, `model`, `osVersion`, `sdkInt`, `appVersion`. |
| `message` | string \| null | App errors and events only. Scrubbed. |
| `attributes` | object | String → string. Keys and values scrubbed, and values of sensitive keys replaced with `[REDACTED]`. App errors and events carry app-supplied attributes. `RELAY_RISK_FLAGGED` carries `txnId` and `rules` (comma-separated rule ids), and `RELAY_RISK_REVIEWED` carries `txnId`. |

`RELAY_RISK_FLAGGED` also sets `reasonCodes` to `["RELAY_RISK_PATTERN"]`. It is experimental and never means a decline ([relay-risk.md](relay-risk.md)).

## What is never included

Card numbers, PINs, track data, CVV, amounts, engine references, integrity signal details, and screen or app content. posguard's own events carry none of these, and the scrubber is a second line of defense for app-supplied text.

## Examples

```json
{"schemaVersion":1,"eventId":"4f1c2a9e-6d0b-4c1e-9a57-0d6f3b2e8c11","type":"PREFLIGHT_EVALUATED","timestamp":"2026-11-14T12:00:00Z","verdict":"BLOCK","reasonCodes":["SCREEN_OVERLAY_PRESENT"],"txn":null,"device":{"manufacturer":"Acme","model":"Pixel Test","osVersion":"15","sdkInt":35,"appVersion":"0.1.0"},"message":null,"attributes":{}}
```

```json
{"schemaVersion":1,"eventId":"9b2d…","type":"TXN_STATE_CHANGED","timestamp":"2026-11-14T12:00:02Z","verdict":null,"reasonCodes":[],"txn":{"txnId":"c0a8…","from":"SUBMITTED","to":"UNKNOWN"},"device":{…},"message":null,"attributes":{}}
```

```json
{"schemaVersion":1,"eventId":"…","type":"APP_ERROR","timestamp":"…","verdict":null,"reasonCodes":[],"txn":null,"device":{…},"message":"decline for [REDACTED_PAN]","attributes":{"lane":"3","cvv":"[REDACTED]"}}
```

## Scrubber placeholders

| Placeholder | Replaces |
|---|---|
| `[REDACTED_TRACK]` | Magnetic-stripe Track 1 / Track 2 shapes |
| `[REDACTED_PAN]` | Luhn-valid 13–19 digit runs (spaces and dashes allowed) |
| `[REDACTED_NUMBER]` | Other 13–19 digit runs (for example, a mistyped card number) |
| `[REDACTED_EMAIL]` | Email addresses |
| `[REDACTED_PHONE]` | North American and international phone numbers |
| `[REDACTED]` | Any value whose key is sensitive (`card`, `pan`, `cvv`, `pin`, `track`, `token`, `email`, `phone`, …) |
| `[SCRUB_ERROR]` | A field where a scrub rule failed. It fails closed. |

Known false positives (redacting is the safe direction): 13–19 digit values such as epoch milliseconds, and some 10-digit identifiers that look like phone numbers. Canonical UUIDs are left intact.

## Wiring

```kotlin
val telemetry = PosGuardTelemetry.Builder(DeviceContext.fromSystem(context))
    .addSink(JsonLinesFileSink(File(context.noBackupFilesDir, "posguard-telemetry")))
    .addSink(myDashboardSink) // any TelemetrySink: WebhookSink, hosted dashboard, ...
    .build()

PosGuard.Builder(ledger).addListener(telemetry)
PosGuardLedger.Builder(context, engine).addListener(telemetry)
telemetry.record(EventType.APP_ERROR, "printer offline", mapOf("lane" to "3"))
```

Events leave the buffer when it reaches the batch size, or when you call `telemetry.flush()`. Call `flush()` from a periodic job (for example, WorkManager) or when the app goes to the background, so small batches don't wait.

## Webhook delivery (`WebhookSink`)

`WebhookSink` posts batches to an HTTPS endpoint you control, so events can go to the tools a merchant already uses. No endpoint or secret ships with posguard. Load both from your own untracked config. The app must declare `android.permission.INTERNET`. The library does not add it.

```kotlin
val webhook = WebhookSink.Builder(config.webhookUrl)          // https:// only
    .signingSecret(config.webhookSecret)                      // optional, recommended
    .header("Authorization", "Bearer ${config.webhookToken}") // optional
    .build()
PosGuardTelemetry.Builder(device).addSink(webhook).build()
```

**Request.** One `POST` per batch, `Content-Type: application/json; charset=utf-8`:

```json
{"schemaVersion":1,"events":[{"schemaVersion":1,"eventId":"…","type":"APP_ERROR", …}]}
```

Each element of `events` is a v1 event exactly as described above, already scrubbed.

**Responses.** Any 2xx response accepts the batch. Any other status, a timeout (connect 5 s, read 10 s by default), or a network error counts as failure. The batch stays in the buffer and is sent again on the next flush. Redirects are never followed. **Receivers must deduplicate by `eventId`**, because a retried batch can repeat events that already arrived.

**Signature.** With a signing secret, every request carries:

```
X-Posguard-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<raw body>")>
```

To verify on the receiver:
1. Split the header on `,`, then on the first `=`, and read `t` and `v1`.
2. Compute HMAC-SHA256 over the bytes `t + "." + rawBody` with the shared secret, encoded as lowercase hex.
3. Compare it to `v1` with a constant-time comparison. Reject the request if they differ.
4. Reject requests whose `t` is more than a few minutes away from your clock, to block replays.

```python
import hmac, hashlib, time

def verify(header: str, raw_body: bytes, secret: bytes, tolerance_s: int = 300) -> bool:
    parts = dict(p.split("=", 1) for p in header.split(","))
    t, v1 = parts.get("t", ""), parts.get("v1", "")
    if not t.isdigit() or abs(time.time() - int(t)) > tolerance_s:
        return False
    expected = hmac.new(secret, t.encode() + b"." + raw_body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, v1)
```

`http://` URLs are rejected unless you call `.allowInsecureHttp()`, which is meant for a receiver on your own machine during development only.
