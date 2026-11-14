package dev.posguard.integrity.internal

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.view.accessibility.AccessibilityManager
import java.io.File

/** What the accessibility signal needs to know about one enabled service. No content is read. */
internal data class AccessibilityServiceSnapshot(
    val packageName: String,
    val canRetrieveWindowContent: Boolean,
    val canPerformGestures: Boolean,
    /** Self-declared (API 31+). Diagnostic only, never trusted. Null below API 31. */
    val declaresAccessibilityTool: Boolean?,
)

internal fun interface AccessibilityServicesSource {
    fun enabledServices(): List<AccessibilityServiceSnapshot>
}

internal class AndroidAccessibilityServicesSource(context: Context) : AccessibilityServicesSource {
    private val manager = context.applicationContext.getSystemService(AccessibilityManager::class.java)

    override fun enabledServices(): List<AccessibilityServiceSnapshot> {
        val am = checkNotNull(manager) { "AccessibilityManager unavailable" }
        // API 14: getEnabledAccessibilityServiceList. FEEDBACK_ALL_MASK covers every feedback type.
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).map { info ->
            // API 18: getCapabilities. CAPABILITY_CAN_PERFORM_GESTURES is API 24 (below our minSdk 26).
            val caps = info.capabilities
            AccessibilityServiceSnapshot(
                packageName = info.id?.substringBefore('/')
                    ?: info.resolveInfo?.serviceInfo?.packageName
                    ?: "unknown",
                canRetrieveWindowContent = caps and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0,
                canPerformGestures = caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0,
                // API 31: isAccessibilityTool.
                declaresAccessibilityTool =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.isAccessibilityTool else null,
            )
        }
    }
}

internal interface DebugProbe {
    fun isDebuggerConnected(): Boolean
    fun isDebuggableBuild(): Boolean
}

internal class AndroidDebugProbe(context: Context) : DebugProbe {
    private val appInfo = context.applicationContext.applicationInfo

    // API 1: Debug.isDebuggerConnected. Settings.Global.ADB_ENABLED is not used:
    // it always returns 0 for third-party apps.
    override fun isDebuggerConnected(): Boolean = Debug.isDebuggerConnected()

    // API 1: ApplicationInfo.FLAG_DEBUGGABLE.
    override fun isDebuggableBuild(): Boolean = appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}

/** Read-only device facts for root and emulator heuristics. */
internal interface SystemProbe {
    fun fileExists(path: String): Boolean
    val tags: String
    val fingerprint: String
    val hardware: String
    val product: String
    val model: String
    val manufacturer: String
}

internal object AndroidSystemProbe : SystemProbe {
    override fun fileExists(path: String): Boolean = File(path).exists()
    override val tags: String get() = Build.TAGS.orEmpty()
    override val fingerprint: String get() = Build.FINGERPRINT.orEmpty()
    override val hardware: String get() = Build.HARDWARE.orEmpty()
    override val product: String get() = Build.PRODUCT.orEmpty()
    override val model: String get() = Build.MODEL.orEmpty()
    override val manufacturer: String get() = Build.MANUFACTURER.orEmpty()
}
