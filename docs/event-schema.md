# Telemetry event schema (v1)

Events are produced by `posguard-telemetry` and sent to sinks as JSON objects (one per line in `JsonLinesFileSink`). Every string field has passed through the scrubber **before** buffering.

## Fields

| Field | Type | Notes |
|---|---|---|
| `schemaVersion` | int | `1`. A field may be added in a minor version. Removing or renaming one bumps this number. |
| `eventId` | string | UUID. Unique per event. Receivers can use it to drop duplicates (a batch may be redelivered after a sink failure). |
| `type` | string | `PREFLIGHT_EVALUATED`, `TXN_STATE_CHANGED`, `APP_ERROR`, `APP_EVENT`. |
| `timestamp` | string | ISO-8601 UTC instant. |
| `verdict` | string \| null | `ALLOW`, `WARN`, `BLOCK`. Set on `PREFLIGHT_EVALUATED`. |
| `reasonCodes` | string[] | Stable `ReasonCode.code` values. Can be non-empty even with `ALLOW`. |
| `txn` | object \| null | Set on `TXN_STATE_CHANGED`: `txnId` (opaque), `from` (null on creation), `to`. |
| `device` | object | `manufacturer`, `model`, `osVersion`, `sdkInt`, `appVersion`. |
| `message` | string \| null | App errors and events only. Scrubbed. |
| `attributes` | object | String → string. App errors and events only. Keys and values scrubbed, and values of sensitive keys replaced with `[REDACTED]`. |

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
    .addSink(myDashboardSink) // any TelemetrySink: webhook (V2), hosted dashboard, ...
    .build()

PosGuard.Builder(ledger).addListener(telemetry)
PosGuardLedger.Builder(context, engine).addListener(telemetry)
telemetry.record(EventType.APP_ERROR, "printer offline", mapOf("lane" to "3"))
```
