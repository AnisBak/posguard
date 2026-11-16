# Codebase assessment (`posguard-assess`)

> **Heuristic scan, not an audit.** posguard-assess reads source text. It can miss a protection set somewhere it doesn't look, and it can flag code that is safe. Treat findings as leads for a reviewer. It is not a certification and makes no PCI claim.

## Why

Most Android POS apps in use today weren't written with a compromised phone, an overlay app, or a mid-payment crash in mind, and their teams can't rewrite them. posguard-assess points at the gaps around the certified payment engine and ranks them by **risk × ease of fix**, so a team can start with the high-risk, easy fixes and modernize step by step. Each finding maps to an OWASP MASVS control group and names the posguard module that helps, if there is one.

## Run it

**CLI** (no install; from this repository):

```sh
./gradlew :posguard-assess:run --args="path/to/pos-app --out build/assess/report.md --html build/assess/report.html"
```

Paths are resolved from the repository root. `./gradlew :posguard-assess:installDist` builds a standalone `posguard-assess/build/install/posguard-assess/bin/posguard-assess` launcher.

| Option | Meaning |
|---|---|
| `<path>` | Directory to scan (all modules under it) |
| `--out <file>` | Write the Markdown report (default: print it to stdout) |
| `--html <file>` | Also write a self-contained HTML report |
| `--config <file>` | Properties file with `posguard.assess.*` settings (below) |
| `--fail-on high\|medium\|low` | Exit with 1 if any finding is at or above that risk (for CI) |
| `--list-rules` | List the rules |

Exit codes: `0` done, `1` findings at or above `--fail-on`, `2` usage or config error.

**Gradle plugin** (`posguard-assess-gradle`, plugin id `dev.posguard.assess`). It isn't published yet (that comes with the Maven Central work), so use the CLI on other projects for now. Once published:

```groovy
plugins { id 'dev.posguard.assess' }

tasks.named('assessPosguard') {
    configFile = file('posguard-assess.properties')   // optional
    failOn = 'high'                                     // optional: fail the build
}
```

`./gradlew assessPosguard` writes `build/reports/posguard/assessment.md` and `.html`. The task is cacheable and up to date when nothing changed.

## What it scans

Kotlin, Java, `AndroidManifest.xml`, other XML (layouts, network security config), Gradle build scripts, and version catalogs. It skips `build/`, `.git/`, `.gradle/`, `.idea/`, generated code, files over 1 MB, and test source sets (`test`, `androidTest`, `testFixtures`, `sharedTest`) unless `include.tests` is on. Comments are ignored.

## Rules

Run `--list-rules` for the current list. Defaults are public and not tuned to any deployment.

| Rule | Looks for | MASVS | Risk / ease | Helps |
|---|---|---|---|---|
| `payment-screen-no-flag-secure` | A payment, checkout, or tender screen (by name) whose file never sets `FLAG_SECURE` | PLATFORM | High / Easy | posguard-integrity |
| `payment-screen-no-touch-filter` | The same screens with no obscured-touch filtering in code or in their layout | PLATFORM | High / Easy | posguard-integrity |
| `no-preflight-integrity` | Payment calls, with no Play Integrity, root check, or `preflight()` anywhere | RESILIENCE | Medium / Moderate | posguard-integrity |
| `card-data-in-logs` | Logging calls that reference variables named like card data (`pan`, `cardNumber`, `track2`, `cvv`, `pinBlock`) | PRIVACY | High / Easy | posguard-telemetry |
| `payment-state-plain-prefs` | Payment state or card-like keys written to `SharedPreferences` or plain files | STORAGE | Medium / Moderate | posguard-ledger |
| `no-idempotency-key` | Payment calls with no idempotency key, or no recovery of pending payments | CODE | High / Hard | posguard-ledger |
| `ebt-magstripe-first` | EBT code that swipes before any chip or tap step | PLATFORM | High / Moderate | posguard-ebt |
| `old-target-sdk` | `targetSdk` below the `minimum` option (default 35) | CODE | Medium / Moderate | — |
| `deprecated-android-api` | `AsyncTask`, `IntentService`, `ProgressDialog`, `Handler()`, `getExternalStorageDirectory`, `LocalBroadcastManager`, SafetyNet | CODE | Low / Moderate | posguard-integrity-play (SafetyNet) |
| `manifest-debuggable` | `android:debuggable="true"`, or a debuggable release build type | CODE | High / Easy | posguard-integrity |
| `manifest-allow-backup` | `android:allowBackup="true"`, or no backup setting at all (it defaults to true) | STORAGE | Medium / Easy | — |
| `cleartext-traffic` | `usesCleartextTraffic="true"`, `cleartextTrafficPermitted="true"`, or `"http://` URLs (local development hosts excepted) | NETWORK | High / Easy | — |

