# ADR-001: Wrap the certified payment engine, never replace it

- **Status:** Accepted
- **Date:** 2026-11-13

## Context

Card payments on Android depend on certified components: EMV kernels, PIN entry, and card-data encryption. These are validated under PCI programs and card-network certifications. Re-implementing them is costly, legally constrained, and unnecessary for small payment-app makers.

That certification covers the payment kernel. It does not cover the **merchant app around it**. The surrounding app decides when payment acceptance opens. It holds transaction state across crashes and outages, and it sends errors and events to back-office tools. Those areas carry real risk: overlays and screen capture on a compromised phone, lost or duplicated charges after a network drop, and card-like data leaking into logs.

## Decision

posguard works **only around** the certified engine:

- It decides whether to open payment acceptance (preflight verdicts) based on device, app, and screen integrity signals.
- It journals transaction state with idempotency keys so payments are never lost or double-charged.
- It scrubs telemetry before any data leaves the device.
- It integrates with the engine through the engine's public API and results (authorized, declined, unknown, engine reference).

posguard **never** reads cards, processes EMV, handles PINs, encrypts card data, or processes payments. It never stores or transmits card numbers, PINs, track data, or CVV.

## Consequences

- posguard can be adopted next to any certified SDK without affecting that SDK's certification.
- posguard makes no PCI certification or compliance claim. Docs explain the boundary.
- Engine integration goes through adapters. `mock-payment-engine` is the reference adapter for demos and tests.
- Some protections are limited by what the engine exposes. For example, relay-risk flags only use metadata the engine provides.
- Features that would require touching card data or certified internals are out of scope by design.
