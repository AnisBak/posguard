# Architecture

> Status (2026-11-14): all M1 modules, including `demo-app`, are implemented and tested. Demo scenarios 1–5 were verified on an emulator.

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
| `posguard-integrity` | Android library | Device, app, and screen integrity signals. Each signal fails safe to `INTEGRITY_UNAVAILABLE`. |
| `posguard-integrity-play` | Android library (optional) | Play Integrity verdict signals. Separate so POS terminals without Google Play services avoid the Play libraries. |
| `posguard-ledger` | Android library | Outage-safe journal (Room) with idempotency keys and crash or network recovery (WorkManager). |
| `posguard-telemetry` | Android library | Event schema, card-data scrubber, local buffer, pluggable sinks. |
| `mock-payment-engine` | Android library (not published) | Fake certified SDK with failure modes for demos and tests. **Not real.** |
| `demo-app` | Android app | Fictional "Sunrise Market" with Demo controls. Runs fully offline. |

Dependency direction: every library depends on `posguard-core`, and nothing depends on `demo-app` or `mock-payment-engine` except the demo itself.

## Core API (`posguard-core`)

| Package | Key types |
|---|---|
| `dev.posguard.core` | `PosGuard` (+ `Builder`), `VerdictEvaluator` |
| `dev.posguard.core.verdict` | `ReasonCode` (stable `code` + plain-English `description`), `GuardAction`, `GuardVerdict` (`Allow` / `Warn` / `Block`) |
| `dev.posguard.core.policy` | `GuardPolicy` (defaults, builder, strict `fromProperties`). See [policy-defaults.md](policy-defaults.md) |
| `dev.posguard.core.signal` | `IntegritySignal`, `SignalResult` (`Clear` / `Detected` / `Unavailable`) |
| `dev.posguard.core.ledger` | `TransactionLedger`, `TxnState`, `TxnStateMachine`, `TransactionRecord`, `Money`, `Tender`, `EngineResult`, `EngineStatusSource`. See [ADR-002](decisions/ADR-002-idempotent-ledger.md) |
| `dev.posguard.core.event` | `GuardEvent`, `GuardEventListener` |

**Preflight flow:** `PosGuard.preflight()` switches to a background dispatcher and runs all signals in parallel, each with a timeout (default 3 s). Any exception or timeout becomes `SignalResult.Unavailable` → `INTEGRITY_UNAVAILABLE`, and exception messages are dropped so they can't leak data. `VerdictEvaluator` applies the policy (the strictest action wins), then listeners receive `PreflightEvaluated`. A throwing listener is ignored.

## Ledger (`posguard-ledger`)

Room-backed `TransactionLedger` built with `PosGuardLedger.Builder`. Writes are serialized in-process and use compare-and-set on a version column. `recoverPending()` resolves pending payments by idempotency key, and `LedgerRecoveryWorker` (WorkManager) repeats recovery on reconnect. See [ledger.md](ledger.md).

## Integrity (`posguard-integrity`, `posguard-integrity-play`)

`PosGuardIntegrity.install(application)` attaches the obscured-touch and screen-recording monitors and returns the default signals. `PaymentScreenProtection` hardens the payment window. Play Integrity signals live in an optional module and share one verdict fetch per preflight. See [integrity.md](integrity.md) and [threat-model.md](threat-model.md).

## Telemetry (`posguard-telemetry`)

`PosGuardTelemetry` is a `GuardEventListener`. Every event is scrubbed before buffering (card numbers, track data, emails, phones, sensitive keys; it fails closed). The buffer is bounded and delivers to pluggable `TelemetrySink`s, removing events only after every sink accepts them. See [event-schema.md](event-schema.md).

## Mock engine (`mock-payment-engine`)

A fake SoftPOS SDK (**not real**) with a simulated processor that deduplicates by idempotency key and implements `EngineStatusSource`. It can simulate approve, decline, timeout, network drop after submit, duplicate response, and engine crash. See [mock-engine.md](mock-engine.md).

## Extension points (open-core boundary)

Private products must plug in **without forking**:

- **Telemetry sinks**: an interface. A hosted dashboard is just another sink.
- **Policy and thresholds**: loaded from config supplied by the integrating app, never hardcoded. Public defaults are conservative and documented.
- **Detection rules and integrity providers**: interfaces, so a real Play Integrity provider, a fake provider, and third-party providers are interchangeable.

## Build

- Gradle 9.9.1 (Kotlin DSL), AGP 9.5.1 with built-in Kotlin, Kotlin 2.4.21.
- `build-logic/` convention plugins: `posguard.android.library`, `posguard.jvm.library`, `posguard.publish`.
- minSdk 26, compileSdk 37, targetSdk 36. JVM target 17.
- Libraries use explicit API mode and do not force a DI framework. Hilt is used only in `demo-app`.

## Threading

All integrity checks and I/O run off the main thread through coroutines. Public suspend functions are main-safe.
