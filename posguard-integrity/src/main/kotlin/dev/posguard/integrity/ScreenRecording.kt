package dev.posguard.integrity

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.view.WindowManager
import dev.posguard.core.signal.IntegritySignal
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.ReasonCode
import java.util.WeakHashMap
import java.util.function.Consumer

/**
 * Tracks whether this app's activities are visible in a screen recording.
 *
 * API 35: `WindowManager.addScreenRecordingCallback(Executor, Consumer<Integer>)`
 * returns the current state and reports changes between
 * `SCREEN_RECORDING_STATE_VISIBLE` and `SCREEN_RECORDING_STATE_NOT_VISIBLE`.
 * The state covers every activity owned by this app's UID. It needs
 * `android.permission.DETECT_SCREEN_RECORDING` (normal, declared in this
 * library's manifest). Nothing happens below API 35.
 */
public class ScreenRecordingMonitor {

    private val callbacks = WeakHashMap<Activity, Consumer<Int>>()

    /** Null until a callback reports. Then true while recording shows this app. */
    @Volatile internal var recordingVisible: Boolean? = null
        private set

    @Volatile internal var registrationFailed: Boolean = false
        private set

    /** Registers for [activity]. Called from `onActivityStarted`. Main thread. */
    public fun onStarted(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        if (callbacks.containsKey(activity)) return
        try {
            val wm = activity.getSystemService(WindowManager::class.java) ?: return
            val callback = Consumer<Int> { state -> onState(state) }
            // API 35: addScreenRecordingCallback returns the current state.
            val initial = wm.addScreenRecordingCallback(activity.mainExecutor, callback)
            callbacks[activity] = callback
            onState(initial)
            registrationFailed = false
        } catch (_: Exception) {
            // SecurityException if the permission was stripped, or OEM issues.
            registrationFailed = true
        }
    }

    /** Unregisters for [activity]. Called from `onActivityStopped`. Main thread. */
    public fun onStopped(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val callback = callbacks.remove(activity) ?: return
        try {
            activity.getSystemService(WindowManager::class.java)?.removeScreenRecordingCallback(callback)
        } catch (_: Exception) {
            // Best effort.
        }
    }

    /** Test and integration hook: applies a raw `SCREEN_RECORDING_STATE_*` value. */
    // The API 35 constant is inlined at compile time. States only arrive from the API 35
    // callback, so comparing against it on older devices is harmless.
    @SuppressLint("InlinedApi")
    internal fun onState(state: Int) {
        recordingVisible = state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE
    }
}

/**
 * Raises [ReasonCode.SCREEN_CAPTURE_ACTIVE] while this app is visible in a
 * screen recording (API 35+).
 *
 * Below API 35 it returns `Unavailable("requires API 35")`, because the
 * platform offers no detection there. Payment screens are still protected by
 * `FLAG_SECURE` ([PaymentScreenProtection]) on every version.
 */
public class ScreenRecordingSignal(private val monitor: ScreenRecordingMonitor) : IntegritySignal {

    override val id: String = ID

    override suspend fun check(): SignalResult = try {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                SignalResult.Unavailable(id, "requires API 35")
            monitor.registrationFailed -> SignalResult.Unavailable(id, "callback-registration-failed")
            else -> when (monitor.recordingVisible) {
                null -> SignalResult.Unavailable(id, "state-unknown")
                true -> SignalResult.Detected(id, ReasonCode.SCREEN_CAPTURE_ACTIVE, "recording-visible")
                false -> SignalResult.Clear(id)
            }
        }
    } catch (_: Exception) {
        SignalResult.Unavailable(id, "monitor-error")
    }

    public companion object {
        public const val ID: String = "screen.recording"
    }
}
