# CLAUDE.md — Secure, Resilient Android Payment Toolkit

## 1. What we are building

An **open-source Android toolkit** that helps payment-app makers keep card payments on Android phones and POS devices **secure and reliable**: when the phone may be compromised, when the network or app fails mid-payment, and when stores must adopt new requirements such as chip and tap EBT (food-assistance) cards.

Target users of the toolkit: developers of Android POS and tap-to-phone (SoftPOS) apps, especially small payment-app makers, POS resellers, and teams maintaining older, vendor-built Android POS apps.

**The toolkit works around the certified payment engine, never replaces it.** Card reading, EMV processing, PIN handling, and encryption stay inside certified payment SDKs and hardware. We protect the merchant app that wraps them: device and screen integrity, transaction state, connectivity, telemetry, and checkout flows.

Why it matters (for README and docs, with sources):
- Cards are over three quarters of 236.6 billion U.S. noncash payments (Federal Reserve Payments Study, 2025 initial findings).
- 29.8 million of 36.2 million U.S. small businesses have no employees (SBA Office of Advocacy, 2026); phones are becoming their payment terminal (Visa reports 20M+ Tap to Phone devices, company data).
- The Secret Service reports POS and ATM skimming losses of over $1 billion a year and increasingly targets EBT skimming.
- USDA proposed (Oct 2026) that SNAP retailers accept chip and NFC EBT.
- PCI validates the payment kernel; the surrounding merchant app is not covered by that certification.

This prototype has two jobs:
1. A working **demo** for merchants, POS resellers, app makers, and engineering leaders.
2. **Dated, documented, public proof of work.** Small, frequent commits with clear messages. Keep docs current.

Working codename in code: `posguard`. Product name TBD. Package root: `dev.posguard` (change later if needed).

## 2. Scope

- **Version 1 (build now):** phone-terminal integrity, outage-safe transaction journal, secure telemetry, mock payment engine, demo app.
- **Version 2:** device-connectivity watchdog, relay-fraud risk flags, codebase assessment tool, webhook export, Maven Central release.
- **Version 3:** EBT checkout module against a simulator.
- **Not our product:** EMV kernels, card reading, PIN entry, encryption of card data, payment processing, PCI certification, physical skimmer detection, iOS.

If a feature would require touching real card data, PINs, or certified payment internals, **stop and ask**.

## 2.5 Open-core boundary

This repo is the **open-source core** (Apache-2.0). A company may later sell products built on top of it, so keep a clean boundary:

- **Public (this repo):** library modules, mock payment engine, simulators, demo app, docs, the assessment tool's base rules.
- **Private (separate repos, not built now):** a hosted dashboard that receives telemetry events, tuned detection thresholds and rules learned from real deployments, assessment-as-a-service tooling, premium integrations.
- Design every extension point so private products plug in **without forking**: pluggable telemetry sinks, policy and threshold config loaded from outside the library, interfaces for detection rules and assessment rules.
- **Never commit** tuned thresholds, customer data, private endpoints, API keys, or proprietary code to this repo. Public defaults must be safe and documented, not tuned.
- Keep public APIs stable and versioned (semantic versioning), since paid products and outside adopters will depend on them.

## 3. Tech stack

- Kotlin, Android library modules (min SDK 26; feature-gate newer APIs), Coroutines + Flow
- Libraries must not force a DI framework on consumers: plain constructor injection and builders. Hilt only in the demo app.
- Jetpack Compose (demo app), Room (transaction journal), WorkManager (sync and recovery)
- Play Integrity API (device and app-access-risk verdicts)
- Tests: JUnit, MockK, Turbine, Robolectric; instrumented tests on real devices where needed
- Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`), Maven-publishable modules
- License: Apache-2.0

Use the latest **stable** versions and pin them. **Ask before adding any dependency not listed here.**

## 4. Modules

```
/posguard-core        Public API, verdict and decision types, reason codes, policy config
/posguard-integrity   Device, app, and screen integrity signals; relay-risk flags (V2)
/posguard-ledger      Outage-safe transaction journal and recovery
/posguard-telemetry   Secure error/event reporting with card-data scrubbing; webhook (V2)
/posguard-devices     (V2) Card reader / printer / scanner connectivity watchdog
/posguard-ebt         (V3) EBT checkout flows (SNAP-eligible split, balance, chip/tap, fallback)
/posguard-assess      (V2) CLI + Gradle plugin that scans an Android POS codebase
/mock-payment-engine  Fake certified-SDK stand-in for demos and tests. Clearly labeled NOT REAL.
/ebt-simulator        (V3) Fake EBT processor responses for demos and tests
/demo-app             Fictional "Sunrise Market" food stand / small grocer (Compose)
/docs                 Architecture, threat model, decisions, demo script, benchmarks
```

## 5. Public API (sketch; refine, keep the shape)

```kotlin
// Before opening the payment screen
interface PosGuard {
    suspend fun preflight(): GuardVerdict
    val ledger: TransactionLedger
}

