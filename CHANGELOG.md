# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `posguard-ledger` (2026-11-14): Room journal with atomic, state-machine-checked transitions; duplicate-response handling; same-key resubmission only after the engine confirms `NotProcessed`; `recoverPending()` by idempotency key; WorkManager recovery worker. 27 Robolectric tests, including a crash/relaunch simulation. Docs: `docs/ledger.md`.
- `posguard-core` public API (2026-11-14): `PosGuard` with fail-safe parallel preflight, `GuardVerdict`, `ReasonCode`, `GuardPolicy` (conservative defaults, strict properties loader, relay-risk capped at WARN), `IntegritySignal`, `TransactionLedger` contract with `TxnStateMachine`, `EngineStatusSource`, and `GuardEvent` listeners. 45 unit tests.
- Docs: `docs/policy-defaults.md` and ADR-002 (idempotent ledger).
- Project setup (2026-11-13): Gradle 9.9.1 wrapper with pinned checksum, version catalog, `build-logic` convention plugins, and Apache-2.0 license.
- Milestone 1 module shells: `posguard-core`, `posguard-integrity`, `posguard-ledger`, `posguard-telemetry`, `mock-payment-engine`, `demo-app`.
- Demo app placeholder ("Sunrise Market") with the "DEMO — NOT A REAL PAYMENT" banner.
- Docs: architecture overview, ADR-001 (wrap, not replace), contributing guide.
