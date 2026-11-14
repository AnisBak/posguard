# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
