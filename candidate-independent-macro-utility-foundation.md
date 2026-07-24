# Candidate-independent reusable-macro formation and paired utility

## Purpose

This slice continues the frozen `reusable-search-macros` challenge after the
production replay boundary established in PR #459.

It answers two separate questions:

1. Can reusable macro schemas be formed from the four TRAIN replays without
   reading held-out tasks?
2. Under identical held-out search inputs and budgets, does adding only those
   formed macros improve production best-first search?

Replay, generalization, correctness and utility remain separate evidence
surfaces. A reproduced trace does not automatically imply a valid generalized
macro, and a valid macro does not automatically imply held-out utility.

## Target-free formation

`CandidateIndependentMacroFormation` consumes only:

- the four frozen TRAIN replay traces;
- the frozen abstract-operation to production-rule mapping.

It first invokes `CandidateIndependentMacroReplayAdapter`. Replays are then
clustered solely by their frozen abstract operation sequence.

For clusters with multiple examples, the source expressions are structurally
anti-unified. The target schema is not anti-unified from target constants.
Instead, the generalized source pattern is executed symbolically through the
same frozen production operation sequence, and candidate target patterns are
retained only when they instantiate to every TRAIN target and pass deterministic
polynomial-normal-form equivalence.

This rejects the observed overfitting failure in which the two binomial examples
could otherwise yield a numerically fitted but algebraically false target such
as a term containing `3*B-2`.

The current TRAIN evidence forms three macros:

| Supporting traces | Formed schema |
|---|---|
| `case-13-trace-1`, `case-13-trace-2` | `(A+B)^2 -> A^2 + 2*A*B + B^2` |
| `case-14-trace-1` | `A+A-A -> A` |
| `case-14-trace-2` | `A*1+0 -> A` |

The binomial matcher also treats subtraction as addition of a negated right
operand, allowing the TRAIN-derived `(A+B)^2` schema to apply to `(a-4)^2`
without reading its held-out target.

## Paired production search

`CandidateIndependentMacroUtilityEvaluator` uses the production
`BestFirstSearchStrategy.searchWithDiagnostics` for both runs.

For each frozen task, baseline and macro-enabled search have identical:

- source and target;
- assumptions;
- primitive production inventory;
- scoring and canonicalization;
- best-first strategy;
- depth, state and candidate bounds.

The macro-enabled run differs only by the formed macro transformations.
Every generated edge is filtered by declared-assumption coverage and then
independently checked through deterministic polynomial equivalence or exact
assumption-aware rational-function equivalence.

Equality-assumption tasks whose source already satisfies the target are retained
as zero-step paired successes rather than being rewritten into artificial search
work.

## Measured twelve-task frontier

The focused production characterization currently requires exactly:

- **2 `IMPROVED`** paired evaluations;
- **0 `REACHABILITY_GAIN`** evaluations;
- **6 `NO_IMPROVEMENT`** evaluations;
- **4 `NO_RESULT`** evaluations;
- **0 `CORRECTNESS_REGRESSION`** evaluations.

The two improvements are the held-out binomial expansion tasks. The formed macro
reaches each target as one macro step with fewer explored states than the paired
primitive baseline.

The normalization, distribute/collect and equality-substitution families are
successful but show no additional macro benefit under the frozen budgets. The
cross-family rational and guarded partial-fraction families remain honest null
results for both runs.

## Verification

```bash
./gradlew :app:test \
  --tests de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapterTest
```

The test requires:

- all four TRAIN replays;
- exactly three validated macro schemas;
- the exact non-overfitted binomial schema;
- production best-first diagnostics;
- all twelve paired tasks;
- the exact `2/0/6/4/0` outcome accounting;
- no correctness regression and no search-budget overrun.

Root `check` and `fullCheck` execute the same ordinary JUnit contract.

## Claim boundary

This is a production characterization foundation, not yet the canonical
four-campaign JSON benchmark run. It does not establish:

- that every valid primitive composition can be generalized;
- universal macro utility;
- superiority on the rational cross-family tasks;
- amortized end-to-end break-even;
- formal proof or external mathematical novelty;
- completion of issue #383 or publication readiness.

The next slice must materialize four deterministic campaigns, retain every raw
formation and paired-evaluation row, validate a strict schema, independently
recompute outcome accounting and reproduce the batch twice byte-for-byte.
