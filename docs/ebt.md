# EBT readiness (`posguard-ebt`, `ebt-simulator`)

> **Simulator only. Not legal or compliance guidance.** posguard has no real EBT processor integration. It never handles card numbers, track data, PINs, or real benefit accounts. Eligibility rules come from your config; posguard ships none.

## Why

SNAP benefits are paid on EBT cards. Most are still magnetic-stripe-only (the proposed rule notes that seven States had issued EBT chip cards as of August 2026), and skimming them is a well-known theft: USDA reports at least **$360 million** in stolen benefits replaced through the second quarter of fiscal year 2025 (753,929 approved claims, $360,441,561 in total replacement value).

On October 23, 2026, USDA's Food and Nutrition Administration (FNA) proposed **"Supplemental Nutrition Assistance Program: Electronic Benefits Transfer Card Security Measures"** (RIN 0584-AE99, 91 FR 66958, document 2026-21658). Comments close **December 22, 2026**. Among other things, it would require States to issue chip cards that also work by tap (NFC) and require SNAP retailers to accept them. Retailers pay for their own POS equipment.

Chip cards only stop skimming if the POS app actually uses the chip. A checkout that drops to swipe too early, or that a thief can push into swiping by damaging the chip, keeps the skimmable path open. Small retailers' existing POS apps also have to add EBT without losing or double-charging a leg during an outage, and without showing balances on screen.

`posguard-ebt` is a **security and reliability layer** an existing Android POS app adds around its certified engine. It has no screens: the app keeps its own UI and engine, and posguard decides what to prompt next, records why, and journals every leg. The core guarantees:

