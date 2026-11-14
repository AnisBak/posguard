# ADR-002: Idempotent, state-machine transaction ledger

- **Status:** Accepted. Contract in `posguard-core`, implemented in `posguard-ledger` (2026-11-14). See [ledger.md](../ledger.md).
- **Date:** 2026-11-14

## Context

On a phone acting as a payment terminal, the network can drop and the app can crash at any point. The worst moment is just after a payment is handed to the engine. The app doesn't know whether the customer was charged. If it retries naively, it can charge twice. If it gives up, a paid order can be lost.

## Decision

1. **Journal first.** `TransactionLedger.begin()` persists the payment in `CREATED` and creates an **idempotency key** before anything reaches the engine.
2. **One key per payment, forever.** Every submission and status query uses that key. Resubmitting without the same key is not supported by the API.
3. **Explicit state machine.** `TxnStateMachine` in `posguard-core` is the single source of truth:

   ```
   CREATED   → SUBMITTED | VOIDED
   SUBMITTED → AUTHORIZED | DECLINED | UNKNOWN
   UNKNOWN   → AUTHORIZED | DECLINED | SUBMITTED* | VOIDED
   AUTHORIZED→ COMPLETED | VOIDED
   DECLINED  → COMPLETED
   COMPLETED, VOIDED: terminal
   ```
   \* Resubmission with the same key, allowed only after the engine returns `EngineStatus.NotProcessed`.

   Every forbidden transition throws `IllegalTxnTransitionException`. In particular, a `DECLINED` payment can never become `AUTHORIZED`.
4. **Recovery before new payments.** `recoverPending()` runs on app start and on reconnect. For every `CREATED`, `SUBMITTED`, or `UNKNOWN` record, it asks the engine (`EngineStatusSource.queryStatus(key)`):
   - `Authorized` / `Declined` → record the result (`RESOLVED`).
   - `NotProcessed` → `SAFE_TO_RESUBMIT` (same key) or void.
   - `Unreachable` → leave it pending (`UNRESOLVED`). Never guess, and never resubmit.

   `CREATED` is included because the app can crash after handing the payment to the engine but before recording `SUBMITTED`.
5. **Minimal data.** `TransactionRecord` has only id, key, amount, currency, tender, state, timestamps, and engine reference. There is no field for card numbers, PINs, track data, or CVV.
6. **Offline acceptance (store-and-forward) is off by default.** Card-network and processor rules govern it. It will need explicit, limited configuration.

## Consequences

- Double charges are prevented by the engine deduplicating on the key, together with the ledger refusing unsafe retries.
- Engine adapters must support status queries by idempotency key (`EngineStatusSource`). Engines that can't do this limit what recovery can guarantee, and adopters must document that.
- An `UNRESOLVED` transaction can block the next payment until connectivity returns. That's the intended trade-off: correctness over throughput.
