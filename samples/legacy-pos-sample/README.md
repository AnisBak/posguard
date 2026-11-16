# legacy-pos-sample: deliberately insecure. Do not copy.

> **Fictional and deliberately insecure.** This is a small, made-up "Corner Deli" POS app written the way older Android POS apps often are, so `posguard-assess` has something to find. It isn't a Gradle module, it doesn't build, and it contains no card numbers, keys, or real endpoints (`example.com` only). **Never copy this code.**

Every built-in rule fires at least once. The committed report is [docs/examples/assess-legacy-pos-sample.md](../../docs/examples/assess-legacy-pos-sample.md), and a test fails if they drift apart.

```sh
./gradlew :posguard-assess:run --args="samples/legacy-pos-sample --out build/assess/legacy.md --html build/assess/legacy.html"
```
