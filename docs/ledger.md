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

**More than one engine** (for example, EBT legs on an EBT-capable engine and card legs on a card engine): pass `combinedStatusSource(cardEngine, ebtEngine)`. Asking only one engine would report "never processed" for the other engine's payments, and recovery could void a charged payment. The combined source says `NotProcessed` only when every engine agrees. A known answer from any engine wins, and any unreachable engine makes the result `Unreachable`.

**Partial approvals:** when the engine approves less than requested (for example, an EBT SNAP balance that's too low), report it as `EngineResult.Authorized(reference, approvedAmount = Money(...))`. The record keeps the requested `amount` and the actual `approvedAmount`. An approved amount above the request or in another currency is rejected. During recovery, such an impossible answer is ignored and the full amount is kept.

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

Only id, idempotency key, amount, currency, tender, state, timestamps, engine reference, **outcome** (`APPROVED`/`DECLINED`, so a COMPLETED record still shows the engine's answer), **approved amount** (only for partial approvals), and internal bookkeeping (resubmit flag, version). **Never** card numbers, PINs, track data, or CVV. The table has no column for them. The schema is exported to `posguard-ledger/schemas/`. Destructive migrations are never used. v1 → v2 added `outcome`: AUTHORIZED and DECLINED rows are backfilled, and COMPLETED rows from v1 keep a null outcome because v1 didn't record it. v2 → v3 added a nullable `approvedAmountCents`, and existing rows keep null (full amount).
