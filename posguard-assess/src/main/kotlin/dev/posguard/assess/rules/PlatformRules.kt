package dev.posguard.assess.rules

import dev.posguard.assess.AssessRule
import dev.posguard.assess.Ease
import dev.posguard.assess.MasvsCategory
import dev.posguard.assess.Risk
import dev.posguard.assess.RuleContext
import dev.posguard.assess.RuleHit
import dev.posguard.assess.SourceFile
import dev.posguard.assess.SourceKind

/** targetSdk below a configurable minimum. */
internal object OldTargetSdk : AssessRule {
    override val id = "old-target-sdk"
    override val title = "Old targetSdk"
    override val masvs = MasvsCategory.CODE
    override val defaultRisk = Risk.MEDIUM
    override val defaultEase = Ease.MODERATE
    override val whyItMatters =
        "Apps targeting old API levels miss newer platform protections (for example, stricter defaults for exported " +
            "components, cleartext traffic, and background starts), and app stores stop accepting updates below their " +
            "current target level."
    override val howToFix =
        "Raise targetSdk step by step, reading each version's behavior changes on developer.android.com, and retest " +
            "the payment flow on each step."

    /** Public default, not tuned. Set the option to your distribution channel's current requirement. */
    const val DEFAULT_MINIMUM: Int = 35
    private const val MINIMUM = "minimum"
    override val options = mapOf(MINIMUM to "Lowest acceptable targetSdk (default $DEFAULT_MINIMUM).")

    private val GRADLE = Regex("""\btargetSdk(?:Version)?\b\s*(?:=\s*|\(\s*|\s+)"?(\d{1,3})""")
    private val MANIFEST = Regex("""android:targetSdkVersion\s*=\s*"(\d{1,3})"""")

    override fun check(context: RuleContext): List<RuleHit> {
        val minimum = context.options[MINIMUM]?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..99 }
                ?: throw IllegalArgumentException("Option $id.$MINIMUM must be a number from 1 to 99.")
        } ?: DEFAULT_MINIMUM
        val gradle = context.project.matches(GRADLE, context.project.filesOf(SourceKind.GRADLE)).map { it to GRADLE }
        val manifest = context.project.matches(MANIFEST, context.project.filesOf(SourceKind.MANIFEST)).map { it to MANIFEST }
        return (gradle + manifest).mapNotNull { (match, regex) ->
            val (file, line) = match
            val value = regex.find(file.code[line - 1])!!.groupValues[1].toInt()
            if (value < minimum) RuleHit(file.path, line, "targetSdk $value is below $minimum.") else null
        }
    }
}

/** Uses of Android APIs that are deprecated. */
internal object DeprecatedAndroidApi : AssessRule {
    override val id = "deprecated-android-api"
    override val title = "Deprecated Android API in use"
    override val masvs = MasvsCategory.CODE
    override val defaultRisk = Risk.LOW
    override val defaultEase = Ease.MODERATE
    override val whyItMatters =
        "Deprecated APIs get no fixes and can change or stop working on newer Android versions, which tends to surface " +
            "as crashes or hangs during checkout. SafetyNet Attestation in particular is replaced by Play Integrity."
    override val howToFix =
        "Replace each API with its documented successor (see its reference page on developer.android.com). For SafetyNet, " +
            "move to the Play Integrity API; posguard-integrity-play wraps it."

    /** Pattern → (name, note). Each entry's deprecation is listed in the Android reference docs. */
    private val APIS: List<Triple<Regex, String, String>> = listOf(
        Triple(Regex("""\bAsyncTask\b"""), "AsyncTask", "deprecated in API 30; use coroutines or java.util.concurrent"),
        Triple(Regex("""\bIntentService\b"""), "IntentService", "deprecated in API 30; use WorkManager"),
        Triple(Regex("""\bProgressDialog\b"""), "ProgressDialog", "deprecated in API 26; use an in-layout progress indicator"),
        Triple(Regex("""\bnew\s+Handler\s*\(\s*\)|\bHandler\s*\(\s*\)"""), "Handler()", "no-arg constructor deprecated in API 30; pass a Looper"),
        Triple(Regex("""\bgetExternalStorageDirectory\b|\.externalStorageDirectory\b"""), "getExternalStorageDirectory", "deprecated in API 29; use app-specific storage"),
        Triple(Regex("""\bLocalBroadcastManager\b"""), "LocalBroadcastManager", "deprecated; use an observable such as Flow"),
        Triple(Regex("""\bSafetyNet\b|\bsafetynet\b"""), "SafetyNet", "deprecated; use the Play Integrity API"),
    )

    override fun check(context: RuleContext): List<RuleHit> = context.project.codeFiles.flatMap { file ->
        APIS.mapNotNull { (regex, name, note) ->
            val index = file.code.indexOfFirst { line ->
                !line.trimStart().startsWith("import ") && regex.containsMatchIn(Heuristics.withoutStrings(line))
            }
            if (index < 0) null else RuleHit(file.path, index + 1, "$name: $note.")
        }
    }
}

