# Using the transaction ledger (`posguard-ledger`)

The ledger makes sure a payment is **never lost and never charged twice**, even if the app crashes or the network drops mid-payment. The design is in [ADR-002](decisions/ADR-002-idempotent-ledger.md).

## Setup

```kotlin
// Once per process, for example in Application.onCreate
val ledger = PosGuardLedger.Builder(context, engineAdapter /* EngineStatusSource */)
    .addListener(telemetry)          // optional: TxnStateChanged events
    .build()
```

`engineAdapter` wraps your certified payment SDK and answers "what happened to the payment with this idempotency key?" (`EngineStatusSource.queryStatus`). If your SDK can't query by key, recovery can't guarantee "never charged twice", and you should document that for your merchants.

## Checkout flow

```kotlin
ledger.recoverPending()                       // 1. on app start: resolve old payments first
val id = ledger.begin(Money(1250, "USD"), Tender.CARD)   // 2. journal + idempotency key
val key = ledger.get(id)!!.idempotencyKey
ledger.markSubmitted(id)                      // 3. just before calling the engine
val result = engine.charge(amount, key)       //    always pass the same key
ledger.markResult(id, result)                 // 4. Authorized / Declined / Unknown
ledger.markCompleted(id)                      // 5. close it out (or markVoided)
```

Rules the ledger enforces:
- Every change is checked against `TxnStateMachine`. A forbidden move throws `IllegalTxnTransitionException` and leaves the journal unchanged.
- A duplicate identical engine response is ignored. A conflicting one throws.
- After `UNKNOWN`, the payment can be resubmitted or voided **only** after recovery got `NotProcessed` from the engine, and only once.
- Writes are atomic (compare-and-set on a version column).

## Recovery outcomes

| Stored state | Engine says Authorized / Declined | NotProcessed | Unreachable |
|---|---|---|---|
| CREATED | → result, `RESOLVED` | stays CREATED, `SAFE_TO_RESUBMIT` | stays CREATED, `UNRESOLVED` |
| SUBMITTED | → result, `RESOLVED` | → UNKNOWN, `SAFE_TO_RESUBMIT` | → UNKNOWN, `UNRESOLVED` |
| UNKNOWN | → result, `RESOLVED` | stays UNKNOWN, `SAFE_TO_RESUBMIT` | stays UNKNOWN, `UNRESOLVED` |

If the status query throws, that counts as `Unreachable`. Recovery never resubmits by itself and never guesses an outcome. While anything is `UNRESOLVED`, don't start new payments for that customer.

## Recovery on reconnect (WorkManager)

Register the factory once, then schedule recovery whenever a payment ends up `UNKNOWN` or the app starts with unresolved payments:

```kotlin
class App : Application(), Configuration.Provider {
    override val workManagerConfiguration
        get() = Configuration.Builder().setWorkerFactory(LedgerWorkerFactory(ledger)).build()
}

LedgerRecovery.schedule(context)   // runs when connected; retries with backoff while UNRESOLVED
```

With a custom `Configuration.Provider`, remove WorkManager's default initializer from your manifest, as the WorkManager docs describe. If you already use other worker factories, add `LedgerWorkerFactory` to a `DelegatingWorkerFactory`.

## What is stored

Only id, idempotency key, amount, currency, tender, state, timestamps, engine reference, and internal bookkeeping (resubmit flag, version). **Never** card numbers, PINs, track data, or CVV. The table has no column for them. The schema is exported to `posguard-ledger/schemas/`. Destructive migrations are never used.
