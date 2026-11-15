package dev.posguard.ebt

import dev.posguard.ebt.EbtEntryMode.CHIP_INSERT
import dev.posguard.ebt.EbtEntryMode.CHIP_TAP
import dev.posguard.ebt.EbtEntryMode.MAGSTRIPE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EbtEntryFlowTest {

    @Test
    fun `falls back insert to tap to magstripe, then gives up`() {
        val flow = EbtEntryFlow()
        assertEquals(EntryPrompt.Present(CHIP_INSERT), flow.start())

        val tap = flow.onFailed(CHIP_INSERT, EntryFailure.CHIP_READ_FAILED) as EntryPrompt.Present
        assertEquals(CHIP_TAP, tap.mode)
        assertEquals(EntryAttempt(CHIP_INSERT, EntryFailure.CHIP_READ_FAILED), tap.fallbackFrom)

        val swipe = flow.onFailed(CHIP_TAP, EntryFailure.MODE_NOT_SUPPORTED) as EntryPrompt.Present
        assertEquals(MAGSTRIPE, swipe.mode)

        val done = flow.onFailed(MAGSTRIPE, EntryFailure.MAGSTRIPE_READ_FAILED)
        assertEquals(3, (done as EntryPrompt.Exhausted).attempts.size)
    }

    @Test
    fun `no chip or unrecognized chip app skips straight to magstripe`() {
        listOf(EntryFailure.CARD_HAS_NO_CHIP, EntryFailure.CHIP_APP_NOT_RECOGNIZED).forEach { failure ->
            val flow = EbtEntryFlow()
            flow.start()
            assertEquals(MAGSTRIPE, (flow.onFailed(CHIP_INSERT, failure) as EntryPrompt.Present).mode)
        }
    }

    @Test
    fun `without magstripe in the order, chip failures end the flow`() {
        val flow = EbtEntryFlow(listOf(CHIP_TAP, CHIP_INSERT))
        assertEquals(CHIP_TAP, (flow.start() as EntryPrompt.Present).mode)
        assertEquals(CHIP_INSERT, (flow.onFailed(CHIP_TAP, EntryFailure.CHIP_READ_FAILED) as EntryPrompt.Present).mode)
        assert(flow.onFailed(CHIP_INSERT, EntryFailure.CHIP_READ_FAILED) is EntryPrompt.Exhausted)

        val noChip = EbtEntryFlow(listOf(CHIP_INSERT, CHIP_TAP))
        noChip.start()
        assert(noChip.onFailed(CHIP_INSERT, EntryFailure.CARD_HAS_NO_CHIP) is EntryPrompt.Exhausted)
    }

    @Test
    fun `rejects unsafe orders and out-of-turn results`() {
        assertThrows(IllegalArgumentException::class.java) { EbtEntryFlow(listOf(MAGSTRIPE, CHIP_INSERT)) }
        assertThrows(IllegalArgumentException::class.java) { EbtEntryFlow(listOf(CHIP_INSERT, MAGSTRIPE, CHIP_TAP)) }

        val flow = EbtEntryFlow()
        flow.start()
        assertThrows(IllegalStateException::class.java) { flow.onFailed(MAGSTRIPE, EntryFailure.MAGSTRIPE_READ_FAILED) }
        flow.onRead(CHIP_INSERT)
        assertThrows(IllegalStateException::class.java) { flow.onFailed(CHIP_INSERT, EntryFailure.CHIP_READ_FAILED) }
    }
}