/** `android:debuggable="true"` in a manifest, or debuggable in a release build type. */
internal object ManifestDebuggable : AssessRule {
    override val id = "manifest-debuggable"
    override val title = "Debuggable build"
    override val masvs = MasvsCategory.CODE
    override val defaultRisk = Risk.HIGH
    override val defaultEase = Ease.EASY
    override val posguardModule = "posguard-integrity"
    override val whyItMatters =
        "A debuggable payment app lets anyone with a USB cable attach a debugger, read memory, and change behavior at " +
            "runtime, bypassing the app's own checks."
    override val howToFix =
        "Remove android:debuggable from the manifest (the build type sets it) and make sure release builds aren't " +
            "debuggable. posguard-integrity also reports an attached debugger to preflight."

    private val MANIFEST = Regex("""android:debuggable\s*=\s*"true"""")
    private val RELEASE_OPEN = Regex("""\brelease\b[^{]*\{|"release"\s*\)\s*\{""")
    private val DEBUGGABLE = Regex("""\b(?:isDebuggable\s*=\s*true|debuggable\s*(?:=\s*)?true)\b""")

    override fun check(context: RuleContext): List<RuleHit> {
        val manifest = context.project.matches(MANIFEST, context.project.filesOf(SourceKind.MANIFEST))
            .map { (file, line) -> RuleHit(file.path, line, "android:debuggable is set to true.") }
        val gradle = context.project.filesOf(SourceKind.GRADLE).flatMap { releaseDebuggable(it) }
        return manifest + gradle
    }

    private fun releaseDebuggable(file: SourceFile): List<RuleHit> {
        val hits = mutableListOf<RuleHit>()
        var depth = 0
        var releaseDepth: Int? = null
        file.code.forEachIndexed { index, line ->
            if (releaseDepth == null && RELEASE_OPEN.containsMatchIn(line)) releaseDepth = depth
            if (releaseDepth != null && DEBUGGABLE.containsMatchIn(line)) {
                hits += RuleHit(file.path, index + 1, "The release build type is debuggable.")
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (releaseDepth != null && depth <= releaseDepth!!) releaseDepth = null
        }
        return hits
    }
}

/** Backups allowed (explicitly, or by default). */
internal object ManifestAllowBackup : AssessRule {
    override val id = "manifest-allow-backup"
    override val title = "App data can be backed up"
    override val masvs = MasvsCategory.STORAGE
    override val defaultRisk = Risk.MEDIUM
    override val defaultEase = Ease.EASY
    override val whyItMatters =
        "android:allowBackup defaults to true. Payment state, tokens, and settings in app storage can then be copied off " +
            "the device in backups and restored onto another device."
    override val howToFix =
        "Set android:allowBackup=\"false\", or keep backup on and exclude payment data with backup rules " +
            "(android:fullBackupContent, and android:dataExtractionRules on API 31+)."

    private val EXPLICIT = Regex("""android:allowBackup\s*=\s*"true"""")
    private val ANY_SETTING = Regex("""android:allowBackup|android:fullBackupContent|android:dataExtractionRules""")
    private val APPLICATION = Regex("""<application\b""")

    override fun check(context: RuleContext): List<RuleHit> = context.project.filesOf(SourceKind.MANIFEST).flatMap { file ->
        val explicit = file.code.withIndex().filter { EXPLICIT.containsMatchIn(it.value) }
            .map { RuleHit(file.path, it.index + 1, "android:allowBackup is set to true.") }
        val appLine = file.code.indexOfFirst { APPLICATION.containsMatchIn(it) }
        val implicit = if (appLine >= 0 && !file.containsCode(ANY_SETTING)) {
            listOf(RuleHit(file.path, appLine + 1, "android:allowBackup isn't set, so backup is on by default."))
        } else {
            emptyList()
        }
        explicit + implicit
    }
}

/** Cleartext HTTP allowed or used. */
internal object CleartextTraffic : AssessRule {
    override val id = "cleartext-traffic"
    override val title = "Cleartext HTTP traffic"
    override val masvs = MasvsCategory.NETWORK
    override val defaultRisk = Risk.HIGH
    override val defaultEase = Ease.EASY
    override val whyItMatters =
        "Plain HTTP can be read and changed by anyone on the store's Wi-Fi. Payment status, receipts, configuration, and " +
            "update checks sent this way can be faked."
    override val howToFix =
        "Use HTTPS everywhere, remove android:usesCleartextTraffic=\"true\", and don't permit cleartext in the network " +
            "security config (except for local development hosts)."

    private val MANIFEST = Regex("""android:usesCleartextTraffic\s*=\s*"true"""")
    private val NETWORK_CONFIG = Regex("""cleartextTrafficPermitted\s*=\s*"true"""")
    private val HTTP_LITERAL = Regex(""""http://(?!localhost|127\.0\.0\.1|10\.0\.2\.2|schemas\.android\.com|www\.w3\.org)""")

    override fun check(context: RuleContext): List<RuleHit> {
        val project = context.project
        val manifest = project.matches(MANIFEST, project.filesOf(SourceKind.MANIFEST))
            .map { (f, l) -> RuleHit(f.path, l, "android:usesCleartextTraffic is set to true.") }
        val config = project.matches(NETWORK_CONFIG, project.filesOf(SourceKind.XML))
            .map { (f, l) -> RuleHit(f.path, l, "The network security config permits cleartext traffic.") }
        val code = project.matches(HTTP_LITERAL, project.filesOf(SourceKind.KOTLIN, SourceKind.JAVA, SourceKind.GRADLE))
            .map { (f, l) -> RuleHit(f.path, l, "Plain http:// URL.") }
        return manifest + config + code
    }
}
