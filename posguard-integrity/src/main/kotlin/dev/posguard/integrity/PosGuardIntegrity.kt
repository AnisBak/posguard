package dev.posguard.integrity

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import dev.posguard.core.signal.IntegritySignal

/**
 * One-line setup for the built-in signals.
 *
 * ```
 * // Application.onCreate
 * val integrity = PosGuardIntegrity.install(this, IntegrityConfig.fromProperties(props))
 * val guard = PosGuard.Builder(ledger).signals(integrity.signals()).build()
 * ```
 *
 * `install` registers activity lifecycle callbacks that attach the monitors to
 * every started activity.
 */
public class PosGuardIntegrity private constructor(
    private val context: Context,
    public val config: IntegrityConfig,
    public val touchMonitor: ObscuredTouchMonitor,
    public val recordingMonitor: ScreenRecordingMonitor,
) {
    /** The default signal set, ready for `PosGuard.Builder.signals(...)`. */
    public fun signals(): List<IntegritySignal> = listOf(
        ObscuredTouchSignal(touchMonitor, config),
        ScreenRecordingSignal(recordingMonitor),
        AccessibilitySignal(context, config),
        DebuggerSignal(context),
        RootEmulatorSignal(),
    )

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            touchMonitor.install(activity.window)
            recordingMonitor.onStarted(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            recordingMonitor.onStopped(activity)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    public companion object {
        /** Registers the monitors on [application] and returns the integrity setup. Main thread. */
        public fun install(application: Application, config: IntegrityConfig = IntegrityConfig()): PosGuardIntegrity {
            val integrity = PosGuardIntegrity(application, config, ObscuredTouchMonitor(), ScreenRecordingMonitor())
            application.registerActivityLifecycleCallbacks(integrity.lifecycle)
            return integrity
        }
    }
}