**Ranking.** Risk weighs High 3, Medium 2, Low 1. Ease weighs Easy 3, Moderate 2, Hard 1. Priority is their product (9 = fix first). Ties go to higher risk, then rule id and location, so reports are stable and easy to diff.

**The `old-target-sdk` default** is 35 (Android 15). App stores raise their minimum target level over time, so set the `minimum` option to your distribution channel's current requirement.

## Config

```properties
posguard.assess.include.tests=false
posguard.assess.rule.deprecated-android-api.enabled=false
posguard.assess.rule.manifest-allow-backup.risk=high
posguard.assess.rule.no-idempotency-key.ease=moderate
posguard.assess.rule.old-target-sdk.minimum=34
```

Unknown keys, unknown rule ids, unknown options, and bad values are rejected (exit code 2), so a typo never silently turns a check off.

## Privacy of the report

Reports contain file paths, line numbers, rule text, and identifier names (for example, "Logging call references `cardNumber`"). They never quote string literals or values from the scanned code, so a card number hard-coded in a log line doesn't end up in the report. The title shows only the scanned directory's name, never an absolute path. There are no timestamps, so the same code gives the same report.

## Adding rules (no fork needed)

Implement `dev.posguard.assess.AssessRule`, list the class in `META-INF/services/dev.posguard.assess.AssessRule`, and put the jar on the classpath. For the CLI, after `installDist`: `java -cp "posguard-assess/build/install/posguard-assess/lib/*:my-rules.jar" dev.posguard.assess.cli.CliKt <path>` (use `;` instead of `:` on Windows). For the Gradle plugin, add the jar to the build script classpath. `RuleRegistry.discover()` loads it after the built-in rules. A rule declares its MASVS group, default risk and ease, explanation, fix, and any `options`, and it returns `RuleHit`s. The same config keys (`posguard.assess.rule.<id>.*`) apply to plugged-in rules.

## Example

[docs/examples/assess-legacy-pos-sample.md](examples/assess-legacy-pos-sample.md) is the report for [`samples/legacy-pos-sample`](../samples/legacy-pos-sample), a fictional, deliberately insecure "Corner Deli" POS app that triggers every rule (17 findings). A test keeps the two in sync.

The demo app (`demo-app/`) and posguard's own modules report **no findings**, because they provide the protections the rules look for. (Scanning the repository root also scans the sample, so its findings show up there.)

## Limits

- **Text heuristics, not code analysis.** There is no type resolution or data flow. A `FLAG_SECURE` set in a base activity, or a logging wrapper with an unusual name, won't be seen.
- **Payment screens are found by name** (`*Payment*`, `*Checkout*`, `*Tender*`, `*Charge*`, `Pay*` + `Activity`/`Fragment`/`Screen`/`Dialog`). Screens named otherwise are missed.
- **Project-wide rules** (`no-preflight-integrity`, `no-idempotency-key`) accept the protection anywhere in the scanned tree. They can't check that it runs before each payment.
- **Not yet tested on public open-source apps.** That is planned, and results will be published only after review.
