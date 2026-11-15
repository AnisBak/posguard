package dev.posguard.core.ledger

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CombinedStatusSourceTest {

    private val key = IdempotencyKey("k")
    private fun fixed(status: EngineStatus) = EngineStatusSource { status }
    private val throwing = EngineStatusSource { error("down") }

    @Test
    fun `a known answer from any source wins`() = runTest {
        val approved = EngineStatus.Authorized("ebt-1")
        assertEquals(approved, combinedStatusSource(fixed(EngineStatus.NotProcessed), fixed(approved)).queryStatus(key))
        assertEquals(approved, combinedStatusSource(fixed(EngineStatus.Unreachable), fixed(approved)).queryStatus(key))
        assertEquals(approved, combinedStatusSource(throwing, fixed(approved)).queryStatus(key))
        val declined = EngineStatus.Declined("c-1", "05")
        assertEquals(declined, combinedStatusSource(fixed(declined), fixed(approved)).queryStatus(key))
    }

    @Test
    fun `not processed only when every source agrees`() = runTest {
        assertEquals(
            EngineStatus.NotProcessed,
            combinedStatusSource(fixed(EngineStatus.NotProcessed), fixed(EngineStatus.NotProcessed)).queryStatus(key),
        )
        assertEquals(
            EngineStatus.Unreachable,
            combinedStatusSource(fixed(EngineStatus.NotProcessed), fixed(EngineStatus.Unreachable)).queryStatus(key),
        )
        assertEquals(EngineStatus.Unreachable, combinedStatusSource(fixed(EngineStatus.NotProcessed), throwing).queryStatus(key))
    }

    @Test
    fun `needs at least one source`() {
        assertThrows(IllegalArgumentException::class.java) { combinedStatusSource() }
    }
}
