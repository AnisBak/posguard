package dev.posguard.telemetry

/** One redaction rule. Takes text and returns it with sensitive parts replaced. */
public fun interface ScrubRule {
    public fun scrub(input: String): String
}

/**
 * Removes sensitive data from telemetry before it reaches any sink.
 *
 * - [scrub] applies every rule in order to free text.
 * - [scrubAttributes] also scrubs keys, and replaces the whole value of any
 *   key that looks sensitive (see [isSensitiveKey]).
 *
 * It fails closed: if a rule throws, the field becomes [SCRUB_ERROR] instead
 * of being emitted raw.
 *
 * The scrubber is a safety net, not a license. Never put card data,
 * PINs, or personal data in telemetry in the first place.
 */
public class Scrubber(
    private val rules: List<ScrubRule> = ScrubRules.DEFAULT,
    extraSensitiveKeys: Set<String> = emptySet(),
) {
    private val sensitiveTokens: Set<String> =
        DEFAULT_SENSITIVE_KEY_TOKENS + extraSensitiveKeys.map { it.lowercase() }

    /** Scrubs free text. */
    public fun scrub(text: String): String = try {
        rules.fold(text) { acc, rule -> rule.scrub(acc) }
    } catch (_: Exception) {
        SCRUB_ERROR
    }

    /** Scrubs keys and values. Values of sensitive keys become [REDACTED]. */
    public fun scrubAttributes(attributes: Map<String, String>): Map<String, String> =
        attributes.entries.associate { (key, value) ->
            scrub(key) to if (isSensitiveKey(key)) REDACTED else scrub(value)
        }

    /**
     * True when any token of [key] is on the sensitive list. Keys are split on
     * non-alphanumerics and camelCase boundaries, so `cardNumber`, `card_number`,
     * and `CARD-NUMBER` all match `card`, while `shipping` does not match `pin`.
     */
    public fun isSensitiveKey(key: String): Boolean {
        val tokens = tokenize(key)
        return tokens.any { it in sensitiveTokens } || tokens.joinToString("") in sensitiveTokens
    }

    public companion object {
        public const val REDACTED: String = "[REDACTED]"
        public const val SCRUB_ERROR: String = "[SCRUB_ERROR]"

        /** Default key tokens whose values are always redacted. Lowercase. */
        public val DEFAULT_SENSITIVE_KEY_TOKENS: Set<String> = setOf(
            "pan", "card", "cardnumber", "cc", "ccn", "cvv", "cvv2", "cvc", "cvc2", "cid",
            "pin", "pinblock", "track", "track1", "track2", "magstripe", "expiry", "expiration",
            "password", "passwd", "passcode", "secret", "token", "apikey", "auth", "authorization",
            "email", "phone", "mobile", "msisdn", "ssn", "iban", "account", "routing",
        )

        /** Default rules and sensitive keys. */
        public val Default: Scrubber = Scrubber()

        private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
        private val NON_ALNUM = Regex("[^A-Za-z0-9]+")

        private fun tokenize(key: String): List<String> =
            key.split(CAMEL_BOUNDARY).flatMap { it.split(NON_ALNUM) }
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
    }
}

/** Built-in [ScrubRule]s. */
public object ScrubRules {
    public const val TRACK_PLACEHOLDER: String = "[REDACTED_TRACK]"
    public const val PAN_PLACEHOLDER: String = "[REDACTED_PAN]"
    public const val NUMBER_PLACEHOLDER: String = "[REDACTED_NUMBER]"
    public const val EMAIL_PLACEHOLDER: String = "[REDACTED_EMAIL]"
    public const val PHONE_PLACEHOLDER: String = "[REDACTED_PHONE]"

    // Track 1: %B<PAN>^<NAME>^<YYMM><...>?   Track 2: ;<PAN>=<YYMM><...>?
    private val TRACK1 = Regex("""%?B\d{12,19}\^[^^\r\n]{0,26}\^\d{4}[^?\s]*\??""")
    private val TRACK2 = Regex(""";?\d{12,19}=\d{4}[^?\s]*\??""")

    // 13–19 digits, optionally single space/dash separated, not part of a longer digit run.
    private val DIGIT_RUN = Regex("""(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)""")

    // Canonical UUIDs (8-4-4-4-12 hex) are ids, not card or phone numbers. Their fixed shape
    // cannot hold a 13+ digit run, so they are left out of the digit rules.
    private val UUID = Regex("""(?i)(?<![0-9a-z])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-z])""")

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")

    // International: +CC followed by digit groups. NANP: (415) 555-0123, 415.555.0123, 4155550123.
    private val PHONE_INTL = Regex("""(?<![\w+])\+\d{1,3}(?:[ .-]?\(?\d{1,4}\)?){2,5}(?!\d)""")
    private val PHONE_NANP = Regex("""(?<!\d)(?:\(\d{3}\)\s?|\d{3}[ .-]?)\d{3}[ .-]?\d{4}(?!\d)""")

    /** Magnetic-stripe track data (Track 1 and Track 2 shapes). */
    public val TRACK_DATA: ScrubRule = ScrubRule { input ->
        TRACK2.replace(TRACK1.replace(input, TRACK_PLACEHOLDER), TRACK_PLACEHOLDER)
    }

    /**
     * Card-like numbers: 13–19 digits, optionally separated by single spaces or
     * dashes. Luhn-valid runs become [PAN_PLACEHOLDER]. Other runs become
     * [NUMBER_PLACEHOLDER], so a mistyped card number never leaks either.
     *
     * Known false positive: other 13–19 digit values (for example epoch
     * milliseconds) are redacted too.
     */
    public val CARD_NUMBER: ScrubRule = ScrubRule { input ->
        outsideUuids(input) { segment ->
            DIGIT_RUN.replace(segment) { match ->
            val digits = match.value.filter(Char::isDigit)
            if (luhnValid(digits)) PAN_PLACEHOLDER else NUMBER_PLACEHOLDER
            }
        }
    }

    /** Email addresses. */
    public val EMAIL_ADDRESS: ScrubRule = ScrubRule { input -> EMAIL.replace(input, EMAIL_PLACEHOLDER) }

    /**
     * Phone numbers: international (`+` and 8–15 digits) and North American
     * shapes. Known false positive: some 10-digit identifiers are redacted.
     */
    public val PHONE_NUMBER: ScrubRule = ScrubRule { input ->
        outsideUuids(input) { segment ->
            val intl = PHONE_INTL.replace(segment) { m ->
                val count = m.value.count(Char::isDigit)
                if (count in 8..15) PHONE_PLACEHOLDER else m.value
            }
            PHONE_NANP.replace(intl, PHONE_PLACEHOLDER)
        }
    }

    /** Default order: track data first (it contains a PAN), then card numbers, emails, phones. */
    public val DEFAULT: List<ScrubRule> = listOf(TRACK_DATA, CARD_NUMBER, EMAIL_ADDRESS, PHONE_NUMBER)

    /** Applies [transform] to the text between UUIDs, keeping UUIDs unchanged. */
    private inline fun outsideUuids(input: String, transform: (String) -> String): String {
        val out = StringBuilder()
        var last = 0
        for (m in UUID.findAll(input)) {
            out.append(transform(input.substring(last, m.range.first))).append(m.value)
            last = m.range.last + 1
        }
        return out.append(transform(input.substring(last))).toString()
    }

    /** Luhn (mod 10) checksum. */
    public fun luhnValid(digits: String): Boolean {
        if (digits.isEmpty() || !digits.all(Char::isDigit)) return false
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }
}
