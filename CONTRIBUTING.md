# Contributing to posguard

Thanks for your interest. posguard is early-stage. Issues and small, focused pull requests are welcome.

## Ground rules (non-negotiable)

- **No real payment data.** Never add code, tests, fixtures, or logs that handle, store, or transmit card numbers, PINs, track data, CVV, or real EBT data. Use clearly fake values. For card-like test strings, use documented test PANs only inside scrubber tests.
- **No EMV kernels or PIN entry.** posguard wraps certified payment engines and never re-implements them. See [ADR-001](docs/decisions/ADR-001-wrap-not-replace.md).
- **No PCI claims.** Docs may explain what PCI covers and what posguard covers. They must never claim certification or compliance.
- **No secrets.** Play Integrity config, keys, and endpoints belong in `local.properties` or other untracked files.
- **No tuned thresholds or customer data.** Public defaults must be safe, conservative, and documented.

## Development setup

Requirements: JDK 17 or newer, and the Android SDK with platform 37 (AGP downloads it automatically if your SDK licenses are accepted).

```sh
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew build          # compile, lint, and unit-test every module
./gradlew :demo-app:installDebug
```

## Project layout

- Shared Gradle configuration lives in `build-logic/` as convention plugins (`posguard.android.library`, `posguard.jvm.library`, `posguard.publish`).
- Dependency versions are pinned in `gradle/libs.versions.toml`. Open an issue before proposing a new dependency.
- Library modules use Kotlin **explicit API mode**: every public declaration needs a visibility modifier and an explicit type.

## Pull requests

- Keep commits small and use [Conventional Commits](https://www.conventionalcommits.org/) (`feat(ledger): add idempotent retry`, `fix(integrity): ...`, `docs: ...`).
- Add tests for every signal, ledger state transition, and scrubber rule.
- Run `./gradlew build` before pushing.
- Keep integrity checks and I/O off the main thread.
- Update `CHANGELOG.md` and the relevant docs.
- Record significant design choices as an ADR in `docs/decisions/`.

## License

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
