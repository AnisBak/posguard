package dev.posguard.integrity

import android.content.Context
import dev.posguard.core.signal.IntegritySignal
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.ReasonCode
import dev.posguard.integrity.internal.AccessibilityServicesSource
import dev.posguard.integrity.internal.AndroidAccessibilityServicesSource
import dev.posguard.integrity.internal.AndroidDebugProbe
import dev.posguard.integrity.internal.AndroidSystemProbe
import dev.posguard.integrity.internal.DebugProbe
import dev.posguard.integrity.internal.SystemProbe

/**
 * Flags enabled accessibility services that are **not** on the allowlist and
 * can read window content or perform gestures (the capabilities abused by
 * overlay and remote-control malware). Only service metadata is read, never
 * content.
 */
public class AccessibilitySignal internal constructor(
    private val source: AccessibilityServicesSource,
    private val allowlist: Set<String>,
) : IntegritySignal {

    public constructor(context: Context, config: IntegrityConfig = IntegrityConfig()) :
        this(AndroidAccessibilityServicesSource(context), config.accessibilityAllowlist)

    override val id: String = ID

    override suspend fun check(): SignalResult = try {
        val risky = source.enabledServices().filter { service ->
            service.packageName !in allowlist && (service.canRetrieveWindowContent || service.canPerformGestures)
        }
        if (risky.isEmpty()) {
            SignalResult.Clear(id)
        } else {
            SignalResult.Detected(id, ReasonCode.RISKY_ACCESSIBILITY_SERVICE, "count=${risky.size}")
        }
    } catch (_: Exception) {
        SignalResult.Unavailable(id, "accessibility-manager-error")
    }

    public companion object {
        public const val ID: String = "accessibility.services"
    }
}

/** Raises [ReasonCode.DEBUGGER_ATTACHED] while a debugger is attached. */
public class DebuggerSignal internal constructor(private val probe: DebugProbe) : IntegritySignal {

    public constructor(context: Context) : this(AndroidDebugProbe(context))

    override val id: String = ID

    override suspend fun check(): SignalResult = try {
        val detail = if (probe.isDebuggableBuild()) "debuggable-build" else null
        if (probe.isDebuggerConnected()) {
            SignalResult.Detected(id, ReasonCode.DEBUGGER_ATTACHED, detail)
        } else {
            SignalResult.Clear(id)
        }
    } catch (_: Exception) {
        SignalResult.Unavailable(id, "debug-probe-error")
    }

    public companion object {
        public const val ID: String = "app.debugger"
    }
}

/**
 * Lightweight root and emulator heuristics. **A signal, not proof.** Custom
 * ROMs and developer devices can trigger it, and a determined attacker can hide
 * from it. Use Play Integrity (posguard-integrity-play) for stronger evidence.
 */
public class RootEmulatorSignal internal constructor(private val probe: SystemProbe) : IntegritySignal {

    public constructor() : this(AndroidSystemProbe)

    override val id: String = ID

    override suspend fun check(): SignalResult = try {
        val findings = buildList {
            if (SU_PATHS.any(probe::fileExists)) add("su-binary")
            if (MAGISK_PATHS.any(probe::fileExists)) add("magisk")
            if (probe.tags.contains("test-keys")) add("test-keys")
            if (looksLikeEmulator()) add("emulator")
        }
        if (findings.isEmpty()) {
            SignalResult.Clear(id)
        } else {
            SignalResult.Detected(id, ReasonCode.ROOT_OR_EMULATOR_SUSPECTED, findings.joinToString(","))
        }
    } catch (_: Exception) {
        SignalResult.Unavailable(id, "system-probe-error")
    }

    private fun looksLikeEmulator(): Boolean {
        val fp = probe.fingerprint.lowercase()
        val product = probe.product.lowercase()
        return fp.startsWith("generic") ||
            fp.contains("emulator") ||
            fp.contains("sdk_gphone") ||
            probe.hardware.lowercase() in EMULATOR_HARDWARE ||
            product.contains("sdk_gphone") || product == "sdk" || product == "google_sdk" ||
            probe.model.contains("Emulator") || probe.model.contains("Android SDK built for") ||
            probe.manufacturer.contains("Genymotion")
    }

    public companion object {
        public const val ID: String = "device.root_emulator"

        private val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sd/xbin/su", "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
        )
        private val MAGISK_PATHS = listOf("/sbin/.magisk", "/data/adb/magisk", "/cache/.disable_magisk")
        private val EMULATOR_HARDWARE = setOf("goldfish", "ranchu", "vbox86")
    }
}
