# Checkout-owned JMH performance history

Regelsuche retains performance history as immutable, checksum-bound JSON in the checkout. The history renderer and verifier are implemented in Java and characterized with JUnit Jupiter; no Python interpreter, virtual environment or script test runner is involved.

## Reproduce

With Maven:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-quality -am test
```

During the build migration, the equivalent focused Gradle invocation is:

```bash
./gradlew --no-daemon --no-configuration-cache \
  :regelsuche-quality:test \
  :regelsuche-quality:renderJmhHistory
```

The renderer writes:

- `build/reports/quality/jmh-history/history.json` — normalized machine-readable history;
- `build/reports/quality/jmh-history/history.md` — indexed comparison tables;
- `build/reports/quality/jmh-history/charts/*.svg` — one deterministic chart per benchmark.

Every table and chart uses **milliseconds per operation (`ms/op`)**. Lower values and lower points are always faster/better. JMH `scoreError` is retained as an error bar.

## Retained evidence contract

`config/quality/jmh-history-policy.json` lists every accepted snapshot and its SHA-256 identity. Each immutable snapshot under `config/quality/jmh-history/` records:

- an ISO-8601 UTC timestamp and exact source revision;
- the retained artifact identity;
- the JMH/JDK/fork/warm-up/measurement contract;
- the complete declared benchmark inventory with family, unit, score and `scoreError`.

The Java loader fails closed when:

- a snapshot hash changes;
- duplicate JSON keys occur;
- timestamps are not strictly chronological;
- source revisions or labels repeat;
- execution contracts differ;
- a benchmark disappears or appears undeclared;
- a family or unit drifts from the active regression policy;
- a snapshot escapes the repository or is symbolic.

The JUnit suite renders the reports twice and requires byte-identical output. It also executes the retained repository history and requires all 29 declared benchmarks and 29 SVG charts.

## Add a history point

1. Run the complete checkout-owned verification on the exact candidate revision.
2. Retain the complete JMH result and its artifact digest.
3. Add a new chronologically named immutable snapshot; never edit an earlier snapshot.
4. Add the new path and exact SHA-256 to `config/quality/jmh-history-policy.json`.
5. Run the Java/JUnit history contract and the complete repository gate.

A history point is evidence, not a new tolerance. Regression thresholds remain exclusively in `config/quality/jmh-regression-policy-v2.json` and must not be weakened because one runner produced a slower or noisier point.

## Claim boundary

The history compares retained same-contract measurements and supports repository-local trend investigation. It does not establish cross-hardware absolute performance, universal scalability or statistical significance beyond the recorded JMH uncertainty.
