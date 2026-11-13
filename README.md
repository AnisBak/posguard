# posguard

> Working codename. Product name TBD. **Early development: not ready for production use.**

posguard is an open-source Android toolkit that helps payment-app makers keep card payments on Android phones and POS devices **secure and reliable**. It targets three situations: the phone may be compromised, the network or app may fail mid-payment, or the store has to adopt new requirements such as chip and tap EBT cards.

**posguard wraps the certified payment engine. It never replaces it.** Card reading, EMV processing, PIN handling, and card-data encryption stay inside certified payment SDKs and hardware. posguard protects the merchant app around them:

- **Phone-terminal integrity:** device, app, and screen checks (overlays, screen capture, risky accessibility services, root, emulator, debugger) before payment acceptance opens.
- **Outage-safe transaction journal:** a payment is never lost and never charged twice, even if the app crashes or the network drops mid-payment.
- **Secure telemetry:** error and event reporting that strips card-like data before it leaves the device.
- **Mock payment engine and demo app:** "Sunrise Market", a fictional small grocer, that runs fully offline.

## What posguard does not do

- No EMV kernels, card reading, PIN entry, or card-data encryption.
- No payment processing and no real card or EBT processor integrations.
- No PCI certification and no claim of PCI compliance.
- No physical skimmer detection. No iOS.

## Status

Milestone 1 is in progress. See [CHANGELOG.md](CHANGELOG.md) and [docs/architecture.md](docs/architecture.md).

## Build from source

Requirements: JDK 17 or newer, and the Android SDK.

```sh
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew build
./gradlew :demo-app:installDebug   # Sunrise Market demo (placeholder for now)
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for project rules.

## License

[Apache License 2.0](LICENSE)
