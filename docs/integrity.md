# Integrity signals (`posguard-integrity`, `posguard-integrity-play`)

Every signal implements `IntegritySignal` and **fails safe**: when it can't run, it returns `Unavailable` (`INTEGRITY_UNAVAILABLE`) and never crashes. What each reason *does* (allow, warn, or block) is set by `GuardPolicy` ([policy-defaults.md](policy-defaults.md)).

## Setup

```kotlin
// Application.onCreate (main thread)
val integrity = PosGuardIntegrity.install(this, IntegrityConfig.fromProperties(config))
val play = PlayIntegritySignals.create(playSource)        // optional, Google Play devices only
val guard = PosGuard.Builder(ledger).signals(integrity.signals() + play).build()

// When the payment screen opens or closes (main thread)
PaymentScreenProtection.protect(activity)
PaymentScreenProtection.unprotect(activity)
```

To let `protect()` hide other apps' overlays on Android 12+, declare this in **your app's** manifest:

```xml
<uses-permission android:name="android.permission.HIDE_OVERLAY_WINDOWS" />
```

## Signals

| Signal id | Reason | How | Limits |
|---|---|---|---|
| `screen.overlay` | SCREEN_OVERLAY_PRESENT | Watches touches for `FLAG_WINDOW_IS_OBSCURED` (API 9) and `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` (API 29) through a wrapped `Window.Callback`. Raised for 30 s after an obscured touch. | Android has **no API to ask whether an overlay is present**. It needs a touch while the overlay covers the window (usually the "Take payment" tap). With no touch, it reports Clear. |
| `screen.recording` | SCREEN_CAPTURE_ACTIVE | `WindowManager.addScreenRecordingCallback` (API 35, `DETECT_SCREEN_RECORDING`). | **Unavailable below API 35.** It only reports recordings that show this app's windows. |
| `accessibility.services` | RISKY_ACCESSIBILITY_SERVICE | Enabled services (`getEnabledAccessibilityServiceList`, API 14) not on the allowlist that can retrieve window content or perform gestures (`getCapabilities`, API 18). | Reads metadata only, never content. `isAccessibilityTool` (API 31) is self-declared and **not trusted**. |
| `app.debugger` | DEBUGGER_ATTACHED | `Debug.isDebuggerConnected()` (API 1). | `Settings.Global.ADB_ENABLED` and `DEVELOPMENT_SETTINGS_ENABLED` are **not used**: they always return 0 for third-party apps. |
| `device.root_emulator` | ROOT_OR_EMULATOR_SUSPECTED | `su`/Magisk paths, `test-keys`, emulator fingerprints. | Heuristics: false positives on custom ROMs and developer devices, and easy to evade. |
| `play.device` | DEVICE_INTEGRITY_FAIL | No `MEETS_DEVICE_INTEGRITY`/`MEETS_STRONG_INTEGRITY` label. | Needs Google Play services, Play Console, and a backend. |
| `play.app` | APP_INTEGRITY_FAIL | `appRecognitionVerdict` = `UNRECOGNIZED_VERSION` (`UNEVALUATED` is unavailable). | Same. |
| `play.access.capturing` / `.overlays` / `.controlling` | SCREEN_CAPTURE_ACTIVE / SCREEN_OVERLAY_PRESENT / REMOTE_CONTROL_RISK | `appAccessRiskVerdict.appsDetected` `KNOWN_*`/`UNKNOWN_*`. | Play Console opt-in. Use `includeAppAccessRisk = false` if you haven't opted in. |

All Play signals share **one** verdict fetch per preflight (cached 10 s). If the fetch fails, every Play signal reports unavailable.

## Payment screen protection

`PaymentScreenProtection.protect()`:
- `FLAG_SECURE` (API 1): blocks screenshots and display on non-secure displays.
- `filterTouchesWhenObscured` on the decor view: drops touches that pass through an overlay.
- `Window.setHideOverlayWindows(true)` (API 31): hides non-system overlays, but only if your app was granted `HIDE_OVERLAY_WINDOWS`.

## Verified API reference

Checked against developer.android.com on 2026-11-14:

| API | Min API | Permission |
|---|---|---|
| `MotionEvent.FLAG_WINDOW_IS_OBSCURED` | 9 | none |
| `MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED` | 29 | none |
| `Window.setHideOverlayWindows` | 31 | `HIDE_OVERLAY_WINDOWS` (protection level not stated in the reference; **verify on device**) |
| `WindowManager.LayoutParams.FLAG_SECURE` | 1 | none |
| `WindowManager.addScreenRecordingCallback` | 35 | `DETECT_SCREEN_RECORDING` (normal) |
| `AccessibilityManager.getEnabledAccessibilityServiceList` | 14 | none documented |
| `AccessibilityServiceInfo.getCapabilities` / `isAccessibilityTool` | 18 / 31 | none |
| `Debug.isDebuggerConnected` | 1 | none |
| Play Integrity standard requests (`com.google.android.play:integrity:1.6.0`, library minSdk 23) | — | — |

## Play Integrity setup (needs a human)

1. In Play Console, link a Google Cloud project to the app and enable the Play Integrity API. Note the **cloud project number**.
2. Opt in to **app access risk** (and Play Protect, optionally) in Play Console.
3. Create a **backend** with a service account (`playintegrity` scope) that calls `decodeIntegrityToken`. It must check `requestDetails.requestPackageName`, `requestHash`, and `timestampMillis`, then map the payload to `IntegrityVerdict`.
4. Implement `IntegrityTokenVerifier` to call that backend. Keep the project number in `local.properties` or remote config. **Never commit it.**
5. Call `PlayIntegrityVerdictSource.warmUp()` at app start (preparation is limited to 5 per minute).

For demos and tests, `FakeIntegrityVerdictSource` is the embedded mock integrity server, and it works offline.

## Real-device checks still to do

- Obscured-touch detection with a real overlay app on API 29+ and 31+, and hide-overlay behavior on 31+.
- Screen-recording callback on an API 35 device, including how it interacts with `FLAG_SECURE`.
- The `HIDE_OVERLAY_WINDOWS` protection level.
