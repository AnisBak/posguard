# Demo script: Sunrise Market (about 2 minutes, plus scenario 9 for EBT audiences)

> **DEMO — NOT A REAL PAYMENT.** Everything runs offline on the phone: a mock payment engine, a mock integrity server, and simulated threats. No card data is used.

**Before you start:** install the debug build, open **Sunrise Market**, long-press the ☀ logo, then tap **Reset demo**. Keep Demo controls one long-press away.

---

**Opening (15 s).**
"Phones are becoming payment terminals for small shops. The certified payment engine protects the card. Nothing protects the *app around it*: what's on screen, what happens when the network or the app fails mid-payment, and how the checkout handles new requirements like chip and tap EBT cards. posguard is an open-source toolkit for that layer."

*For grocers, SNAP retailers, or state agencies, open with scenario 9 (EBT), then show 4 (network drop) and 5 (crash).*

**1 · Normal payment (20 s).** Add a coffee and a bagel, then tap **Take payment**.
"Before the payment screen opens, posguard runs its integrity checks in a fraction of a second. All clear, so the secure payment screen opens. Screenshots are blocked here." Tap **Tap card (simulated)**. Show **Approved · Processor charged 1×**, then tap **Done**.

**2 · Overlay app (20 s).** Long-press the logo, tap **2 · Overlay app present**, and close the dialog. Tap **Take payment**.
"A malicious app is drawing over the screen, the classic way to fake an amount or capture taps. The payment screen refuses to open, and it says why in plain English: `SCREEN_OVERLAY_PRESENT`." Point at "Simulated for this demo". Tap **Back to shop**.

**3 · Screen recording (15 s).** In Demo controls, tap **Normal** (this clears the threats), then **3 · Screen recording active**. Tap **Take payment**.
"Someone is recording the screen. Blocked: `SCREEN_CAPTURE_ACTIVE`. On Android 15 and later, posguard detects real recordings too." Tap **Back to shop**, then **Normal** in Demo controls.

**4 · Network drops after submit (30 s).** In Demo controls, tap **4 · Network drops after submit**. Take payment, then tap card.
"The engine charged the card, but the connection dropped before the answer came back. Most apps either retry, which can charge twice, or give up, which loses the sale. posguard does neither. It journaled the payment with an idempotency key, it **won't retry blindly**, and new payments are paused." Tap **Reconnect**.
"Back online, it asks the engine about *that key*. Approved, and the processor charged exactly **once**."

**5 · App crashes mid-payment (25 s).** In Demo controls, tap **5 · App crashes mid-payment**. Take payment, then tap card. The app closes for real. Reopen it.
"The app was killed right after the engine charged the card, before the result was saved. On relaunch, posguard finds the unfinished payment in its journal and resolves it with the engine." Point at the banner **Recovered $5.75 payment: approved, charged 1×**, then at **History**.

**7 · Burst of wallet taps (20 s, optional).** In Demo controls, tap **7 · Burst of wallet taps**. The banner says two phone-wallet taps were just processed. Take payment, then tap card.
"Three wallet taps in a row is a pattern linked to relay fraud, where a criminal forwards someone else's card or phone to this terminal. posguard's experimental check doesn't decline anything. The payment is approved but **held for review**, so the merchant can check before handing over goods." Tap **Reviewed, complete sale**.

**9 · EBT purchase (30 s).** In Demo controls, tap **9 · EBT purchase (chip fails → tap, partial)**. A basket loads: bananas, juice, soda, dish soap, and hot coffee. Take payment, then tap **Pay with EBT**.
"Under USDA's proposed rule, SNAP retailers will need to accept chip and tap EBT cards. Here posguard splits the basket: food goes on SNAP, while the soda (restricted in this example state), the soap, and the hot coffee don't." Tap **Charge $7.97 to SNAP**. "The chip read fails, so posguard asks for a tap. It never jumps straight to the stripe that skimmers copy. The PIN stays inside the certified engine." The card has only $5.00 left: "Partial approval: posguard moves the $2.97 shortfall to the card." Tap **Charge $11.71 to card**. "The receipt has the required fields, and the balance goes on the customer's receipt, not on this screen."

**Close (15 s).** Open Demo controls, tap **Log an error containing a test card number**, and show `[REDACTED_PAN]` in the telemetry panel.
"Every event is scrubbed before it leaves the device. posguard is Apache-2.0, works with any certified engine, and doesn't touch card data or claim PCI certification."

---

**Extra scenarios** (also in Demo controls): card declined, payment-engine crash, engine timeout (resubmit with the same key), duplicate engine response, Play Integrity device failure.

**Notes for presenters**
- Overlay and recording are *simulated* so the demo works anywhere. The real detectors run too ([integrity.md](integrity.md)).
- The demo policy (`demo-app/src/main/assets/demo-policy.properties`) lists emulator, debug, and unavailable checks as "Noted" instead of warning, so scenario 1 shows Allow on any device. Library defaults are stricter ([policy-defaults.md](policy-defaults.md)).
- Scenario 7 uses demo-sized thresholds (`demo-app/src/main/assets/demo-relay.properties`: 3 wallet taps in 2 minutes). The library default is 5 in 60 seconds ([relay-risk.md](relay-risk.md)). "Review later" leaves the payment held. The next **Take payment** then shows a warning until it's marked reviewed in Demo controls.
- Scenario 9 uses an example eligibility config for a fictional jurisdiction (`demo-app/src/main/assets/demo-ebt.properties`), not legal guidance. The EBT processor is the `ebt-simulator`, with fictional cards and balances. Extras in Demo controls: **EBT: magstripe-only card** (falls back to swipe because the card has no chip), **EBT: reader lacks SNAP chip app** (goes straight to swipe, as the proposed rule requires), and **EBT: no SNAP balance** (declined, nothing taken). EBT scenarios last for one EBT sale.
- **Reset demo** never deletes the payment journal. Unfinished payments are always recovered.
