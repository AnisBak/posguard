package dev.posguard.core.ledger

import dev.posguard.core.ledger.TxnState.AUTHORIZED
import dev.posguard.core.ledger.TxnState.COMPLETED
import dev.posguard.core.ledger.TxnState.CREATED
import dev.posguard.core.ledger.TxnState.DECLINED
import dev.posguard.core.ledger.TxnState.SUBMITTED
import dev.posguard.core.ledger.TxnState.UNKNOWN
import dev.posguard.core.ledger.TxnState.VOIDED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TxnStateMachineTest {

    /** The full allowed set. Every pair not listed here must be rejected. */
    private val expectedAllowed = setOf(
        CREATED to SUBMITTED,
        CREATED to VOIDED,
        SUBMITTED to AUTHORIZED,
        SUBMITTED to DECLINED,
        SUBMITTED to UNKNOWN,
        UNKNOWN to AUTHORIZED,
        UNKNOWN to DECLINED,
        UNKNOWN to SUBMITTED,
        UNKNOWN to VOIDED,
        AUTHORIZED to COMPLETED,
        AUTHORIZED to VOIDED,
        DECLINED to COMPLETED,
    )

    @Test
    fun `full transition matrix matches the documented state machine`() {
        for (from in TxnState.entries) {
            for (to in TxnState.entries) {
                val expected = (from to to) in expectedAllowed
                assertEquals("$from -> $to", expected, TxnStateMachine.canTransition(from, to))
            }
        }
    }

    @Test
    fun `requireTransition throws with both states on forbidden move`() {
        val e = assertThrows(IllegalTxnTransitionException::class.java) {
            TxnStateMachine.requireTransition(DECLINED, AUTHORIZED)
        }
        assertEquals(DECLINED, e.from)
        assertEquals(AUTHORIZED, e.to)
    }

    @Test
    fun `requireTransition passes on allowed move`() {
        TxnStateMachine.requireTransition(SUBMITTED, UNKNOWN)
    }

    @Test
    fun `no self transitions`() {
        TxnState.entries.forEach { assertFalse("$it -> $it", TxnStateMachine.canTransition(it, it)) }
    }

    @Test
    fun `terminal states have no exits`() {
        TxnState.entries.filter { it.isTerminal }.forEach {
            assertEquals(emptySet<TxnState>(), TxnStateMachine.nextStates(it))
        }
        assertEquals(setOf(COMPLETED, VOIDED), TxnState.entries.filter { it.isTerminal }.toSet())
    }

    @Test
    fun `declined can never become authorized or voided`() {
        assertFalse(TxnStateMachine.canTransition(DECLINED, AUTHORIZED))
        assertFalse(TxnStateMachine.canTransition(DECLINED, VOIDED))
    }

    @Test
    fun `states needing recovery are the ones the engine may have seen without a recorded result`() {
        assertEquals(
            setOf(CREATED, SUBMITTED, UNKNOWN),
            TxnState.entries.filter { it.needsRecovery }.toSet(),
        )
    }

    @Test
    fun `every non terminal state can reach a terminal state`() {
        for (start in TxnState.entries) {
            val seen = mutableSetOf(start)
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                TxnStateMachine.nextStates(queue.removeFirst()).filter(seen::add).forEach(queue::addLast)
            }
            assertTrue("$start cannot reach a terminal state", seen.any { it.isTerminal })
        }
    }
}
