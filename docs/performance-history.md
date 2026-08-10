# Checkout-owned JMH performance history

Regelsuche keeps performance history as immutable, checksum-bound JSON in the
checkout. GitHub Actions does not own the baseline, chart state or regression
decision; it only invokes the same Gradle tasks that a local checkout can run.

## Reproduce

```bash
./gradlew --no-configuration-cache renderJmhHistory
```

The task first runs the renderer characterization suite and then writes:

- `build/reports/quality/jmh-history/history.json` — normalized machine-readable
  history;
- `build/reports/quality/jmh-history/history.md` — indexed comparison tables;
- `build/reports/quality/jmh-history/charts/*.svg` — one deterministic chart per
  benchmark.

Every table and chart uses **milliseconds per operation (`ms/op`)**. Lower
values and lower points are always faster/better. JMH `scoreError` is retained
and rendered as an error bar.

## Retained evidence contract

`config/quality/jmh-history-policy.json` lists every accepted snapshot and its
SHA-256 identity. Each snapshot under `config/quality/jmh-history/` records:

- an ISO-8601 UTC timestamp and exact 40-character source revision;
- the retained CI or baseline artifact identity;
- the JMH/JDK/fork/warm-up/measurement contract;
- the complete declared benchmark inventory with family, unit, score and
  `scoreError`.

The renderer fails closed when a snapshot hash changes, timestamps are not
strictly chronological, source revisions repeat, execution contracts differ,
a benchmark disappears or appears undeclared, or a family/unit drifts from the
active regression policy.

## Add a new history point

1. Run the complete checkout-owned `ciCheck` on the exact candidate revision.
2. Retain the complete verification artifact and its content digest.
3. Normalize the successful `app/build/reports/jmh/result.json` into a new,
   chronologically named snapshot. Never edit an existing snapshot.
4. Add the snapshot path and exact SHA-256 to
   `config/quality/jmh-history-policy.json`.
5. Run `renderJmhHistory` twice and verify byte-identical outputs.
6. Run the complete `ciCheck` before merge.

A historical point is evidence, not a new tolerance. Regression thresholds
remain exclusively in `config/quality/jmh-regression-policy-v2.json` and may not
be weakened merely because a noisy runner produced a slower point.

## Claim boundary

The history compares same-contract measurements retained by this repository.
It can reveal local trends and support investigation, but it does not establish
cross-hardware absolute performance, universal scalability or statistical
significance beyond the recorded JMH uncertainty.
