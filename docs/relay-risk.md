# Relay-risk flags (experimental)

> **Experimental.** These rules are hints, not proof. Expect false positives. posguard **never declines or reverses a payment** on relay risk. It flags an already-approved payment so the merchant can check before handing over goods. `GuardPolicy` caps `RELAY_RISK_PATTERN` at WARN and rejects BLOCK.

## What a relay attack is

In a relay attack, a criminal near the victim reads their card or phone wallet, and an accomplice at the shop "taps" a device that forwards that read to the terminal in real time. The payment looks like an ordinary contactless tap. Relays often run several small payments in a row, under the amount where a PIN would be asked for.

Real relay defenses live inside the card, the certified payment kernel, and the card network (for example, distance-bounding and network fraud scoring). posguard can't see inside those. It can only look at **metadata your payment engine already exposes** about authorized payments, and point out patterns worth a second look.

## Wiring

```kotlin
val relay = RelayRiskMonitor(
    config = RelayRiskConfig.fromProperties(myProps),   // or RelayRiskConfig()
    listeners = listOf(telemetry),                     // RelayRiskFlagged / RelayRiskReviewed events
)
val guard = PosGuard.Builder(ledger)
    .signals(relay.signal)   // preflight WARNs while a flagged payment awaits review
    .build()

// After the engine authorizes:
ledger.markResult(id, EngineResult.Authorized(ref))
when (val r = relay.record(TapMetadata(id, clock.instant(), amount, entryMode, cardReadMillis))) {
    RelayRiskAssessment.Clear -> ledger.markCompleted(id)
    is RelayRiskAssessment.Flagged -> showHoldForReview(r.hits)   // keep AUTHORIZED; never decline
}
// When the merchant has checked:
relay.markReviewed(id); ledger.markCompleted(id)
```

Your adapter fills `TapMetadata` from what the engine reports. It needs the entry mode (physical card tap, phone-wallet tap, other) and, if available, the card read time. **Never** pass card numbers, EMV tags, card fingerprints, or tokens. The rules don't need them, and `TapMetadata` has no field for them. The demo's `CheckoutController.tapMetadata()` is an example mapping.

## Built-in rules

The defaults are documented starting points, **not tuned values**. Only the first rule is on by default.

| Rule id | Fires when | Default | Notes |
|---|---|---|---|
| `relay.wallet_burst` | At least N phone-wallet taps within T, counting the current one | **On**, N = 5, T = 60 s | Busy shops with many wallet customers will see false positives. Raise N or turn it off. |
| `relay.near_cvm_limit_burst` | At least N contactless taps within T, each within X% below the no-CVM (no-PIN) limit | **Off** until you set the limit | Limits vary by country, card network, and merchant category, so posguard ships none. |
| `relay.slow_card_read` | The engine reports a card read of at least the threshold | **Off** until you set a threshold | Weak evidence: slow phones, worn cards, and busy engines are slow too. |

Properties for `RelayRiskConfig.fromProperties` (unknown keys and bad values are rejected):

```properties
posguard.relay.wallet_burst.enabled=true
posguard.relay.wallet_burst.count=5
posguard.relay.wallet_burst.window_seconds=60
posguard.relay.near_cvm_limit.limit_cents=…     # set together with .currency to turn the rule on
posguard.relay.near_cvm_limit.currency=…
posguard.relay.near_cvm_limit.percent=10
posguard.relay.near_cvm_limit.count=3
posguard.relay.near_cvm_limit.window_seconds=60
posguard.relay.slow_card_read.millis=…           # turns the rule on
posguard.relay.history.size=50
posguard.relay.history.retention_seconds=600
```

## Your own rules

Implement `RelayRiskRule` and pass it to the monitor. Tuned rules from a private product plug in the same way, without forking:

```kotlin
class MyRule : RelayRiskRule {
    override val id = "acme.my_rule"
    override fun evaluate(current: TapMetadata, recent: List<TapMetadata>): RuleHit? = …
}
RelayRiskMonitor(config, rules = config.defaultRules() + MyRule())
```

A rule that throws is skipped, and the other rules still run.

## Limits

- The monitor sees **only this device's** payments, and only metadata the engine exposes.
- History and the review queue are **in memory**. After a process restart the window starts fresh, and the preflight warning for an unreviewed payment is gone. The ledger still holds the payment in `AUTHORIZED`, so the app can list unfinished reviews from the ledger.
- It can't tell a relay from a legitimate rush of wallet customers. That's why it holds payments for a person to review and never declines.

See also the [threat model](threat-model.md) (T11) and [policy defaults](policy-defaults.md).
