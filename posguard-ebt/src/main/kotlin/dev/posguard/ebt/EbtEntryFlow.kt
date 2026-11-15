package dev.posguard.ebt

/** Why a card-entry attempt failed, as reported by the certified engine. */
public enum class EntryFailure {
    /** The chip could not be read (insert or tap). */
    CHIP_READ_FAILED,

    /**
     * The reader saw a chip but did not recognize the SNAP EBT application
     * (an "empty candidate list"). The proposed standards say the POS device
     * must then allow a magnetic stripe transaction (91 FR 66958, Appendix A (o)(3)(i)).
     */
    CHIP_APP_NOT_RECOGNIZED,

    /** This card or reader can't do this mode (for example, the card has no NFC). */
    MODE_NOT_SUPPORTED,

    /** The card has no chip at all (an older magnetic-stripe-only card). */
    CARD_HAS_NO_CHIP,

    /** The magnetic stripe could not be read. */
    MAGSTRIPE_READ_FAILED,

    /** The engine timed out or threw while reading. Nothing was sent to the processor. */
    READ_ERROR,
}

/** One failed attempt. */
public data class EntryAttempt(val mode: EbtEntryMode, val failure: EntryFailure)

/** What the app should ask the customer to do next. */
public sealed interface EntryPrompt {
    /**
     * Present the card in [mode]. [fallbackFrom] is the mode that just failed,
     * or null on the first prompt.
     */
    public data class Present(val mode: EbtEntryMode, val fallbackFrom: EntryAttempt? = null) : EntryPrompt

    /**
     * Every allowed mode failed. Ask for another tender. posguard never offers
     * key entry: it would mean handling the card number.
     */
    public data class Exhausted(val attempts: List<EntryAttempt>) : EntryPrompt
}

/**
 * Chip-first card-entry state machine for one EBT leg. One attempt per mode,
 * in [order]. It never reads the card; the engine does that and reports
 * failures here.
 *
 * Magnetic stripe is only offered after at least one failed chip attempt, or
 * when the card has no chip at all ([EntryFailure.CARD_HAS_NO_CHIP]). Not
 * thread-safe: use one instance per leg, from one coroutine.
 */
public class EbtEntryFlow(private val order: List<EbtEntryMode> = EbtConfig.DEFAULT_ENTRY_ORDER) {

    init {
        // Same rules as EbtConfig, so a flow can't be built with an unsafe order.
        require(order.isNotEmpty() && order.first().isChip) { "order must start with a chip mode." }
        require(EbtEntryMode.MAGSTRIPE !in order || order.last() == EbtEntryMode.MAGSTRIPE) {
            "MAGSTRIPE may only be last."
        }
        require(order.toSet().size == order.size) { "order must not repeat a mode." }
    }

    private val failed = mutableListOf<EntryAttempt>()
    private var index = 0
    private var finished = false

    /** Failed attempts so far, oldest first. */
    public val attempts: List<EntryAttempt> get() = failed.toList()

    /** The first prompt. */
    public fun start(): EntryPrompt {
        check(failed.isEmpty() && index == 0 && !finished) { "start() may only be called once, first." }
        return EntryPrompt.Present(order.first())
    }

    /**
     * Records that [mode] failed and returns the next prompt.
     *
     * @throws IllegalStateException if [mode] isn't the mode that was prompted.
     */
    public fun onFailed(mode: EbtEntryMode, failure: EntryFailure): EntryPrompt {
        check(!finished) { "The flow already finished." }
        check(mode == order[index]) { "Expected a result for ${order[index]}, got $mode." }
        val attempt = EntryAttempt(mode, failure)
        failed += attempt

        index = when {
            // No chip, or a chip without the SNAP app: other chip modes would fail the same way.
            failure in SKIP_TO_MAGSTRIPE -> order.indexOf(EbtEntryMode.MAGSTRIPE).takeIf { it >= 0 } ?: order.size
            else -> index + 1
        }
        if (index >= order.size) {
            finished = true
            return EntryPrompt.Exhausted(attempts)
        }
        val next = order[index]
        check(next.isChip || magstripeAllowed()) { "Magstripe offered before a chip attempt failed." }
        return EntryPrompt.Present(next, fallbackFrom = attempt)
    }

    /** Records a successful read. The flow is then finished. */
    public fun onRead(mode: EbtEntryMode) {
        check(!finished) { "The flow already finished." }
        check(mode == order[index]) { "Expected a result for ${order[index]}, got $mode." }
        finished = true
    }

    private fun magstripeAllowed(): Boolean = failed.any { it.mode.isChip }

    private companion object {
        val SKIP_TO_MAGSTRIPE = setOf(EntryFailure.CARD_HAS_NO_CHIP, EntryFailure.CHIP_APP_NOT_RECOGNIZED)
    }
}
