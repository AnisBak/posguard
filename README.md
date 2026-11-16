# posguard

> Working codename. Product name TBD. **Early development: not ready for production use.**

posguard is an open-source Android toolkit that helps payment-app makers keep card payments on Android phones and POS devices **secure and reliable**. It targets three situations: the phone may be compromised, the network or app may fail mid-payment, or the store has to adopt new requirements such as chip and tap EBT cards.

**posguard wraps the certified payment engine. It never replaces it.** Card reading, EMV processing, PIN handling, and card-data encryption stay inside certified payment SDKs and hardware. posguard protects the merchant app around them:

- **Secure EBT acceptance (simulator):** a layer an existing POS app adds around its certified engine, for USDA's proposed chip-and-tap EBT requirement. It enforces chip-first entry (magstripe, the skimmable path, only after a failed chip attempt, and never key entry), records why every fallback happened, keeps each SNAP, EBT cash, and card leg outage-safe in the ledger, and keeps balances off the POS screen and out of telemetry. Supporting helpers split SNAP-eligible items from config and build receipt fields. No screens: the app keeps its own UI. PINs and card reading stay inside the certified engine.
- **Phone-terminal integrity:** device, app, and screen checks (overlays, screen capture, risky accessibility services, root, emulator, debugger) before payment acceptance opens.
- **Relay-risk flags (experimental):** rules on engine-reported tap metadata, such as a burst of phone-wallet taps, that hold an approved payment for the merchant's review. Never a decline.
- **Outage-safe transaction journal:** a payment is never lost and never charged twice, even if the app crashes or the network drops mid-payment.
- **Secure telemetry:** error and event reporting that strips card-like data before it leaves the device, with pluggable sinks including a signed HTTPS webhook.
- **Codebase assessment:** a CLI and Gradle plugin that scan an existing Android POS app for gaps around the payment engine (unprotected payment screens, card data in logs, payment state in plain storage, no idempotency or recovery, swipe-first EBT, debuggable or cleartext builds) and rank them by risk × ease of fix, mapped to OWASP MASVS. Heuristic, so findings are leads for a reviewer.
- **Mock payment engine and demo app:** "Sunrise Market", a fictional small grocer, that runs fully offline.

## Why it matters

- USDA proposed (October 2026) that SNAP retailers accept chip and contactless EBT, with magnetic-stripe fallback only after a failed chip attempt (91 FR 66958; comments due December 22, 2026). Retailers pay for their own POS equipment.
- USDA reports at least $360 million in stolen SNAP benefits replaced through mid-fiscal-year 2025. The U.S. Secret Service reports POS and ATM skimming losses of over $1 billion a year, and increasingly targets EBT skimming.
- Cards make up over three quarters of 236.6 billion U.S. noncash payments (Federal Reserve Payments Study, 2025 initial findings).
- 29.8 million of 36.2 million U.S. small businesses have no employees (SBA Office of Advocacy, 2026), and phones are becoming their payment terminal (Visa reports 20M+ Tap to Phone devices, company data).
- PCI validates the payment kernel. The merchant app around it is not covered by that certification. That app is what posguard protects.

## Demo: Sunrise Market

A fictional food stand that runs fully offline, using a mock payment engine and a mock integrity server. Every screen says **DEMO — NOT A REAL PAYMENT**. Long-press the ☀ logo for Demo controls. See the [2-minute demo script](docs/demo-script.md).

<img src="docs/images/ebt-demo.gif" width="280" alt="Scenario 9: EBT purchase with SNAP split, chip-to-tap fallback, partial approval, and receipt (3× speed)">

*Scenario 9, EBT purchase (simulator): the basket is split into SNAP and other items, the chip read fails so posguard asks for a tap, SNAP approves part of the amount, the rest goes to card, and the receipt masks the balance on screen. Driven automatically on an API 36 emulator (3× speed).*

