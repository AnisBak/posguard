package dev.posguard.integrity

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import dev.posguard.core.signal.IntegritySignal
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.ReasonCode
import kotlin.time.Duration

/**
 * Watches touch events for the "window is obscured" flags the platform sets
 * when another app's window covers this one.
 *
 * Android has no API to ask "is an overlay present?". The only documented
 * signal is these flags on touches, so detection needs at least one touch,
 * typically the "Take payment" tap that triggers preflight.
 *
 * - `MotionEvent.FLAG_WINDOW_IS_OBSCURED`: API 9. The touch passed through an obscured area.
 * - `MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED`: API 29. The window is obscured elsewhere.
 */
public class ObscuredTouchMonitor internal constructor(private val now: () -> Long) {

    public constructor() : this(SystemClock::elapsedRealtime)

    @Volatile internal var installed: Boolean = false
        private set

    @Volatile private var lastObscuredAtMs: Long = NEVER

    @Volatile private var lastObscuredKind: String? = null

    /** Records a touch. Called from the wrapped `Window.Callback` on the main thread. */
    public fun onTouch(event: MotionEvent) {
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return
        val kind = when {
            event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 -> "obscured"
            // API 29: the partially-obscured flag is only set on 29+.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0 -> "partially-obscured"
            else -> null
        }
        if (kind != null) {
            lastObscuredKind = kind
            lastObscuredAtMs = now()
        }
    }

    /** Wraps [window]'s callback so every touch is observed. Idempotent. Main thread. */
    public fun install(window: Window) {
        val current = window.callback
        if (current !is ObscuredTouchWindowCallback && current != null) {
            window.callback = ObscuredTouchWindowCallback(current, this)
        }
        installed = true
    }

    /** The kind of the most recent obscured touch within [window], or null. */
    internal fun recentObscuredTouch(window: Duration): String? {
        val at = lastObscuredAtMs
        if (at == NEVER) return null
        return if (now() - at <= window.inWholeMilliseconds) lastObscuredKind else null
    }

    /** Forgets past touches, for example after the merchant dismissed a warning. */
    public fun reset() {
        lastObscuredAtMs = NEVER
        lastObscuredKind = null
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}

/** Passes every event to the original callback after showing touches to the monitor. */
internal class ObscuredTouchWindowCallback(
    internal val delegate: Window.Callback,
    private val monitor: ObscuredTouchMonitor,
) : Window.Callback by delegate {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        try {
            monitor.onTouch(event)
        } catch (_: Exception) {
            // Monitoring must never break input.
        }
        return delegate.dispatchTouchEvent(event)
    }
}

/**
 * Raises [ReasonCode.SCREEN_OVERLAY_PRESENT] if an obscured touch was seen
 * within `IntegrityConfig.obscuredTouchWindow`.
 *
 * Limits: no touch means nothing to observe (the result is `Clear`), and an
 * overlay that never covers a touched window is invisible to this signal. Pair it
 * with [PaymentScreenProtection] and Play Integrity's overlay verdict.
 */
public class ObscuredTouchSignal(
    private val monitor: ObscuredTouchMonitor,
    private val config: IntegrityConfig = IntegrityConfig(),
) : IntegritySignal {

    override val id: String = ID

    override suspend fun check(): SignalResult = try {
        when {
            !monitor.installed -> SignalResult.Unavailable(id, "monitor-not-installed")
            else -> monitor.recentObscuredTouch(config.obscuredTouchWindow)
                ?.let { SignalResult.Detected(id, ReasonCode.SCREEN_OVERLAY_PRESENT, it) }
                ?: SignalResult.Clear(id)
        }
    } catch (_: Exception) {
        SignalResult.Unavailable(id, "monitor-error")
    }

    public companion object {
        public const val ID: String = "screen.overlay"
    }
}

/** What [PaymentScreenProtection.protect] managed to enable. */
public data class ProtectionResult(val secureFlag: Boolean, val overlaysHidden: Boolean)

/**
 * Hardens the window that hosts payment acceptance:
 * - `FLAG_SECURE` (API 1): no screenshots, and the window isn't shown on non-secure displays.
 * - `Window.setHideOverlayWindows(true)` (API 31): hides non-system overlays. It needs
 *   `android.permission.HIDE_OVERLAY_WINDOWS` declared by the app and is skipped if not granted.
 * - `filterTouchesWhenObscured` on the decor view: drops touches that pass through an overlay.
 *
 * Call on the main thread when the payment screen opens, and call [unprotect] when it closes.
 */
public object PaymentScreenProtection {

    public fun protect(activity: Activity): ProtectionResult = protect(activity.window, activity)

    public fun protect(window: Window, context: Context): ProtectionResult {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = true
        val hidden = setHideOverlays(window, context, hide = true)
        return ProtectionResult(secureFlag = true, overlaysHidden = hidden)
    }

    public fun unprotect(activity: Activity): Unit = unprotect(activity.window, activity)

    public fun unprotect(window: Window, context: Context) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = false
        setHideOverlays(window, context, hide = false)
    }

    private fun setHideOverlays(window: Window, context: Context, hide: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        // API 31: Window.setHideOverlayWindows, Manifest.permission.HIDE_OVERLAY_WINDOWS.
        val granted = context.checkSelfPermission(Manifest.permission.HIDE_OVERLAY_WINDOWS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        return try {
            window.setHideOverlayWindows(hide)
            hide
        } catch (_: SecurityException) {
            false
        }
    }
}
