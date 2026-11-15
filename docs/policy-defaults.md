# Policy defaults

`GuardPolicy.Defaults` maps every `ReasonCode` to an action. The integrating app owns its policy and can change any value. These public defaults are **conservative and documented, not tuned**: they contain no thresholds learned from real deployments.

| Reason code | Default | Why |
|---|---|---|
| `DEVICE_INTEGRITY_FAIL` | BLOCK | The device failed the integrity verdict, so it can't be trusted to show or protect a checkout. |
| `APP_INTEGRITY_FAIL` | BLOCK | The app binary may be tampered with or repackaged. |
| `SCREEN_OVERLAY_PRESENT` | BLOCK | An overlay can hide or fake amounts and prompts (tapjacking). |
| `SCREEN_CAPTURE_ACTIVE` | BLOCK | Payment screens must not be recorded or streamed. |
| `REMOTE_CONTROL_RISK` | BLOCK | Someone else may be viewing or driving the device. |
| `DEBUGGER_ATTACHED` | BLOCK | Not expected on a production payment device. Apps may relax this in debug builds. |
| `ROOT_OR_EMULATOR_SUSPECTED` | WARN | Lightweight heuristics with real false-positive risk (custom ROMs, developer devices). It's a signal, not proof. |
| `RISKY_ACCESSIBILITY_SERVICE` | WARN | Accessibility malware is a real threat, but blind and low-vision merchants rely on screen readers. Blocking by default could lock out legitimate users. Common assistive services will be on the default allowlist. |
| `INTEGRITY_UNAVAILABLE` | WARN | A check couldn't run (offline, timeout, unsupported API). This fails safe without crashing and keeps merchants with poor connectivity selling, while logging the gap. Apps with stricter risk tolerance can set BLOCK (fail closed). |
| `RELAY_RISK_PATTERN` | WARN, **capped** | Experimental. It flags payments and holds them for review, and must never decline a payment on its own. `GuardPolicy` rejects BLOCK for this reason. Raised by `RelayRiskMonitor.signal` while a flagged payment awaits review. Rule thresholds are in [relay-risk.md](relay-risk.md). |

## How the verdict is chosen

Every reason that is detected is collected. The strictest action among them wins: `BLOCK` > `WARN` > `ALLOW`. Reasons mapped to `ALLOW` still appear in `GuardVerdict.reasons`, so they can be logged.

## Overriding

In code:

```kotlin
val policy = GuardPolicy.builder()
    .set(ReasonCode.ROOT_OR_EMULATOR_SUSPECTED, GuardAction.BLOCK)
    .build()
```

From a properties file supplied by the app (or a private config service):

```properties
posguard.policy.ROOT_OR_EMULATOR_SUSPECTED=BLOCK
posguard.policy.INTEGRITY_UNAVAILABLE=BLOCK
```

`GuardPolicy.fromProperties` is strict. An unknown reason code, an unknown action, or a value above a reason's cap throws `IllegalArgumentException`, so a typo can't silently weaken protection. Keys without the `posguard.policy.` prefix are ignored.
