package dev.posguard.demo

import dev.posguard.core.signal.IntegritySignal
import dev.posguard.core.signal.SignalResult
import dev.posguard.core.verdict.ReasonCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **Demo only.** Lets Demo controls simulate an overlay app (scenario 2) or an
 * active screen recording (scenario 3) without installing anything.
 *
 * These signals can only *add* detections. The real integrity signals keep
 * running beside them, so a real threat is still caught during a demo.
 */
class DemoThreats {
    private val overlayOn = MutableStateFlow(false)
    private val recordingOn = MutableStateFlow(false)

    val overlaySimulated: StateFlow<Boolean> = overlayOn.asStateFlow()
    val recordingSimulated: StateFlow<Boolean> = recordingOn.asStateFlow()

    val signals: List<IntegritySignal> = listOf(
        DemoInjectedSignal("demo.overlay", ReasonCode.SCREEN_OVERLAY_PRESENT, overlayOn),
        DemoInjectedSignal("demo.recording", ReasonCode.SCREEN_CAPTURE_ACTIVE, recordingOn),
    )

    fun setOverlay(on: Boolean) {
        overlayOn.value = on
    }

    fun setRecording(on: Boolean) {
        recordingOn.value = on
    }

    fun clear() {
        overlayOn.value = false
        recordingOn.value = false
    }

    companion object {
        const val DETAIL: String = "demo-simulated"
    }
}

internal class DemoInjectedSignal(
    override val id: String,
    private val reason: ReasonCode,
    private val active: StateFlow<Boolean>,
) : IntegritySignal {
    override suspend fun check(): SignalResult =
        if (active.value) SignalResult.Detected(id, reason, DemoThreats.DETAIL) else SignalResult.Clear(id)
}
