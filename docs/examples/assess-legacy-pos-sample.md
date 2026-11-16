# posguard assessment: legacy-pos-sample

> Heuristic scan by posguard-assess. Findings are leads to check, not proof: rules read source text, so they can miss protections set elsewhere and flag code that is safe. No values from the scanned code are quoted.

Files scanned: 8 · Rules run: 12 · Findings: 17 (High 9, Medium 6, Low 2)

## Fix first

Ranked by risk × ease of fix (9 = high risk, easy fix).

| # | Priority | Risk | Ease | MASVS | Finding | Location |
|---|---|---|---|---|---|---|
| 1 | 9 | High | Easy | MASVS-PRIVACY | Card data or PIN may be written to logs | `app/src/main/java/com/example/cornerdeli/pos/PaymentActivity.java:26` |
| 2 | 9 | High | Easy | MASVS-NETWORK | Cleartext HTTP traffic | `app/build.gradle:5` |
| 3 | 9 | High | Easy | MASVS-NETWORK | Cleartext HTTP traffic | `app/src/main/AndroidManifest.xml:9` |
| 4 | 9 | High | Easy | MASVS-NETWORK | Cleartext HTTP traffic | `app/src/main/java/com/example/cornerdeli/pos/SyncTask.java:10` |
| 5 | 9 | High | Easy | MASVS-CODE | Debuggable build | `app/build.gradle:20` |
| 6 | 9 | High | Easy | MASVS-PLATFORM | Payment screen can be captured or recorded | `app/src/main/java/com/example/cornerdeli/pos/PaymentActivity.java:9` |
| 7 | 9 | High | Easy | MASVS-PLATFORM | Payment screen accepts touches through overlays | `app/src/main/java/com/example/cornerdeli/pos/PaymentActivity.java:9` |
| 8 | 6 | High | Moderate | MASVS-PLATFORM | EBT checkout may swipe before trying the chip | `app/src/main/java/com/example/cornerdeli/pos/EbtTender.java:13` |
| 9 | 6 | Medium | Easy | MASVS-STORAGE | App data can be backed up | `app/src/main/AndroidManifest.xml:8` |
| 10 | 4 | Medium | Moderate | MASVS-RESILIENCE | No integrity check before taking payments | `app/src/main/java/com/example/cornerdeli/pos/PaymentActivity.java:24` |
| 11 | 4 | Medium | Moderate | MASVS-CODE | Old targetSdk | `app/build.gradle:14` |
| 12 | 4 | Medium | Moderate | MASVS-STORAGE | Payment state stored in plain preferences or files | `app/src/main/java/com/example/cornerdeli/pos/TransactionStore.java:18` |
| 13 | 4 | Medium | Moderate | MASVS-STORAGE | Payment state stored in plain preferences or files | `app/src/main/java/com/example/cornerdeli/pos/TransactionStore.java:19` |
| 14 | 4 | Medium | Moderate | MASVS-STORAGE | Payment state stored in plain preferences or files | `app/src/main/java/com/example/cornerdeli/pos/TransactionStore.java:23` |
| 15 | 3 | High | Hard | MASVS-CODE | Payment flow isn't crash- or outage-safe | `app/src/main/java/com/example/cornerdeli/pos/PaymentActivity.java:24` |
| 16 | 2 | Low | Moderate | MASVS-CODE | Deprecated Android API in use | `app/src/main/java/com/example/cornerdeli/pos/SyncTask.java:9` |
| 17 | 2 | Low | Moderate | MASVS-CODE | Deprecated Android API in use | `app/src/main/java/com/example/cornerdeli/pos/SyncTask.java:12` |

## Details

### Card data or PIN may be written to logs (`card-data-in-logs`)

Risk: High · Ease of fix: Easy · MASVS-PRIVACY · posguard module: `posguard-telemetry`

**Why it matters.** Logs end up in logcat, crash reports, and support bundles. A card number, track data, CVV, or PIN in a log is cardholder data leaving the protected payment engine. Heuristic: matched by variable names such as pan, cardNumber, track2, cvv, or pinBlock inside a logging call.

**How to fix.** Remove the value from the log call. Log a transaction id instead. Route app events through a scrubbing reporter such as posguard-telemetry, which drops card-like numbers before anything is stored or sent.

