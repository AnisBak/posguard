package dev.posguard.integrity.relay

import dev.posguard.core.ledger.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RelayRiskConfigTest {

    private fun props(vararg pairs: Pair<String, String>) = Properties().apply { pairs.forEach { setProperty(it.first, it.second) } }

    @Test
    fun `defaults turn on only the wallet burst rule`() {
        val rules = RelayRiskConfig().defaultRules()
        assertEquals(listOf(WalletBurstRule.ID), rules.map { it.id })
    }

    @Test
    fun `empty properties give the defaults`() {
        assertEquals(RelayRiskConfig(), RelayRiskConfig.fromProperties(props("other.key" to "x")))
    }

    @Test
    fun `properties override every setting and turn rules on`() {
        val config = RelayRiskConfig.fromProperties(
            props(
                "posguard.relay.wallet_burst.count" to "3",
                "posguard.relay.wallet_burst.window_seconds" to "120",
                "posguard.relay.near_cvm_limit.limit_cents" to "10000",
                "posguard.relay.near_cvm_limit.currency" to "USD",
                "posguard.relay.near_cvm_limit.percent" to "5",
                "posguard.relay.near_cvm_limit.count" to "4",
                "posguard.relay.near_cvm_limit.window_seconds" to "90",
                "posguard.relay.slow_card_read.millis" to " 1500 ",
                "posguard.relay.history.size" to "20",
                "posguard.relay.history.retention_seconds" to "300",
            ),
        )
        assertEquals(3, config.walletBurstCount)
        assertEquals(120.seconds, config.walletBurstWindow)
        assertEquals(Money(10_000, "USD"), config.noCvmLimit)
        assertEquals(5, config.nearCvmLimitPercent)
        assertEquals(4, config.nearCvmLimitCount)
        assertEquals(90.seconds, config.nearCvmLimitWindow)
        assertEquals(1500.milliseconds, config.slowCardRead)
        assertEquals(20, config.historySize)
        assertEquals(300.seconds, config.historyRetention)
        assertEquals(
            listOf(WalletBurstRule.ID, NearCvmLimitBurstRule.ID, SlowCardReadRule.ID),
            config.defaultRules().map { it.id },
        )
    }

    @Test
    fun `wallet burst can be turned off`() {
        val config = RelayRiskConfig.fromProperties(props("posguard.relay.wallet_burst.enabled" to "FALSE"))
        assertTrue(config.defaultRules().isEmpty())
    }

    @Test
    fun `strict loader rejects unknown keys and bad values`() {
        listOf(
            props("posguard.relay.wallet_burst.cuont" to "3"),
            props("posguard.relay.wallet_burst.count" to "three"),
            props("posguard.relay.wallet_burst.count" to "1"),
            props("posguard.relay.wallet_burst.enabled" to "yes"),
            props("posguard.relay.near_cvm_limit.limit_cents" to "10000"),
            props("posguard.relay.near_cvm_limit.currency" to "usd", "posguard.relay.near_cvm_limit.limit_cents" to "100"),
            props("posguard.relay.slow_card_read.millis" to "0"),
            props("posguard.relay.history.retention_seconds" to "30"), // shorter than the 60 s burst window
            props("posguard.relay.history.size" to "99999999999"),
        ).forEach { p ->
            assertThrows(p.toString(), IllegalArgumentException::class.java) { RelayRiskConfig.fromProperties(p) }
        }
    }
}
