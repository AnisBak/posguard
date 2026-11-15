# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- `EngineResult.Authorized`, `EngineStatus.Authorized`, and `TransactionRecord` have a new optional `approvedAmount` for partial approvals. Ledger schema v3 adds a nullable column (non-destructive 2→3 migration, tested against the committed v2 schema).
- Demo catalog items have EBT categories. "Coffee" and "Sandwich" are now "Hot coffee" and "Hot sandwich".
- Roadmap re-prioritized (2026-11-15): EBT readiness (`posguard-ebt`, `ebt-simulator`, demo scenario 9) is now Milestone 3 and the current focus. The device watchdog, assessment tool, benchmarks, and Maven Central move to Milestone 4.
- `GuardEvent` has new subtypes. Code that matches `GuardEvent` exhaustively with `when` needs an `else` branch (KDoc now says so).

### Added
- **EBT readiness** (2026-11-15), simulator only:
  - New `posguard-ebt` module: `EbtConfig`/`EligibilityRules` (strict properties loader; no eligibility list shipped; unknown categories ineligible), `BasketSplitter`, `EbtTenderPlanner` (split tender and partial-approval shortfall), `EbtEntryFlow` (chip first, magstripe only after a failed chip attempt, never key entry), the `EbtEngine` adapter interface (card read and PIN stay in the certified engine), `EbtCheckout` (each leg in the ledger), and `EbtReceipt` (proposed minimum fields; balances masked for screens and redacted from `toString`). 27 tests.
  - New `ebt-simulator` module (**not real**): fictional cards (magstripe-only, chip, chip and contactless), balances, partial approvals, chip-read failure, unrecognized chip app, and network drop. 6 tests.
  - `combinedStatusSource` in core, so one ledger can recover legs sent to different engines. 3 tests.
  - `GuardEvent.EbtEntryFallback` / `EbtPartialApproval`, and telemetry types `EBT_ENTRY_FALLBACK` / `EBT_PARTIAL_APPROVAL` (no amounts or balances).
  - Demo scenario 9, **EBT purchase**: SNAP split, chip-to-tap fallback, partial approval, the rest by card, and a receipt. Plus magstripe-only card, reader without the SNAP chip app, and empty balance. 5 tests.
  - Docs: `docs/ebt.md` (with facts checked against the proposed rule's text), ADR-003, threat model T12, event schema, architecture, demo script.
- **Experimental relay-risk flags** (2026-11-15). `RelayRiskMonitor` in `posguard-integrity` runs pluggable `RelayRiskRule`s on non-sensitive `TapMetadata`. Built-in, untuned rules: wallet-tap burst (on by default), near-no-PIN-limit burst and slow card read (off until configured). It keeps a review queue and exposes `relay.signal`, which raises `RELAY_RISK_PATTERN` (capped at WARN) while a review is pending. It has a strict `RelayRiskConfig.fromProperties` loader and never declines. 26 tests. Docs: `docs/relay-risk.md`.
- `GuardEvent.RelayRiskFlagged` / `RelayRiskReviewed` in core, and telemetry event types `RELAY_RISK_FLAGGED` / `RELAY_RISK_REVIEWED`.
- `mock-payment-engine`: simulated tap metadata (`MockTapInfo`: entry mode and card read time) per charge, via `setNextTapProfile` and `tapInfo(key)`. The store still reads 0.1.0 records.
- Demo scenario 7, **burst of wallet taps**: the third wallet tap is approved but held for review, and preflight warns until the review is done. 4 tests.
- `WebhookSink` in `posguard-telemetry` (2026-11-15): posts scrubbed v1 event batches to an HTTPS endpoint, with optional HMAC-SHA256 request signing (`X-Posguard-Signature`), custom headers, and timeouts. It never follows redirects, rejects `http://` unless explicitly allowed, and relies on the telemetry buffer for retries. No new dependencies. 12 tests. Docs: webhook section in `docs/event-schema.md`.
- Demo GIF in the README.

## [0.1.0] - 2026-11-14

First tagged release: Milestone 1. Release notes: [docs/releases/v0.1.0.md](docs/releases/v0.1.0.md).

### Added
- Ledger records keep the engine's **outcome** (`TxnOutcome.APPROVED`/`DECLINED`) after completion. Schema v2 with a non-destructive v1→v2 migration (tested against the committed v1 schema). The demo history now shows declined payments correctly after they close.
- `demo-app` (2026-11-14): Sunrise Market (shop, cart, history, payment screen with protection), a Why-blocked/flagged panel, hidden Demo controls for scenarios 1–5 plus five extra failure modes, a live scrubbed telemetry panel, an offline mock integrity server, a demo policy loaded from assets, and demo-only simulated threats that can only add detections. 14 controller tests. Scenarios 1–5 verified on a Pixel 9 API 36 emulator.
- `TransactionLedger.observeRecent(limit)` (core + ledger).
- Docs: `docs/demo-script.md`, README quick start, demo screenshots.
- `posguard-integrity` (2026-11-14): obscured-touch overlay detection, screen-recording signal (API 35+), accessibility, debugger, and root/emulator signals, plus `PaymentScreenProtection` (FLAG_SECURE, obscured-touch filtering, hide overlays on API 31+) and one-line `PosGuardIntegrity.install`. Every API was verified against developer.android.com. 36 tests.
- `posguard-integrity-play` (2026-11-14): optional Play Integrity module (standard requests, backend verifier, five signals sharing one fetch) and an offline `FakeIntegrityVerdictSource`. 9 tests.
- Docs: `docs/integrity.md` and a first `docs/threat-model.md`.
- `posguard-telemetry` (2026-11-14): fail-closed scrubber (track data, Luhn-valid PANs, other 13–19 digit runs, emails, phone numbers, sensitive attribute keys; UUIDs preserved), v1 event schema with JSON encoding, `PosGuardTelemetry` listener with a bounded buffer and `TelemetrySink` extension point, plus in-memory and rotating JSON-lines file sinks. 41 tests. Docs: `docs/event-schema.md`.
- `mock-payment-engine` (2026-11-14): **DEMO — NOT A REAL PAYMENT.** Fake SoftPOS SDK with a simulated processor that deduplicates by idempotency key. It has six failure scenarios (approve, decline, timeout, network drop after submit, duplicate response, engine crash), a processor store that survives app kill, and connectivity toggles. 25 tests, including end-to-end runs of demo scenarios 1, 4, and 5 against the real ledger. Docs: `docs/mock-engine.md`.
- `posguard-ledger` (2026-11-14): Room journal with atomic, state-machine-checked transitions; duplicate-response handling; same-key resubmission only after the engine confirms `NotProcessed`; `recoverPending()` by idempotency key; WorkManager recovery worker. 27 Robolectric tests, including a crash/relaunch simulation. Docs: `docs/ledger.md`.
- `posguard-core` public API (2026-11-14): `PosGuard` with fail-safe parallel preflight, `GuardVerdict`, `ReasonCode`, `GuardPolicy` (conservative defaults, strict properties loader, relay-risk capped at WARN), `IntegritySignal`, `TransactionLedger` contract with `TxnStateMachine`, `EngineStatusSource`, and `GuardEvent` listeners. 45 unit tests.
- Docs: `docs/policy-defaults.md` and ADR-002 (idempotent ledger).
- Project setup (2026-11-13): Gradle 9.9.1 wrapper with pinned checksum, version catalog, `build-logic` convention plugins, and Apache-2.0 license.
- Milestone 1 module shells: `posguard-core`, `posguard-integrity`, `posguard-ledger`, `posguard-telemetry`, `mock-payment-engine`, `demo-app`.
- Demo app placeholder ("Sunrise Market") with the "DEMO — NOT A REAL PAYMENT" banner.
- Docs: architecture overview, ADR-001 (wrap, not replace), contributing guide.
