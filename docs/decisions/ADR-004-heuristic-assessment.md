# ADR-004: Text-heuristic assessment with pluggable rules

- Status: Accepted
- Date: 2026-11-15

## Context

posguard's main audience is teams maintaining existing Android POS apps, often vendor-built and old. They need to know where their app is weak around the certified payment engine and what to fix first, without a rewrite. CLAUDE.md §11 asks for a CLI and Gradle plugin that rank findings by security risk × ease of fix and map them to OWASP MASVS.

Constraints:
- **No new dependencies** without approval. A real Kotlin/Java parser (the Kotlin compiler's PSI, or a Java parser library) would be a large new dependency.
- **Open core.** Private rule packs and tuned thresholds must plug in without forking.
- **Reports may be shared publicly.** They must not leak secrets or card data from the scanned code.
- Legacy codebases often don't build cleanly, so the tool shouldn't need to compile them.

## Decision

1. **Scan source text with heuristics.** Comment-stripped lines, regular expressions, and identifier-word matching (`cardNumber` → card, number). There is no type resolution and no build. Every rule's explanation says it is a heuristic.
2. **One rule interface, discovered with `ServiceLoader`.** `AssessRule` declares its id, MASVS group, default risk and ease, explanation, fix, posguard module, and options. Built-in rules come first, then plugged-in rules.
3. **Config from outside the library.** A properties file can turn rules off, override risk and ease, and set rule options. Unknown keys, rules, and options are errors, so a typo can't silently disable a check.
4. **Ranking is risk weight × ease weight** (3/2/1 each), with deterministic tie-breaks, so reports are stable and diffable.
5. **Reports never quote scanned values.** Rule details may name identifiers but never string literals. There are no absolute paths or timestamps.
6. **A library core with two front ends.** A JVM library (`posguard-assess`) with a CLI, and a cacheable Gradle task (`posguard-assess-gradle`) built on Gradle's own plugin tooling.
7. **A fictional sample app is the end-to-end fixture.** `samples/legacy-pos-sample` triggers every rule, and its report is committed and checked by a test.

## Consequences

- No new dependencies, and it works on code that doesn't compile.
- False positives and negatives are expected (for example, protections set in a base class). Teams can turn rules off or re-rank them in config, and the limits are documented in `docs/assess.md`.
- Scanning this repository found false positives (data classes named `*Screen`, enum entries mentioning magstripe, regexes inside string literals). The heuristics were tightened, and posguard's own modules and demo app now report no findings.
- A parser-based engine could later sit behind the same `AssessRule` interface if precision becomes more important than zero dependencies.
- The Gradle plugin isn't published yet. Publishing comes with the Maven Central work in Milestone 4.
