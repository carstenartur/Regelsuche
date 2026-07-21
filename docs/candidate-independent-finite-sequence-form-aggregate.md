# Candidate-independent finite-sequence candidate-form aggregate

## Purpose

This aggregate combines the two candidate forms frozen for the
`finite-difference-recurrences` challenge:

- `FINITE_DIFFERENCE_POLYNOMIAL`;
- `LINEAR_RECURRENCE`.

It does not select one form after inspecting the holdout and it does not discard
a model merely because another model performs better. Every source evaluation,
including refuted and inconclusive outcomes, remains bound into the aggregate by
its content hash.

## Authoritative command

```bash
./gradlew verifyCandidateIndependentFiniteSequenceFormAggregate
```

The checkout-local task:

1. verifies both source adapters independently;
2. executes each source adapter twice;
3. builds two aggregates from the corresponding clean source runs;
4. requires the aggregates to be byte-identical;
5. validates a strict Draft 2020-12 schema;
6. recomputes every aggregate and source content hash;
7. independently reconstructs both finite-difference and linear-recurrence
   results from the frozen corpus;
8. rejects manipulated refutation removal, missing cases, source substitution,
   unique-continuation claims and premature publication claims.

The implementation is part of root `check` and therefore of `fullCheck`. GitHub
Actions only invokes this repository-owned Gradle contract.

## Source authority

The aggregate is derived exclusively from the two verified adapter run roots.
It cannot replace source outcomes with a hand-written summary: every campaign
and case row retains the source campaign hash, source evaluation hash and
production-evidence hash. The independent verifier also recomputes both model
classes directly from the frozen corpus, so matching source hashes alone are
not sufficient for acceptance.

Completion is deliberately scoped to candidate-form execution for this single
challenge. It does not reinterpret the historical profile, whose
`LINEAR_RECURRENCE=ADAPTER_REQUIRED` value remains the true preregistration
state at freeze time.

## Result

Each of the four configured campaigns evaluates all six frozen cases with both
candidate forms.

| Case | Finite difference | Linear recurrence | Aggregate disposition |
|---|---|---|---|
| `case-07` | confirmed | no unique model | confirmed by finite differences only |
| `case-08` | inconclusive | confirmed `[2]` | confirmed by linear recurrence only |
| `case-09` | inconclusive | confirmed `[-1,1,1]` | confirmed by linear recurrence only |
| `case-10` | inconclusive | confirmed `[1,1]` | confirmed by linear recurrence only |
| `case-11` | confirmed | refuted `[35/9,-49/9,26/9]` | confirmed with a refuted alternative model |
| `case-12` | inconclusive | confirmed `[4,-5,2]` | confirmed by linear recurrence only |

This establishes complete execution coverage for the two preregistered
candidate forms on the six sequence cases:

- six of six cases are confirmed by at least one form;
- one case retains a refuted alternative model;
- zero cases remain unresolved by both available forms.

## Why `case-11` is not collapsed to a simple pass

The observed prefix in `case-11` admits both:

- the cubic finite-difference model, which predicts the frozen continuation;
- a uniquely fitted order-three homogeneous recurrence with coefficients
  `[35/9,-49/9,26/9]`, which predicts `352` instead of the frozen first holdout
  term `350`.

The aggregate records this as
`CONFIRMED_WITH_REFUTED_ALTERNATIVE_MODEL`. It is not a logical contradiction:
the two finite-data model classes make different extrapolation commitments.
Hiding the failed recurrence merely because the polynomial model succeeds would
bias the benchmark and erase useful negative evidence.

## Retained artifacts

```text
build/reports/candidate-independent-finite-sequence-form-aggregate/
  first/run.json
  second/run.json
  verification/verification.json
```

Each case row retains:

- frozen case, split and structural-cluster identities;
- both source evaluation roots and production-evidence roots;
- supporting, refuted and inconclusive candidate forms;
- recurrence coefficients and expected/predicted holdouts;
- an explicit model-outcome-conflict flag;
- proof, novelty, unique-continuation and publication non-claims.

## Claim boundary

A green aggregate establishes complete and reproducible execution of the two
frozen candidate forms for this one challenge. It does not establish:

- a unique infinite continuation from finite sequence data;
- formal proof of a general sequence theorem;
- external mathematical novelty;
- completion of the rational-rewrite or reusable-macro challenges;
- completion of issue #383 as a whole;
- publication readiness.

The top-level status therefore remains
`PARTIAL_OTHER_CHALLENGES_NOT_AGGREGATED`, even though candidate-form coverage
for the finite-sequence challenge is complete.
