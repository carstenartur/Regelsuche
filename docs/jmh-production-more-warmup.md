# Production JMH latency protocol: more warmup v1

## Decision and failure addressed

Issue #981 already compared seven protocols in a finite three-replica study.
Main commit `4ac122f3b012d12529dd10be907e5236d7615d84`, CI run `35417755959`
attempt 1, then failed closed with INCONCLUSIVE for `preparedTargetedSearch`:
`0.797142255 ms/op` with error `7.097983189`, above the unchanged ceiling
`0.729862817`. Its measurement iterations decreased from approximately 1.234
to 0.670 to 0.487 ms/op. This is not evidence of an established regression;
it shows why the existing two-warmup measurement is inadequate for this decision.
The original failed artifact remains evidence, regardless of the diagnostic rerun.

We choose **more-warmup: six warmups / three measurements / one fork**, with
one second for each warmup and measurement iteration. In the retained v2 study
it had zero LOW_PRECISION rows among 87 observations, median relative error
0.080096, p90 0.201663 and median CI cost 1.749 times the control. The
more-measurements alternative also had zero LOW_PRECISION rows and a better
median relative error, but cost 1.936 times the control with p90 0.241444.
This choice prioritizes zero observed LOW_PRECISION with lower CI cost and p90;
it is an explicit engineering choice, not an automatic winner of the study.
See [the retained study outcome](jmh-precision-study-v2-outcome.md).

## Migration boundary

`config/quality/jmh-regression-policy-more-warmup-v1.json` is the execution
source for `:app:jmh`. Its `executionRevision` is
`regelsuche.jmh-latency-execution/more-warmup-v1`. The existing v2 threshold
schema and v3 decision algorithm are reused in separately named policy
instances; no new statistical decision algorithm is introduced.

Every benchmark, unit, family, baseline score/error, multiplier and maximum
is copied unchanged. `baselineExecution` explicitly retains the two-warmup
protocol that produced those historical reference measurements. They are
**historical ceiling references, not newly measured six-warmup baselines**.
The separately named v3 decision policy binds the current threshold bytes by
Git blob SHA-1. INCONCLUSIVE still exits nonzero, as does a precise regression.
No retries, filtering of unfavourable results or threshold relaxation is added.

The publication task explicitly passes `jmh-baseline-more-warmup-v1.json`.
Its older publication ceilings also stay unchanged; `baselineMeasurementPolicy`
preserves their original measurement context. Publication continues to check
both iteration counts and durations. The default standalone publication
verifier and the historical v2/v3 regression policy files are unchanged.

Allocation profiling and SymPy use their existing, separate protocols.
Historical charts continue to read the frozen historical policy and snapshots;
new measurements are not inserted into that two-warmup history. Quality
acceptance collection retains both old and current policy instances.

## Verification

```bash
python3 -B scripts/test-jmh-more-warmup-policy.py
python3 -B scripts/test-verify-jmh-regression-v3.py --verifier scripts/verify-jmh-regression-v3.py
python3 -B scripts/test-quality-acceptance-evidence.py
bash gradle/run-isolated-jmh-authority.sh
```

The migration controls use synthetic measurements. They check unchanged
ceilings, correct content binding, six-warmup acceptance, two-warmup rejection
by the current contract, historical replay, wrong-unit/duration rejection,
precise regression failure and fail-closed INCONCLUSIVE. Synthetic controls do
not demonstrate actual precision or performance. Current-head CI must execute
and retain the complete benchmark inventory before this migration is accepted.
