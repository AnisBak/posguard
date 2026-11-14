package dev.posguard.integrity

import android.app.Activity
import android.view.WindowManager
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.ReasonCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ScreenRecordingTest {

    private val monitor = ScreenRecordingMonitor()
    private val signal = ScreenRecordingSignal(monitor)

    @Test
    @Config(sdk = [34])
    fun `below API 35 is unavailable`() = runTest {
        monitor.onState(1) // ignored by the signal on old platforms
        assertEquals(SignalResult.Unavailable(ScreenRecordingSignal.ID, "requires API 35"), signal.check())
    }

    @Test
    @Config(sdk = [35])
    fun `unknown state before any callback is unavailable`() = runTest {
        assertEquals(SignalResult.Unavailable(ScreenRecordingSignal.ID, "state-unknown"), signal.check())
    }

    @Test
    @Config(sdk = [35])
    fun `visible recording is detected`() = runTest {
        monitor.onState(WindowManager.SCREEN_RECORDING_STATE_VISIBLE)
        assertEquals(
            SignalResult.Detected(ScreenRecordingSignal.ID, ReasonCode.SCREEN_CAPTURE_ACTIVE, "recording-visible"),
            signal.check(),
        )
    }

    @Test
    @Config(sdk = [35])
    fun `recording stopping clears the signal`() = runTest {
        monitor.onState(WindowManager.SCREEN_RECORDING_STATE_VISIBLE)
        monitor.onState(WindowManager.SCREEN_RECORDING_STATE_NOT_VISIBLE)
        assertEquals(SignalResult.Clear(ScreenRecordingSignal.ID), signal.check())
    }

    @Test
    @Config(sdk = [35])
    fun `registering on a real activity never throws and reports a state`() = runTest {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        monitor.onStarted(controller.get())
        val result = signal.check()
        // Robolectric may or may not implement the platform callback; either way it is fail-safe.
        val ok = result == SignalResult.Clear(ScreenRecordingSignal.ID) ||
            result == SignalResult.Unavailable(ScreenRecordingSignal.ID, "callback-registration-failed")
        assertEquals("unexpected $result", true, ok)
        monitor.onStopped(controller.get())
    }

    @Test
    @Config(sdk = [34])
    fun `monitor does nothing below API 35`() = runTest {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        monitor.onStarted(activity)
        monitor.onStopped(activity)
        assertEquals(SignalResult.Unavailable(ScreenRecordingSignal.ID, "requires API 35"), signal.check())
    }
}
