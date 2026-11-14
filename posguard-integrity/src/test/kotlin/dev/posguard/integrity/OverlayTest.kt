package dev.posguard.integrity

import android.Manifest
import android.app.Activity
import android.app.Application
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.ReasonCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class OverlayTest {

    private var clockMs = 1_000_000L
    private val monitor = ObscuredTouchMonitor { clockMs }
    private val config = IntegrityConfig(obscuredTouchWindow = 30.seconds)
    private val signal = ObscuredTouchSignal(monitor, config)

    private fun touch(action: Int = MotionEvent.ACTION_DOWN, flags: Int = 0): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 10f; y = 10f })
        return MotionEvent.obtain(0, 0, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, flags)
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    /** A Window.Callback that records dispatched touches, standing in for the app's own. */
    private fun recordingCallback(received: MutableList<MotionEvent>): Window.Callback =
        Proxy.newProxyInstance(Window.Callback::class.java.classLoader, arrayOf(Window.Callback::class.java)) { _, method, args ->
            if (method.name == "dispatchTouchEvent") {
                received += args[0] as MotionEvent
                true
            } else if (method.returnType == Boolean::class.javaPrimitiveType) {
                false
            } else {
                null
            }
        } as Window.Callback

    @Test
    fun `not installed is unavailable`() = runTest {
        assertEquals(SignalResult.Unavailable(ObscuredTouchSignal.ID, "monitor-not-installed"), signal.check())
    }

    @Test
    fun `installed with no touches is clear`() = runTest {
        monitor.install(activity().window)
        assertEquals(SignalResult.Clear(ObscuredTouchSignal.ID), signal.check())
    }

    @Test
    fun `obscured touch through the window callback is detected and still delivered`() = runTest {
        val window = activity().window
        val received = mutableListOf<MotionEvent>()
        window.callback = recordingCallback(received)
        monitor.install(window)

        val event = touch(flags = MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        window.callback.dispatchTouchEvent(event)

        assertSame(event, received.single())
        assertEquals(
            SignalResult.Detected(ObscuredTouchSignal.ID, ReasonCode.SCREEN_OVERLAY_PRESENT, "obscured"),
            signal.check(),
        )
    }

    @Test
    @Config(sdk = [29])
    fun `partially obscured touch is detected on API 29+`() = runTest {
        monitor.install(activity().window)
        monitor.onTouch(touch(flags = MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED))
        assertEquals("partially-obscured", (signal.check() as SignalResult.Detected).detail)
    }

    @Test
    fun `clean touch stays clear`() = runTest {
        monitor.install(activity().window)
        monitor.onTouch(touch())
        assertEquals(SignalResult.Clear(ObscuredTouchSignal.ID), signal.check())
    }

    @Test
    fun `obscured touch expires after the configured window`() = runTest {
        monitor.install(activity().window)
        monitor.onTouch(touch(flags = MotionEvent.FLAG_WINDOW_IS_OBSCURED))
        clockMs += 30_000
        assertTrue(signal.check() is SignalResult.Detected)
        clockMs += 1
        assertEquals(SignalResult.Clear(ObscuredTouchSignal.ID), signal.check())
    }

    @Test
    fun `move events are ignored and reset clears`() = runTest {
        monitor.install(activity().window)
        monitor.onTouch(touch(action = MotionEvent.ACTION_MOVE, flags = MotionEvent.FLAG_WINDOW_IS_OBSCURED))
        assertEquals(SignalResult.Clear(ObscuredTouchSignal.ID), signal.check())

        monitor.onTouch(touch(flags = MotionEvent.FLAG_WINDOW_IS_OBSCURED))
        monitor.reset()
        assertEquals(SignalResult.Clear(ObscuredTouchSignal.ID), signal.check())
    }

    @Test
    fun `install is idempotent`() {
        val window = activity().window
        monitor.install(window)
        val wrapped = window.callback
        monitor.install(window)
        assertSame(wrapped, window.callback)
        assertTrue(wrapped is ObscuredTouchWindowCallback)
    }

    @Test
    fun `install via application attaches to started activities`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val integrity = PosGuardIntegrity.install(app)
        val window = activity().window

        assertTrue(window.callback is ObscuredTouchWindowCallback)
        window.callback.dispatchTouchEvent(touch(flags = MotionEvent.FLAG_WINDOW_IS_OBSCURED))
        val overlay = integrity.signals().first { it.id == ObscuredTouchSignal.ID }
        assertTrue(overlay.check() is SignalResult.Detected)
        assertEquals(
            listOf(ObscuredTouchSignal.ID, AccessibilitySignal.ID, DebuggerSignal.ID, RootEmulatorSignal.ID),
            integrity.signals().map { it.id },
        )
    }

    // --- Payment screen protection -------------------------------------------------------

    private fun Window.hasSecureFlag() = attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    @Config(sdk = [30])
    fun `protect sets FLAG_SECURE and filters obscured touches below API 31`() {
        val activity = activity()
        val result = PaymentScreenProtection.protect(activity)

        assertEquals(ProtectionResult(secureFlag = true, overlaysHidden = false), result)
        assertTrue(activity.window.hasSecureFlag())
        assertTrue(activity.window.decorView.filterTouchesWhenObscured)

        PaymentScreenProtection.unprotect(activity)
        assertFalse(activity.window.hasSecureFlag())
        assertFalse(activity.window.decorView.filterTouchesWhenObscured)
    }

    @Test
    @Config(sdk = [31])
    fun `overlays are not hidden on API 31 without the permission`() {
        assertFalse(PaymentScreenProtection.protect(activity()).overlaysHidden)
    }

    @Test
    @Config(sdk = [31])
    fun `overlays are hidden on API 31 when the permission is granted`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
        val activity = activity()
        assertTrue(PaymentScreenProtection.protect(activity).overlaysHidden)
    }
}
