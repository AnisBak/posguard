package dev.posguard.integrity.play

/**
 * The fields posguard uses from a **decoded** Play Integrity verdict. Your
 * backend decodes the token with `decodeIntegrityToken` and maps the payload
 * to this type (see docs/integrity.md).
 *
 * Values are the exact strings from the verdict payload.
 */
public data class IntegrityVerdict(
    /** `deviceIntegrity.deviceRecognitionVerdict`. Empty if the field was omitted. */
    val deviceRecognition: Set<String>,
    /** `appIntegrity.appRecognitionVerdict`: `PLAY_RECOGNIZED`, `UNRECOGNIZED_VERSION`, or `UNEVALUATED`. */
    val appRecognition: String?,
    /**
     * `environmentDetails.appAccessRiskVerdict.appsDetected`. Null when app access
     * risk was not evaluated (not opted in on Play Console, or below API 23).
     */
    val appsDetected: Set<String>?,
) {
    public companion object {
        public const val MEETS_DEVICE_INTEGRITY: String = "MEETS_DEVICE_INTEGRITY"
        public const val MEETS_STRONG_INTEGRITY: String = "MEETS_STRONG_INTEGRITY"
        public const val PLAY_RECOGNIZED: String = "PLAY_RECOGNIZED"
        public const val UNRECOGNIZED_VERSION: String = "UNRECOGNIZED_VERSION"
        public const val UNEVALUATED: String = "UNEVALUATED"

        public const val KNOWN_CAPTURING: String = "KNOWN_CAPTURING"
        public const val UNKNOWN_CAPTURING: String = "UNKNOWN_CAPTURING"
        public const val KNOWN_OVERLAYS: String = "KNOWN_OVERLAYS"
        public const val UNKNOWN_OVERLAYS: String = "UNKNOWN_OVERLAYS"
        public const val KNOWN_CONTROLLING: String = "KNOWN_CONTROLLING"
        public const val UNKNOWN_CONTROLLING: String = "UNKNOWN_CONTROLLING"

        /** A healthy verdict, for demos and tests. */
        public val HEALTHY: IntegrityVerdict = IntegrityVerdict(
            deviceRecognition = setOf(MEETS_DEVICE_INTEGRITY),
            appRecognition = PLAY_RECOGNIZED,
            appsDetected = setOf("KNOWN_INSTALLED"),
        )
    }
}

/** Produces a decoded verdict for one request. */
public fun interface IntegrityVerdictSource {
    /**
     * @param requestHash hash bound to this request. The backend must check it
     *   against the token's `requestDetails.requestHash`.
     */
    public suspend fun verdict(requestHash: String): IntegrityVerdict
}

/**
 * Your backend call: sends the token (and the expected request hash) to your
 * server, which decodes it with Google's `decodeIntegrityToken` using a
 * service account and returns the verdict. Tokens cannot be decoded on the device.
 */
public fun interface IntegrityTokenVerifier {
    public suspend fun verify(token: String, requestHash: String): IntegrityVerdict
}

/**
 * Embedded "mock integrity server" for offline demos and tests.
 * **DEMO: not a real integrity check.**
 */
public class FakeIntegrityVerdictSource(
    @Volatile public var verdict: IntegrityVerdict = IntegrityVerdict.HEALTHY,
    @Volatile public var failure: Exception? = null,
) : IntegrityVerdictSource {
    @Volatile public var calls: Int = 0
        private set

    override suspend fun verdict(requestHash: String): IntegrityVerdict {
        calls++
        failure?.let { throw it }
        return verdict
    }
}
