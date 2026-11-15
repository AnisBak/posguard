# Threat model (v1, 2026-11-14)

**Scope:** the merchant app that wraps a certified payment engine on an Android phone or POS device. Card reading, EMV, PIN entry, and card-data encryption are inside the certified engine and **out of scope** ([ADR-001](decisions/ADR-001-wrap-not-replace.md)). posguard makes no PCI claim.

**Assets:** checkout integrity (what the customer sees and approves), transaction state (never lost, never doubled), telemetry confidentiality (no card or personal data leaks), and merchant trust.

## Threats → signals → limits

| # | Threat | posguard response | Limits |
|---|---|---|---|
| T1 | **Overlay / tapjacking**: a malicious app draws over checkout to fake amounts or capture taps | `screen.overlay` (obscured touches), `play.access.overlays`, `PaymentScreenProtection` (hide overlays on API 31+, filter obscured touches). Default: BLOCK | No platform API reports overlays directly. Detection needs a touch. Hiding overlays needs API 31 and an app-declared permission. |
| T2 | **Screen capture / recording** of the checkout | `FLAG_SECURE` on payment screens (all versions), `screen.recording` (API 35+), `play.access.capturing`. Default: BLOCK | Below API 35, recording can't be detected (it's reported as unavailable and WARNs by default). |
| T3 | **Accessibility-service abuse**: reading the screen or performing gestures | `accessibility.services` (non-allowlisted services with content or gesture capabilities). Default: WARN | Defaults to WARN so assistive-tech users aren't locked out. The allowlist is package-based. |
| T4 | **Remote control** of the device during checkout | `play.access.controlling`, plus T3. Default: BLOCK | Needs Play Integrity app access risk. There is no on-device API. |
| T5 | **Compromised device**: root, emulator, tampered OS | `play.device` (strong), `device.root_emulator` (heuristic). Defaults: BLOCK / WARN | Heuristics are evadable and produce false positives. Play Integrity needs Google Play services. |
| T6 | **Tampered or repackaged app** | `play.app` (`UNRECOGNIZED_VERSION`). Default: BLOCK | Needs Play distribution and a backend. Complements, doesn't replace, app shielding. |
| T7 | **Runtime instrumentation**: debugger attached | `app.debugger`. Default: BLOCK | Doesn't detect hooking frameworks. |
| T8 | **Network drop or crash mid-payment** leading to a lost sale or double charge | Ledger: idempotency key, state machine, `recoverPending()` by key ([ADR-002](decisions/ADR-002-idempotent-ledger.md)) | Depends on the engine supporting status lookup by key. |
| T9 | **Card data leaking through logs or telemetry** | Telemetry scrubber (PAN, track, email, phone, sensitive keys; fails closed). Schema excludes amounts and engine references ([event-schema.md](event-schema.md)) | Pattern-based. Redaction errs toward false positives. Apps must still not log card data. |
| T10 | **Security checks blocked or failing** (offline, API missing, signal crash) | Every signal fails safe to `INTEGRITY_UNAVAILABLE`. Default: WARN, configurable to BLOCK (fail closed) | WARN keeps merchants selling during outages. Choose BLOCK for higher risk tolerance. |
| T11 | **Relay attacks** on contactless taps | Experimental `RelayRiskMonitor` ([relay-risk.md](relay-risk.md)): rules on engine-reported tap metadata (wallet-tap bursts; optional near-no-PIN-limit bursts and slow reads). Flagged payments are held for review, and preflight WARNs until reviewed. Capped at WARN | Sees only metadata the engine exposes, on this device. False positives are expected. Never declines. Real relay defenses live in the card, kernel, and network. |

## Out of scope

Physical skimmers, EMV kernel or PIN attacks, card-data encryption, processor fraud scoring, iOS, and commercial app shielding or obfuscation.