- `app/src/main/java/com/example/cornerdeli/pos/PaymentActivity.java:26`: Logging call references `cardNumber`.

### Cleartext HTTP traffic (`cleartext-traffic`)

Risk: High · Ease of fix: Easy · MASVS-NETWORK

**Why it matters.** Plain HTTP can be read and changed by anyone on the store's Wi-Fi. Payment status, receipts, configuration, and update checks sent this way can be faked.

**How to fix.** Use HTTPS everywhere, remove android:usesCleartextTraffic="true", and don't permit cleartext in the network security config (except for local development hosts).

- `app/build.gradle:5`: Plain http:// URL.
- `app/src/main/AndroidManifest.xml:9`: android:usesCleartextTraffic is set to true.
- `app/src/main/java/com/example/cornerdeli/pos/SyncTask.java:10`: Plain http:// URL.

### Debuggable build (`manifest-debuggable`)

Risk: High · Ease of fix: Easy · MASVS-CODE · posguard module: `posguard-integrity`

**Why it matters.** A debuggable payment app lets anyone with a USB cable attach a debugger, read memory, and change behavior at runtime, bypassing the app's own checks.

**How to fix.** Remove android:debuggable from the manifest (the build type sets it) and make sure release builds aren't debuggable. posguard-integrity also reports an attached debugger to preflight.

- `app/build.gradle:20`: The release build type is debuggable.

### Payment screen can be captured or recorded (`payment-screen-no-flag-secure`)

Risk: High · Ease of fix: Easy · MASVS-PLATFORM · posguard module: `posguard-integrity`

**Why it matters.** Without the secure window flag, screenshots, screen recording, casting, and the recent-apps thumbnail can capture the checkout, including amounts and anything the engine draws in the app's window. Heuristic: a screen is matched by name, and the flag may be set in a base class this rule can't see.

**How to fix.** Set WindowManager.LayoutParams.FLAG_SECURE while the payment screen is shown (all API levels), or call PaymentScreenProtection.protect(activity) from posguard-integrity.

- `app/src/main/java/com/example/cornerdeli/pos/PaymentActivity.java:9`: PaymentActivity doesn't set FLAG_SECURE in this file.

### Payment screen accepts touches through overlays (`payment-screen-no-touch-filter`)

Risk: High · Ease of fix: Easy · MASVS-PLATFORM · posguard module: `posguard-integrity`

**Why it matters.** An overlay app can draw over the checkout and trick the user into tapping (tapjacking), for example to confirm an amount they can't see. Heuristic: a screen is matched by name, and filtering may live in a base class.

**How to fix.** Set filterTouchesWhenObscured on the payment views (or android:filterTouchesWhenObscured="true" in the layout), and treat MotionEvent.FLAG_WINDOW_IS_OBSCURED as a warning sign. PaymentScreenProtection in posguard-integrity does this and reports overlays to preflight.

- `app/src/main/java/com/example/cornerdeli/pos/PaymentActivity.java:9`: PaymentActivity doesn't filter touches from obscured windows.

### EBT checkout may swipe before trying the chip (`ebt-magstripe-first`)

Risk: High · Ease of fix: Moderate · MASVS-PLATFORM · posguard module: `posguard-ebt`

**Why it matters.** Magnetic stripes are what skimmers copy. USDA's proposed SNAP EBT card security rule (91 FR 66958) would not permit a magstripe transaction until at least one chip attempt has failed. A checkout that swipes first keeps the skimmable path open. Heuristic: in files that mention EBT, a magstripe or swipe action (a call, member access, or entry in a list) appears before any chip or tap reference. String text is ignored.

**How to fix.** Prompt chip (insert or tap) first and offer magstripe only after a failed chip attempt or when the card or reader can't use the chip. posguard-ebt's EbtEntryFlow enforces this order and records why each fallback happened.

- `app/src/main/java/com/example/cornerdeli/pos/EbtTender.java:13`: Magstripe or swipe appears before any chip or tap step in this EBT code.

### App data can be backed up (`manifest-allow-backup`)

Risk: Medium · Ease of fix: Easy · MASVS-STORAGE

**Why it matters.** android:allowBackup defaults to true. Payment state, tokens, and settings in app storage can then be copied off the device in backups and restored onto another device.

