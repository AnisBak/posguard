package dev.posguard.demo

import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.ReasonCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoThreatsTest {

    @Test
    fun `signals are clear until toggled and only add detections`() = runTest {
        val threats = DemoThreats()
        threats.signals.forEach { assertEquals(SignalResult.Clear(it.id), it.check()) }

        threats.setOverlay(true)
        assertEquals(
            listOf(
                SignalResult.Detected("demo.overlay", ReasonCode.SCREEN_OVERLAY_PRESENT, DemoThreats.DETAIL),
                SignalResult.Clear("demo.recording"),
            ),
            threats.signals.map { it.check() },
        )

        threats.setRecording(true)
        threats.clear()
        threats.signals.forEach { assertEquals(SignalResult.Clear(it.id), it.check()) }
    }
}
