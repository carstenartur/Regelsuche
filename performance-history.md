# Checkout-owned JMH performance history

Regelsuche retains performance history as immutable, checksum-bound JSON in the checkout. The history renderer and verifier are implemented in Java and characterized with JUnit Jupiter; no Python interpreter, virtual environment or script test runner is involved.

## Reproduce

With Maven:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-quality -am test
```

The Maven test phase writes the durable report to:

- `regelsuche-quality/target/reports/quality/jmh-history/history.json`;
- `regelsuche-quality/target/reports/quality/jmh-history/history.md`;
- `regelsuche-quality/target/reports/quality/jmh-history/charts/*.svg`.

During the build migration, the equivalent focused Gradle invocation is:

```bash
./gradlew --no-daemon --no-configuration-cache \
  :regelsuche-quality:test \
  :regelsuche-quality:renderJmhHistory
```

The Gradle adapter writes the same Java-rendered evidence below `build/reports/quality/jmh-history/`. Both paths contain normalized machine-readable history, indexed Markdown comparison tables and one deterministic SVG chart per benchmark.

Every table and chart uses **milliseconds per operation (`ms/op`)**. Lower values and lower points are always faster/better. JMH `scoreError` is retained as an error bar.

### Active regression decision authority

The active checkout-owned `verifyJmhRegression` task now executes the
Java `JmhRegressionV3Main` via a pinned JDK 25 `JavaExec` adapter.
Its source of authority is the Java/JUnit `JmhRegressionV3Verifier`;
the original `scripts/verify-jmh-regression-v3.py` remains a retained
comparison control, **not** the live Gradle decision. The active
six-warmup execution policy is bound by
`config/quality/jmh-regression-policy-more-warmup-v1.json` and
`config/quality/jmh-regression-decision-policy-more-warmup-v1.json`.
The original two-warmup v2/v3 policies remain available for exact historical
replay. Both six-warmup and historical versions retain the same finite v2
benchmark ceilings, and the selected policy is content-bound to its Git blob.

For average-time measurements, v3 records `decisionScore = max(0, currentScore - currentScoreError)` but does not turn uncertainty overlap into a passing result. The outcome is deliberately three-way and fail-closed:

- **PASSED** when the point estimate `currentScore` is at or below `maximumAllowedScore`;
- **INCONCLUSIVE** when the point estimate is above the maximum but `decisionScore` is at or below it, meaning the reported uncertainty overlaps the ratchet boundary; this exits non-zero and requires fresh evidence rather than claiming either a regression or a pass;
- **FAILED** when `decisionScore` is strictly above the maximum, so even the uncertainty-adjusted lower bound exceeds the ratchet. Exact equality at the declared boundary passes.

This keeps the historical v2 verifier and its reports unchanged and reproducible at their original revisions while avoiding both failure modes exposed by noisy shared-runner measurements: a small over-threshold point estimate is no longer mislabeled as a demonstrated regression merely because uncertainty was ignored, but a very wide error bar also cannot make an over-threshold result green.

Measurement precision remains visible independently of the gate outcome. When `currentScoreError >= currentScore`, v3 retains the benchmark as `LOW_PRECISION` in JSON and Markdown evidence. A low-precision row whose point estimate remains below the ratchet can still pass the regression gate, but it is **not** evidence that performance is proven unchanged. If its point estimate exceeds the ratchet it is at least `INCONCLUSIVE`, and therefore fails closed. The rule remains a finite shared-runner ratchet and does not establish cross-hardware absolute performance or statistical significance beyond the recorded JMH uncertainty.

### Java/Maven decision replay and remaining migration

The Java quality module uses `JmhRegressionV3Verifier` and
`JmhRegressionV3Main` for the active regression decision. Maven/JUnit
can independently replay **already produced** results; it does not silently
run JMH, replace the separate producer or reclassify unavailable
infrastructure. For the active six-warmup result and decision policy:

```bash
mvn -pl regelsuche-quality -am \
  -Dregelsuche.jmh.regressionResult=/absolute/path/to/jmh-result.json \
  verify
```

Reports from that optional Maven replay go to
`regelsuche-quality/target/reports/quality/jmh-regression-v3/`.
`PASSED` succeeds; `INCONCLUSIVE`, `FAILED` and malformed evidence
fail closed. The JUnit controls cover all 29 real captured benchmark scores
and uncertainty measurements against the corresponding historical Python
Markdown SHA-256, plus boundary, inventory, drift and policy-substitution
controls. The preceding Python synthetic characterizations remain available
during migration, and the producer still runs through Gradle.

**This is a decision-path migration, not a completed Maven-only lifecycle.**
A complete raw-result parity replay, isolated Maven/Failsafe execution,
remaining authority-bearing host-script removal and fresh clean-checkout plus
pinned-container qualification remain outstanding under #749.

The [finite JMH precision study](jmh-precision-study.md) preregisters a separate,
bounded comparison of execution protocols for issue #981. It leaves this v3
authority and the historical v2/v3 evidence unchanged. A production protocol
may be adopted only after the retained shared-runner measurements, preregistered
criteria and a new versioned execution authority/baseline have been reviewed.

The writer recreates the dedicated chart directory before every run, so removed benchmarks cannot leave stale SVG evidence behind. It also validates all chart filenames before writing and fails closed if two benchmark identities would normalize to the same filename.

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

The JUnit suite renders the reports twice and requires byte-identical output. It also executes the retained repository history and requires all 29 declared benchmarks and 29 SVG charts. The Maven-only evidence contract additionally requires the report to remain below `regelsuche-quality/target/` and verifies the complete durable output there.

## Add a history point

1. Run the complete checkout-owned verification on the exact candidate revision.
2. Retain the complete JMH result and its artifact digest.
3. Add a new chronologically named immutable snapshot; never edit an earlier snapshot.
4. Add the new path and exact SHA-256 to `config/quality/jmh-history-policy.json`.
5. Run the Java/JUnit history contract and the complete repository gate.

A history point is evidence, not a new tolerance. Regression thresholds remain exclusively in `config/quality/jmh-regression-policy-v2.json` and must not be weakened because one runner produced a slower or noisier point.

## Claim boundary

The history compares retained same-contract measurements and supports repository-local trend investigation. It does not establish cross-hardware absolute performance, universal scalability or statistical significance beyond the recorded JMH uncertainty.