1. **Chip first, enforced.** Magstripe is offered only after a failed chip attempt (or when the card or reader can't use the chip). Config that puts magstripe anywhere but last is rejected. There is no key entry.
2. **Every fallback is explained.** Each fallback emits an event with its reason, so merchants and their tools can see which terminals fall back and why.
3. **No lost or doubled legs.** SNAP, EBT cash, and card legs are separate ledger transactions with their own idempotency keys and the same crash and outage recovery as card payments, including partial approvals.
4. **Balances stay private.** They go on the customer receipt only, never on the POS screen or in telemetry.

Supporting helpers (basket split from your eligibility config, tender planning, receipt fields) save integrators work, but the guarantees above are the point. `ebt-simulator` lets you build and demo all of it without real cards.

### What the proposed rule says (verified against the published text)

Quotes are from the proposed rule as published. A final rule may differ.

| Topic | Proposed text | posguard |
|---|---|---|
| Chip-first fallback | Cards "must not permit a transaction to be conducted via magnetic stripe until there has been at least one failed attempt to conduct the transaction via electronic chip" (Appendix A (p)(4), p. 66991) | `EbtEntryFlow` offers magstripe only after a failed chip attempt. `EbtConfig` rejects any order where magstripe isn't last. |
| Chip app not recognized | When the device recognizes a chip but not the SNAP application, "the POS device must allow the card to proceed with a magnetic stripe transaction" (Appendix A (o)(3)(i), p. 66990) | `EntryFailure.CHIP_APP_NOT_RECOGNIZED` goes straight to magstripe. |
| Insert vs tap order | Not specified | Configurable (`posguard.ebt.entry.order`). |
| Balance on screen | "Balance information shall not be displayed on the screen of the POS terminal except for balance-only inquiry terminals" (7 CFR 274.8(b)(6)(i), p. 66979) | Balances appear only in `EbtReceipt`. `lines(includeBalance = false)` masks them for screens. Telemetry never carries them. |
| Receipt contents | "State the date, merchant's name and location, transaction type, transaction amount and remaining balance for the SNAP account" and identify the account "using a truncated number or coded transaction number" (7 CFR 274.8(b)(7), p. 66979) | `EbtReceipt` has these fields. It prints the engine reference as the coded transaction number, so posguard never needs card digits. |
| PIN | Households "must enter their PIN, regardless of whether they process the transaction by swiping, inserting, or tapping" (Appendix A (o)(3)(iii), p. 66990); POS devices must encrypt PINs and never record them (Appendix A (n)(4)(v)) | PIN entry stays inside the certified engine. `EbtEngine.readCard` covers it, and posguard never sees a PIN. |
| Partial approval | Response code 10, "Partial approval: … the processor only approved a portion of the requested payment" (Appendix A (l)(2), Table 1, p. 66987) | `EbtPurchaseResult.Approved.approvedAmount` and the ledger's `approvedAmount`. `EbtTenderPlanner.afterSnapApproval` moves the shortfall. |
| Split tender | "When a household chooses to pay for its purchase of SNAP-eligible food partly with SNAP benefits and partly through some other means" (definitions, p. 66996) | `BasketSplitter` and `EbtTenderPlanner`. |
| Retailer deadline | Not stated. Card issuance is proposed within two years of a final rule (p. 66961) | — |
| Mobile POS | Some retailers "would be expected to utilize some form of mobile POS device or mobile POS phone/tablet application" (p. 66963) | posguard targets Android POS and phone-based apps. |

Source: [Federal Register document 2026-21658](https://www.federalregister.gov/documents/2026/10/23/2026-21658/supplemental-nutrition-assistance-program-electronic-benefits-transfer-card-security-measures).

## What it does and doesn't do

| posguard does | posguard doesn't |
|---|---|
| Decide the next card-entry prompt (chip first, fallback) and record why | Read cards, or offer key entry (that would mean handling the card number) |
| Run each leg through the outage-safe ledger with its own idempotency key | Collect, see, or store PINs |
| Record partial approvals, emit fallback and partial-approval events | Show balances on the POS screen or send them to telemetry |
| Helper: split a basket into SNAP-eligible and other items, from your config | Decide what is eligible (federal rules, state restrictions, and your catalog do) |
| Helper: plan legs (SNAP, then EBT cash or card for the rest) | Process payments or talk to EBT processors |
| Helper: build receipt fields | Detect physical skimmers, or certify anything. Check your state's EBT processor spec. |

## Wiring

```kotlin
val config = EbtConfig.fromProperties(myEbtProperties)
// One ledger for card and EBT legs. If they go to different engines, recovery must ask both:
val ledger = PosGuardLedger.Builder(context, combinedStatusSource(cardEngine, ebtEngine)).build()
val checkout = EbtCheckout(ledger, ebtEngine, config, listeners = listOf(telemetry))

// After preflight allows the payment:
val split = checkout.split(basketLines)              // item ids, categories, prices, quantities
val legs = EbtTenderPlanner.plan(split, remainderTender = Tender.CARD)
val snap = checkout.payLeg(legs.first()) { prompt -> showPrompt(prompt) }   // "Insert card", "Tap card", ...
when (snap) {
    is EbtLegResult.Approved -> {
        val rest = EbtTenderPlanner.afterSnapApproval(legs.first(), snap.approved, legs.drop(1))
        // Charge `rest` through your card flow, then ledger.markCompleted(snap.txnId) and print EbtReceipt.
    }
    is EbtLegResult.Declined -> askForAnotherTender()
    is EbtLegResult.CardUnreadable -> askForAnotherTender()      // never key entry
    is EbtLegResult.Unknown -> waitForRecovery(snap.txnId)        // same rules as card payments
}
```

`EbtEngine` is the adapter you write around your certified engine: `readCard(mode)` (card read and PIN entry, nothing sent yet), `purchase(request)`, and `queryStatus(key)` for recovery. `ebt-simulator` is a fake implementation for demos and tests.

**Why `combinedStatusSource`:** the ledger recovers pending payments by asking an engine about their idempotency keys. If EBT legs go to an EBT engine and card legs to a card engine, asking only one of them would wrongly report "never processed" for the other's payments, and a charged leg could be voided. `combinedStatusSource` says "not processed" only when every engine agrees.

## Config

```properties
posguard.ebt.jurisdiction=Example State (fictional)   # label only
posguard.ebt.category.produce=eligible
posguard.ebt.category.hot_food=ineligible
posguard.ebt.restricted=soda                           # restricted here, whatever the category says
posguard.ebt.entry.order=chip_insert,chip_tap,magstripe
```

- Unknown keys and bad values are rejected.
- Categories not listed are treated as **ineligible**, with that reason shown, so a catalog gap never puts an item on SNAP by accident.
- Leave `magstripe` out of `entry.order` to turn fallback off. Magstripe can only be last.

The demo's file (`demo-app/src/main/assets/demo-ebt.properties`) is an example for a fictional jurisdiction, not guidance.

## Events

| Event | Telemetry type | Attributes |
|---|---|---|
| `GuardEvent.EbtEntryFallback` | `EBT_ENTRY_FALLBACK` | `txnId`, `from`, `to` (`NONE` when no mode was left), `reason` |
| `GuardEvent.EbtPartialApproval` | `EBT_PARTIAL_APPROVAL` | `txnId` |

Neither includes amounts or balances. Fallback counts by reason (for example `CHIP_APP_NOT_RECOGNIZED`) show which terminals still need the SNAP chip application during a rollout.

## Simulator (`ebt-simulator`)

**Not real.**
- Fictional cards: magstripe-only, chip-only, and chip with contactless. Each has fictional SNAP and cash balances.
- It approves up to the balance: a partial approval when the balance is short, a decline (`SIM-NSF`, a made-up code) when it's empty.
- It deduplicates by idempotency key.
- One-shot scenarios: chip read fails once, reader doesn't recognize the SNAP chip app, and network drops after submit.
- It assumes the engine's PIN entry succeeded. There is no PIN anywhere.

## Limits

- The eligibility config is the integrator's responsibility. posguard enforces only that it is explicit and complete.
- One attempt per entry mode. Real engines may retry a read internally before reporting a failure.
- Balance inquiry, refunds, and voids stay in the engine's own tools.
- No real-device or real-processor testing has been done. This is the simulator stage.

See also [ADR-003](decisions/ADR-003-ebt-split-tender.md), the [threat model](threat-model.md) (T12), and [demo scenario 9](demo-script.md).
