# ADR-003: EBT as ledger legs around a certified engine

- Status: Accepted
- Date: 2026-11-15

## Context

USDA's proposed SNAP EBT card security rule (91 FR 66958, October 2026) would move SNAP to chip cards that also tap, with magnetic-stripe fallback only after a failed chip attempt. Retailers buy their own POS equipment. posguard re-prioritized its roadmap to help small retailers' Android POS apps get ready (see the CHANGELOG).

An EBT sale differs from a card sale in four ways:
1. Only SNAP-eligible items may go on SNAP.
2. A sale is often split across SNAP and another tender.
3. Processors can approve part of the amount.
4. Card entry must try chip before magstripe.

posguard must still never touch card data or PINs (ADR-001).

## Decision

1. **Each tender leg is its own ledger transaction** with its own idempotency key (`Tender.EBT_SNAP`, `EBT_CASH`, `CARD`). The outage-safe rules from ADR-002 then apply to EBT unchanged. A sale-level grouping is left to the app.
2. **Partial approvals are recorded in the ledger** (`approvedAmount`, schema v3) rather than only in the EBT module, so recovery and history show what was actually taken.
3. **The engine reads the card and handles the PIN; posguard decides the next prompt.** `EbtEngine.readCard(mode)` is separate from `purchase()`, so a failed read never reaches the processor and the ledger entry stays `CREATED`, then `VOIDED` if every mode fails.
4. **Chip-first is enforced in config validation and in the state machine.** `EbtConfig` rejects orders where magstripe isn't last. `EbtEntryFlow` only offers magstripe after a chip failure, or when the card has no chip or the reader lacks the SNAP chip app. There is no key-entry mode.
5. **Eligibility is config only, and unknown categories are ineligible.** posguard ships no eligibility list, because rules vary by state and over time.
6. **Recovery across engines uses `combinedStatusSource`.** "Not processed" requires every engine to agree.
7. **Balances go to the receipt only.** They never appear on the POS screen or in telemetry, and `toString` redacts them.

## Consequences

- EBT reuses the ledger's tested recovery instead of adding a second journal.
- Adding `approvedAmount` changed `EngineResult.Authorized`, `EngineStatus.Authorized`, and `TransactionRecord`. This is a pre-1.0 API change, with a non-destructive Room migration.
- Apps must complete legs (`markCompleted`) and group them into a sale themselves.
- Everything is validated against the simulator only. Real EBT processors and state specs may need adapter changes.