**How to fix.** Set android:allowBackup="false", or keep backup on and exclude payment data with backup rules (android:fullBackupContent, and android:dataExtractionRules on API 31+).

- `app/src/main/AndroidManifest.xml:8`: android:allowBackup is set to true.

### No integrity check before taking payments (`no-preflight-integrity`)

Risk: Medium · Ease of fix: Moderate · MASVS-RESILIENCE · posguard module: `posguard-integrity`

**Why it matters.** On a rooted, tampered, or remotely controlled phone, an attacker can watch or drive the checkout. The certified engine protects card data, but the merchant app around it decides whether to open payment acceptance at all. Heuristic: no Play Integrity, root check, or posguard preflight call was found anywhere.

**How to fix.** Check device and app integrity before opening the payment screen, and fail safe when the check is unavailable. posguard's preflight() combines Play Integrity, overlay, screen-capture, accessibility, and root signals into Allow, Warn, or Block.

- `app/src/main/java/com/example/cornerdeli/pos/PaymentActivity.java:24`: Payments start here, and no integrity check was found in the project.

### Old targetSdk (`old-target-sdk`)

Risk: Medium · Ease of fix: Moderate · MASVS-CODE

**Why it matters.** Apps targeting old API levels miss newer platform protections (for example, stricter defaults for exported components, cleartext traffic, and background starts), and app stores stop accepting updates below their current target level.

**How to fix.** Raise targetSdk step by step, reading each version's behavior changes on developer.android.com, and retest the payment flow on each step.

- `app/build.gradle:14`: targetSdk 30 is below 35.

### Payment state stored in plain preferences or files (`payment-state-plain-prefs`)

Risk: Medium · Ease of fix: Moderate · MASVS-STORAGE · posguard module: `posguard-ledger`

**Why it matters.** SharedPreferences and app files are plain text on disk, can be included in backups, and aren't a crash-safe journal: a half-written update can lose a payment or cause a double charge. Card data must never be stored by the app at all. Heuristic: matched by key names such as txn, payment, pending, amount, or card.

**How to fix.** Keep transaction state in a transactional store with explicit states and idempotency keys (posguard-ledger uses Room). Never store card numbers, track data, CVV, or PINs. Turn off backup for payment data.

- `app/src/main/java/com/example/cornerdeli/pos/TransactionStore.java:18`: Writes payment state to SharedPreferences.
- `app/src/main/java/com/example/cornerdeli/pos/TransactionStore.java:19`: Writes payment state to SharedPreferences.
- `app/src/main/java/com/example/cornerdeli/pos/TransactionStore.java:23`: Writes payment state to SharedPreferences.

### Payment flow isn't crash- or outage-safe (`no-idempotency-key`)

Risk: High · Ease of fix: Hard · MASVS-CODE · posguard module: `posguard-ledger`

**Why it matters.** If the network drops or the app crashes after a payment is submitted, the app can't tell whether the customer was charged. Retrying without the same idempotency key can charge twice, and giving up can lose a sale. Heuristic: matched by names such as idempotency, clientTransactionId, recoverPending, or queryStatus.

**How to fix.** Give every payment an idempotency key before submitting it, journal its state, and on app start and reconnect ask the engine about pending payments by that key before retrying. posguard-ledger implements this state machine.

- `app/src/main/java/com/example/cornerdeli/pos/PaymentActivity.java:24`: Payments start here, and no idempotency key was found in the project.

### Deprecated Android API in use (`deprecated-android-api`)

Risk: Low · Ease of fix: Moderate · MASVS-CODE

**Why it matters.** Deprecated APIs get no fixes and can change or stop working on newer Android versions, which tends to surface as crashes or hangs during checkout. SafetyNet Attestation in particular is replaced by Play Integrity.

**How to fix.** Replace each API with its documented successor (see its reference page on developer.android.com). For SafetyNet, move to the Play Integrity API; posguard-integrity-play wraps it.

- `app/src/main/java/com/example/cornerdeli/pos/SyncTask.java:9`: AsyncTask: deprecated in API 30; use coroutines or java.util.concurrent.
- `app/src/main/java/com/example/cornerdeli/pos/SyncTask.java:12`: ProgressDialog: deprecated in API 26; use an in-layout progress indicator.
