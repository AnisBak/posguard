package dev.posguard.core.signal

/**
 * One integrity check (overlay, screen capture, Play Integrity, ...).
 *
 * Implementations should return [SignalResult.Unavailable] when they cannot
 * run. As a safety net, [dev.posguard.core.PosGuard] also turns exceptions and
 * timeouts into [SignalResult.Unavailable], so a faulty signal never crashes
 * checkout.
 *
 * Implementations must not read the content of other apps or the screen.
 */
public interface IntegritySignal {
    /** Stable, unique identifier, for example `"screen.overlay"`. Used in telemetry. */
    public val id: String

    /** Runs the check. Called off the main thread. */
    public suspend fun check(): SignalResult
}
