# Architecture

> Status (2026-11-15): M1 modules are implemented and tested, and demo scenarios 1–5 were verified on an emulator. M2 added relay-risk flags and the webhook sink. M3 (EBT readiness) added `posguard-ebt`, `ebt-simulator`, and demo scenario 9, which have unit tests. Scenarios 7 and 9 were verified on an API 36 emulator on 2026-11-15. M4 (current) added `posguard-assess` and its Gradle plugin.

## Principle: wrap, never replace

posguard sits **around** a certified payment engine (a SoftPOS SDK or a POS terminal SDK). It never sits inside one. Card reading, EMV processing, PIN entry, and card-data encryption stay in certified code and hardware. posguard protects the merchant app that wraps them. See [ADR-001](decisions/ADR-001-wrap-not-replace.md).

```
┌──────────────────────────── Merchant POS app ────────────────────────────┐
│                                                                          │
│  Checkout UI ──► PosGuard.preflight() ──► Allow / Warn / Block           │
│       │                 │                                                │
│       │                 └─ posguard-integrity (device, app, screen)      │
│       │                                                                  │
│       ├──► TransactionLedger.begin() ── idempotency key ──┐              │
│       │                                                   ▼              │
│       │                                 ┌──────────────────────────────┐ │
│       └───────────────────────────────► │ Certified payment engine     │ │
│                                         │ (or mock-payment-engine)     │ │
│                                         │ card data never leaves here  │ │
│                                         └──────────────────────────────┘ │
│                                                                          │
│  posguard-telemetry ◄── events (scrubbed) ──► pluggable sinks            │
└──────────────────────────────────────────────────────────────────────────┘
```

## Modules

| Module | Type | Responsibility |
|---|---|---|
| `posguard-core` | Kotlin/JVM | Public API: `PosGuard`, `GuardVerdict`, `ReasonCode`, policy config, the `TransactionLedger` contract. No Android dependency, so it is fast to test. |
| `posguard-integrity` | Android library | Device, app, and screen integrity signals. Each signal fails safe to `INTEGRITY_UNAVAILABLE`. Also the experimental relay-risk monitor. |
| `posguard-integrity-play` | Android library (optional) | Play Integrity verdict signals. Separate so POS terminals without Google Play services avoid the Play libraries. |
| `posguard-ledger` | Android library | Outage-safe journal (Room) with idempotency keys and crash or network recovery (WorkManager). |
| `posguard-telemetry` | Android library | Event schema, card-data scrubber, local buffer, pluggable sinks. |
| `posguard-ebt` | Android library | EBT checkout flows around a certified engine: config-driven SNAP split, split-tender planning, chip-first entry with fallback, partial approvals, receipt fields. No card data, no PIN. |
| `posguard-assess` | Kotlin/JVM + CLI | Scans an Android POS codebase as text and reports MASVS-mapped gaps ranked by risk × ease of fix. Rules plug in via `ServiceLoader`. Depends on nothing else in posguard. See [assess.md](assess.md). |
| `posguard-assess-gradle` | Gradle plugin (not published yet) | `dev.posguard.assess`: a cacheable `assessPosguard` task that writes Markdown and HTML reports. |
| `mock-payment-engine` | Android library (not published) | Fake certified SDK with failure modes for demos and tests. **Not real.** |
| `ebt-simulator` | Android library (not published) | Fake EBT engine and processor: fictional cards and balances, partial approvals, chip failures. **Not real.** |
| `demo-app` | Android app | Fictional "Sunrise Market" with Demo controls. Runs fully offline. |

Dependency direction: every runtime library depends on `posguard-core` (the assessment tool is standalone), and nothing depends on `demo-app` or `mock-payment-engine` except the demo itself.

## Core API (`posguard-core`)

| Package | Key types |
|---|---|
| `dev.posguard.core` | `PosGuard` (+ `Builder`), `VerdictEvaluator` |
| `dev.posguard.core.verdict` | `ReasonCode` (stable `code` + plain-English `description`), `GuardAction`, `GuardVerdict` (`Allow` / `Warn` / `Block`) |
| `dev.posguard.core.policy` | `GuardPolicy` (defaults, builder, strict `fromProperties`). See [policy-defaults.md](policy-defaults.md) |
| `dev.posguard.core.signal` | `IntegritySignal`, `SignalResult` (`Clear` / `Detected` / `Unavailable`) |
| `dev.posguard.core.ledger` | `TransactionLedger`, `TxnState`, `TxnStateMachine`, `TransactionRecord`, `Money`, `Tender`, `EngineResult`, `EngineStatusSource`, `combinedStatusSource`. See [ADR-002](decisions/ADR-002-idempotent-ledger.md) |
| `dev.posguard.core.event` | `GuardEvent`, `GuardEventListener` |