| 9 · SNAP split from config | 9 · Chip fails → tap, partial approval | 9 · Receipt, balance kept off screen |
|---|---|---|
| <img src="docs/images/scenario9-split.png" width="200"> | <img src="docs/images/scenario9-partial.png" width="200"> | <img src="docs/images/scenario9-receipt.png" width="200"> |

<img src="docs/images/demo.gif" width="280" alt="Sunrise Market running demo scenarios 1–5 and scrubbed telemetry (5× speed)">

*Card scenarios 1–5 and scrubbed telemetry (5× speed).*

| 1 · Normal payment | 2 · Overlay blocked | 4 · Network drop: no blind retry | 5 · Recovered after a crash |
|---|---|---|---|
| <img src="docs/images/scenario1-approved.png" width="200"> | <img src="docs/images/scenario2-overlay-blocked.png" width="200"> | <img src="docs/images/scenario4-unknown.png" width="200"> | <img src="docs/images/scenario5-recovered.png" width="200"> |

| 7 · Wallet-tap burst held for review (experimental) |
|---|
| <img src="docs/images/scenario7-held.png" width="200"> |

## What posguard does not do

- No EMV kernels, card reading, PIN entry, or card-data encryption.
- No payment processing and no real card or EBT processor integrations.
- No PCI certification and no claim of PCI compliance.
- No physical skimmer detection. No iOS.

## Status

**Current focus: Milestone 4 (assessment tool, device watchdog, benchmarks, distribution).** Latest release: [v0.2.0](docs/releases/v0.2.0.md) (2026-11-15).

- **Milestone 4, in progress:** `posguard-assess` (CLI) and `posguard-assess-gradle` (plugin) are built and tested. Twelve MASVS-mapped rules, with an [example report](docs/examples/assess-legacy-pos-sample.md) on a fictional legacy POS app. Next: device watchdog, benchmarks, Maven Central.
- **Milestone 3, EBT readiness (done):** `posguard-ebt` and `ebt-simulator` are built and tested. Demo scenario 9 runs an EBT purchase with SNAP split, chip-to-tap fallback, and a partial approval, and was verified on an Android 16 (API 36) emulator. Next: real-device checks.
- **Milestone 2, done:** experimental relay-risk flags (scenario 7, verified on the emulator) and a signed webhook sink.
- **Milestone 1, done:** integrity signals, outage-safe ledger, secure telemetry, mock engine, and demo scenarios 1–5, verified on the emulator. First release: [v0.1.0](docs/releases/v0.1.0.md).

See [CHANGELOG.md](CHANGELOG.md).

Docs: [architecture](docs/architecture.md) · [threat model](docs/threat-model.md) · [integrity signals](docs/integrity.md) · [ledger](docs/ledger.md) · [event schema](docs/event-schema.md) · [policy defaults](docs/policy-defaults.md) · [relay-risk flags](docs/relay-risk.md) · [EBT readiness](docs/ebt.md) · [codebase assessment](docs/assess.md) · [decisions](docs/decisions/)

## Quick start (under 5 minutes)

Requirements: JDK 17 or newer, the Android SDK, and a device or emulator (Android 8.0+).

```sh
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :demo-app:installDebug   # installs Sunrise Market
./gradlew build                    # all modules: compile, lint, unit tests
```

Using the libraries in your app:

```kotlin
val engine = /* your adapter around the certified SDK; implements EngineStatusSource */
val ledger = PosGuardLedger.Builder(context, engine).build()
val integrity = PosGuardIntegrity.install(application)
val guard = PosGuard.Builder(ledger).signals(integrity.signals()).build()

ledger.recoverPending()                       // on start: resolve unfinished payments first
when (val verdict = guard.preflight()) {      // before opening payment acceptance
    is GuardVerdict.Block -> showWhyBlocked(verdict.reasons)
    else -> openPaymentScreen()
}
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for project rules.

## License

[Apache License 2.0](LICENSE)