sealed interface GuardVerdict {
    val reasons: List<ReasonCode>
    data class Allow(...) : GuardVerdict
    data class Warn(...) : GuardVerdict          // proceed, show notice, log
    data class Block(...) : GuardVerdict         // do not open payment acceptance
}

enum class ReasonCode {
    DEVICE_INTEGRITY_FAIL, APP_INTEGRITY_FAIL, ROOT_OR_EMULATOR_SUSPECTED,
    SCREEN_OVERLAY_PRESENT, SCREEN_CAPTURE_ACTIVE, RISKY_ACCESSIBILITY_SERVICE,
    REMOTE_CONTROL_RISK, DEBUGGER_ATTACHED, RELAY_RISK_PATTERN, INTEGRITY_UNAVAILABLE
}

interface TransactionLedger {
    suspend fun begin(amountCents: Long, currency: String, tender: Tender): TxnId  // creates idempotency key
    suspend fun markSubmitted(id: TxnId)
    suspend fun markResult(id: TxnId, result: EngineResult)  // AUTHORIZED / DECLINED / UNKNOWN
    suspend fun recoverPending(): List<RecoveredTxn>          // call on app start and on reconnect
    fun observe(id: TxnId): Flow<TxnState>
}
```

Each policy (what is Warn vs Block) is configurable by the integrating app. Defaults are conservative and documented.

## 6. Integrity signals (posguard-integrity)

Every signal returns a value plus a reason code and **fails safe**: an unavailable API yields `INTEGRITY_UNAVAILABLE`, never a crash.

| Signal | Approach | Notes |
|---|---|---|
| Device / app integrity | Play Integrity verdicts, verified on a server (mock server in demo) | Needs Play Console setup by a human; provide a fake provider |
| App access risk | Play Integrity app-access-risk (apps that can capture screen, overlay, or control the device) | Same as above |
| Overlay present | Obscured-touch detection on payment views; hide non-system overlays where the API allows | **Check official Android docs per API level before implementing** |
| Screen capture / recording | Secure window flag on payment screens; platform screen-capture / recording callbacks where available | Check docs per API level |
| Risky accessibility services | Enabled accessibility services that are not on an allowlist | Never read their content |
| Root / emulator / debugger | Lightweight heuristics; treat as signals, not proof | Document false-positive risk |
| Relay risk pattern (V2) | Rule-based flags from transaction metadata the engine exposes (e.g., bursts of wallet taps, timing anomalies) | **Experimental.** Flag and hold for review; never auto-decline on this alone |

**Do not guess Android API names or behaviors.** Verify each against developer.android.com for the target API levels and note the minimum version in code comments.

## 7. Outage-safe transaction journal (posguard-ledger)

Goal: a payment is **never lost and never charged twice**, even if the app crashes or the network drops mid-payment.

- State machine: `CREATED → SUBMITTED → AUTHORIZED | DECLINED | UNKNOWN → COMPLETED | VOIDED`
- Every transaction gets an **idempotency key** before submission; resubmission without the same key is forbidden.
- `UNKNOWN` (network dropped after submit): on reconnect, query status with the idempotency key; only retry if the engine confirms nothing was processed.
- Crash recovery: `recoverPending()` runs on app start; pending `SUBMITTED`/`UNKNOWN` transactions are resolved before new payments.
- Offline acceptance (store-and-forward) is **off by default** and only enabled through explicit config with limits, because card-network and processor rules govern it.
- Journal stores only: id, idempotency key, amount, currency, tender type, state, timestamps, engine reference. **Never card numbers, PINs, track data, or CVV.**

## 8. Secure telemetry (posguard-telemetry)

- Event schema: event type, reason codes, verdict, transaction state changes, device model, OS version, app version, timestamp. Document in `docs/event-schema.md`.
- Scrubber runs on every message and field: removes Luhn-valid card-like numbers, email addresses, phone numbers, and anything flagged as sensitive. Unit-test it heavily.
- Local buffer with size limits; export via pluggable sinks. V2: webhook sink so merchants feed their existing tools.

## 9. Mock payment engine and demo app

- `mock-payment-engine` simulates a certified SoftPOS SDK: approve, decline, timeout, network drop after submit, duplicate-response, engine crash. Every screen in the demo using it shows a "DEMO — NOT A REAL PAYMENT" label.
- `demo-app`: fictional "Sunrise Market". Simple item grid, cart, "Take payment" button, transaction history, and a hidden **Demo controls** menu (long-press the logo) to trigger each scenario in one tap, plus "Reset demo."
- A **"Why blocked / flagged" panel** explains reason codes in plain English for live demos.
- Must run fully offline (mock engine and mock integrity server embedded) for in-person demos.

## 10. Demo scenarios (write `docs/demo-script.md`, 2-minute spoken script)

1. **Normal payment** → Allow, completed.
2. **Overlay app present** → payment screen refuses to open, explains why (`SCREEN_OVERLAY_PRESENT`).
3. **Screen recording active** → payment blocked (`SCREEN_CAPTURE_ACTIVE`).
4. **Network drops after submit** → `UNKNOWN` → reconnect → status resolved, **no double charge**.
5. **App crashes mid-payment** → relaunch → recovered, correct final state.
6. (V2) **Card reader disconnects** → watchdog reconnects, payment continues.
7. (V2) **Burst of suspicious wallet taps** → flagged for review (`RELAY_RISK_PATTERN`).
8. (V2) **Assessment report** on a sample legacy POS app.
9. (V3) **EBT chip/tap purchase** with SNAP-eligible items separated, balance shown, safe fallback, via simulator.

Every scenario must run from Demo controls without code changes.

## 11. Assessment tool (posguard-assess, V2)

CLI and Gradle plugin that scans an Android codebase and produces a prioritized Markdown/HTML report:
- Missing integrity or overlay protections on payment screens
- Logging that could leak card-like data
- Insecure storage of payment state
- Deprecated or outdated SDKs and APIs; old target SDK
- Crash-unsafe payment flows (no idempotency, no recovery)
Map findings to OWASP MASVS categories by name. Rank by **security risk × ease of fix** so teams can modernize incrementally. Test it only on the demo app and **public open-source Android apps**.

## 12. EBT module (posguard-ebt, V3)

Against `ebt-simulator` only: tender selection, SNAP-eligible vs non-eligible item split, balance inquiry, chip and tap with fallback, partial approvals, receipt fields. Item eligibility rules (including state purchase restrictions) come from a config file, never hardcoded. No real EBT processing.

## 13. Privacy and safety rules (non-negotiable)

- Never handle, store, log, or transmit card numbers, PINs, track data, CVV, or real EBT data.
- Never implement or imitate an EMV kernel or PIN entry.
- Never claim PCI certification or compliance. Docs may explain what PCI covers versus what this toolkit covers.
- Fictional names and data only. **No employer code, names, data, screenshots, or internal details.** Build and test on personal devices and public code.
- Secrets (Play Integrity config, keys) go in `local.properties` or untracked config; never committed.

## 14. Documentation and public evidence

Keep these current; they are part of the deliverable:
- `README.md`: problem, what it does, what it does not do, quick start (< 5 minutes), demo GIF/video link
- `docs/architecture.md`, `docs/threat-model.md` (threats → signals → limits), `docs/event-schema.md`
- `docs/decisions/ADR-xxx.md` for significant choices (e.g., `ADR-001-wrap-not-replace.md`, `ADR-002-idempotent-ledger.md`)
- `docs/benchmarks.md`: `preflight()` latency and ledger recovery time on 2–3 devices
- `docs/demo-script.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `ADOPTERS.md`, `LICENSE`
- Tagged releases with release notes