**Preflight flow:** `PosGuard.preflight()` switches to a background dispatcher and runs all signals in parallel, each with a timeout (default 3 s). Any exception or timeout becomes `SignalResult.Unavailable` → `INTEGRITY_UNAVAILABLE`, and exception messages are dropped so they can't leak data. `VerdictEvaluator` applies the policy (the strictest action wins), then listeners receive `PreflightEvaluated`. A throwing listener is ignored.

## Ledger (`posguard-ledger`)

Room-backed `TransactionLedger` built with `PosGuardLedger.Builder`. Writes are serialized in-process and use compare-and-set on a version column. `recoverPending()` resolves pending payments by idempotency key, and `LedgerRecoveryWorker` (WorkManager) repeats recovery on reconnect. See [ledger.md](ledger.md).

## Integrity (`posguard-integrity`, `posguard-integrity-play`)

`PosGuardIntegrity.install(application)` attaches the obscured-touch and screen-recording monitors and returns the default signals. `PaymentScreenProtection` hardens the payment window. Play Integrity signals live in an optional module and share one verdict fetch per preflight. See [integrity.md](integrity.md) and [threat-model.md](threat-model.md).

`RelayRiskMonitor` (experimental) runs pluggable `RelayRiskRule`s on non-sensitive tap metadata after the engine authorizes a payment. Flagged payments stay `AUTHORIZED` in the ledger until the merchant reviews them, and `relay.signal` makes preflight WARN meanwhile. It never declines. See [relay-risk.md](relay-risk.md).

## Telemetry (`posguard-telemetry`)

`PosGuardTelemetry` is a `GuardEventListener`. Every event is scrubbed before buffering (card numbers, track data, emails, phones, sensitive keys; it fails closed). The buffer is bounded and delivers to pluggable `TelemetrySink`s (in-memory, rotating JSON lines, and a signed HTTPS `WebhookSink`), removing events only after every sink accepts them. See [event-schema.md](event-schema.md).

## EBT (`posguard-ebt`, `ebt-simulator`)

`EbtCheckout` runs each EBT leg (SNAP, EBT cash) as its own ledger transaction: `EbtEntryFlow` decides the next card-entry prompt (chip first, magstripe only after a chip failure), the integrator's `EbtEngine` reads the card and handles the PIN, and partial approvals are recorded as the ledger's `approvedAmount`. `BasketSplitter` and `EbtTenderPlanner` split the basket by config and move a partial-approval shortfall to the next tender. When card and EBT legs use different engines, the ledger recovers through `combinedStatusSource`. See [ebt.md](ebt.md) and [ADR-003](decisions/ADR-003-ebt-split-tender.md).

## Mock engine (`mock-payment-engine`)

A fake SoftPOS SDK (**not real**) with a simulated processor that deduplicates by idempotency key and implements `EngineStatusSource`. It can simulate approve, decline, timeout, network drop after submit, duplicate response, and engine crash. See [mock-engine.md](mock-engine.md).

## Extension points (open-core boundary)

Private products must plug in **without forking**:

- **Telemetry sinks**: an interface. A hosted dashboard is just another sink.
- **Policy and thresholds**: loaded from config supplied by the integrating app, never hardcoded. Public defaults are conservative and documented.
- **EBT eligibility and engines**: eligibility rules come from config (`EbtConfig.fromProperties`), and `EbtEngine` is an interface any certified engine can implement.
- **Detection rules and integrity providers**: interfaces (`IntegritySignal`, `RelayRiskRule`), so a real Play Integrity provider, a fake provider, and third-party providers are interchangeable.

## Build

- Gradle 9.9.1 (Kotlin DSL), AGP 9.5.1 with built-in Kotlin, Kotlin 2.4.21.
- `build-logic/` convention plugins: `posguard.android.library`, `posguard.jvm.library`, `posguard.publish`.
- minSdk 26, compileSdk 37, targetSdk 36. JVM target 17.
- Libraries use explicit API mode and do not force a DI framework. Hilt is used only in `demo-app`.

## Threading

All integrity checks and I/O run off the main thread through coroutines. Public suspend functions are main-safe.
