# Mock payment engine (`mock-payment-engine`)

> **DEMO — NOT A REAL PAYMENT ENGINE.** It never reads cards, handles PINs, or moves money. It exists for tests and the Sunrise Market demo. Every screen that uses it must show `MockPaymentEngine.LABEL`.

The mock stands in for a certified SoftPOS SDK. It contains a simulated **processor** that remembers each idempotency key it has processed and **never charges a key twice**. It also implements `EngineStatusSource`, so `TransactionLedger.recoverPending()` can look up earlier charges.

## Setup

```kotlin
val engine = MockPaymentEngine.Builder()
    .store(SharedPreferencesMockProcessorStore(context)) // survives app kill (scenario 5)
    .latency(800.milliseconds)                            // feels real in a live demo
    .build()

val ledger = PosGuardLedger.Builder(context, engine).build()
```

The default store is in-memory, which suits tests.

## Scenarios

Set one with `engine.setNextScenario(...)` (used for the next charge only, which suits Demo controls) or `engine.defaultScenario`.

| Scenario | Processor | App receives | What happens next |
|---|---|---|---|
| `APPROVE` | approves | `Authorized` | normal completion |
| `DECLINE` | declines (fake code `05`) | `Declined` | record and close |
| `TIMEOUT` | nothing processed | `Unknown` | recovery → `SAFE_TO_RESUBMIT` → resubmit with the **same key** |
| `NETWORK_DROP_AFTER_SUBMIT` | **approves** | `Unknown`, engine goes offline | recovery is `UNRESOLVED` until `reconnect()`, then `RESOLVED` as authorized (demo scenario 4) |
| `DUPLICATE_RESPONSE` | approves | `Authorized`, plus the same result on `lateResponses` | forward it to `ledger.markResult`, which ignores the repeat |
| `ENGINE_CRASH` | approves | `MockEngineCrashException` | record `Unknown`, and recovery finds the charge |

Demo scenario 5 (the app is killed mid-payment) doesn't need a special scenario. Kill the app after `charge` returns but before `markResult`. On relaunch, `recoverPending()` finds the charge in the persisted processor store.

## Demo helpers

- `disconnect()` / `reconnect()` / `networkAvailable`: simulate connectivity.
- `processedCharges()`: what the processor has charged. Use it to show "charged exactly once" live.
- `reset()`: clears processor memory, restores connectivity, and clears any pending scenario ("Reset demo").

## Tests

`EndToEndScenarioTest` runs the real Room ledger against the mock for every scenario above. Each test asserts that the processor charged at most once.