## 15. Milestones

### M1 — Core protection + outage-safe checkout (start here)
- [x] Project setup: modules, version catalog, Apache-2.0 license, CI-friendly Gradle
- [x] `posguard-core`: API, verdicts, reason codes, policy config
- [ ] `posguard-integrity`: overlay, screen capture, accessibility, root/emulator/debugger signals; Play Integrity with real + fake provider
- [ ] `posguard-ledger`: state machine, idempotency, crash and network recovery, unit tests
- [ ] `posguard-telemetry`: event schema, scrubber with tests, local sink
- [ ] `mock-payment-engine` with failure modes
- [ ] `demo-app` with Demo controls and "Why blocked" panel; offline
- [ ] Scenarios 1–5 working end to end
- [ ] README, architecture, threat model, demo script (first versions); first tagged release

### M2 — Devices, relay flags, assessment, distribution
- [ ] `posguard-devices` watchdog (with fake peripherals)
- [ ] Relay-risk flags (experimental)
- [ ] `posguard-assess` CLI + Gradle plugin; report on demo app and public apps
- [ ] Webhook sink
- [ ] Benchmarks
- [ ] Publish to Maven Central; scenarios 6–8

### M3 — EBT readiness
- [ ] `ebt-simulator` and `posguard-ebt`
- [ ] Config-driven item eligibility
- [ ] Scenario 9; docs update

## 16. How to work in this repo

- Before large changes, **propose a short plan** and wait for approval.
- Small commits, conventional messages (`feat(ledger): add idempotent retry`). Commit at least daily while active.
- Tests for every signal, state transition, and scrubber rule. Run tests before committing.
- Keep the main thread free; all integrity checks and I/O off the UI thread.
- Update the milestone checklist as tasks complete.
- If something needs a human (Play Console setup, real devices, publishing credentials), stop and list exactly what's needed.

## 17. Out of scope

- EMV kernels, PIN entry, card-data encryption, payment processing, PCI certification
- Physical skimmer detection
- Real EBT or card processor integrations
- iOS
- Commercial app shielding / obfuscation (complements, not replaces, such tools)
