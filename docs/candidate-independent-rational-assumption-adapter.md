# Candidate-independent rational-assumption adapter execution

## Purpose

This execution applies the target-free rational candidate-formation component
to the frozen `rational-assumption-rewrites` challenge and retains the complete
held-out task matrix.

The exact rational-function evaluator and the discovery adapter have different
roles:

- the evaluator decides whether a supplied identity is valid under supplied
  pole conditions;
- the adapter must form a reusable operation from TRAIN inputs without seeing
  targets and must then reach held-out targets through its selected operation
  and the frozen primitive inventory.

A valid identity is therefore not automatically a successful discovery result.

## Authoritative command

```bash
./gradlew verifyCandidateIndependentRationalAssumptionAdapter
```

The checkout-local task:

1. validates the frozen benchmark source, budgets, case corpus, primitive
   profile and freeze receipt;
2. executes four deterministic campaigns;
3. forms the candidate from the four TRAIN seed expressions without target
   fields;
4. evaluates all twelve tasks in every campaign;
5. runs the complete batch twice and requires byte-identical JSON;
6. validates a strict Draft 2020-12 schema and every nested content hash;
7. independently parses and evaluates rational expressions with Python exact
   fractions and polynomial dictionaries;
8. independently reconstructs bounded reachability using only the selected
   cancellation form, explicit square-difference factoring and division by one;
9. rejects held-out leakage, hidden null results, undeclared rules, missing
   rows, unbalanced resources and publication/proof overclaims.

The task is part of root `check` and therefore of `fullCheck`. GitHub Actions
only invokes the repository-owned Gradle contract.

## Frozen budgets

The preregistered per-campaign limits are:

| Resource | Frozen limit |
|---|---:|
| explored states | 3,000 |
| candidate evaluations | 600 |
| proof attempts | 100 |

The adapter partitions the search resources identically in every campaign:

- target-free formation: 60 states and 60 candidate evaluations;
- each of twelve task evaluations: 245 states and 45 candidate evaluations.

This sums exactly to the frozen 3,000-state and 600-evaluation limits. Formal
proof is outside this adapter slice, so all 100 configured proof attempts remain
explicitly unexecuted.

## Formed candidate

All four TRAIN seeds support the same generic component:

```text
ASSUMPTION_SENSITIVE_FACTOR_CANCELLATION
```

implemented by the assumption-aware
`RationalNormalizationHypothesisOperator`.

Formation receives only:

- seed identity;
- source expression;
- declared assumptions;
- source reference.

There is no target field. A seed supports selection only when the operator
produces a strictly smaller expression and the independent exact evaluator
confirms the transformation under the seed assumptions.

The held-out search inventory contains exactly:

```text
hypothesis_rational_normalization
ast_square_difference_factor
ast_divide_one
```

Partial-fraction and nested-division operators are intentionally absent, even
if other project surfaces contain related transformations.

## Measured frontier

Every campaign contains twelve task rows. The expected and retained result is:

| Case family | Tasks per campaign | Result |
|---|---:|---|
| direct factor cancellation (`case-01`) | 2 | reached and confirmed |
| affine factor cancellation (`case-02`) | 2 | reached and confirmed |
| literal difference of squares (`case-03`) | 2 | no result |
| partial fractions (`case-04`) | 2 | no result |
| nested division (`case-05`) | 2 | no result |
| parameterized difference of squares (`case-06`) | 2 | reached and confirmed |

Across four campaigns:

- 24 task evaluations are reached and independently confirmed;
- 24 task evaluations retain `NO_RESULT`;
- zero tasks exhaust their budgets;
- zero reached targets are refuted;
- zero correctness regressions are hidden.

## Why `case-03` remains a no-result

The frozen primitive `ast_square_difference_factor` matches two explicit square
AST nodes. It handles

```text
x^2 - a^2
```

but not the literal presentation

```text
x^2 - 1
```

because `1` is not represented as `1^2` in that AST. The exact evaluator can
confirm the supplied identity, but the restricted search inventory does not
construct the needed factorization. The batch therefore retains the gap rather
than adding a post-hoc special rule after seeing the VALIDATION target.

## Why partial fractions and nested division remain no-results

Those forms were not selected from the TRAIN observations. Enabling existing
project transformations for them during held-out evaluation would violate the
candidate-independent protocol by expanding the candidate language after
observing the held-out task families.

The no-results establish the actual frontier of the selected form. They are not
errors and are not removed from aggregate counts.

## Retained artifacts

```text
build/reports/candidate-independent-rational-assumption-adapter/
  first/run.json
  second/run.json
  verification/verification.json
```

Every task row retains:

- frozen case, split, structural cluster and task identity;
- source, target and assumptions read only during evaluation;
- complete ordered rewrite steps;
- generated and required non-zero conditions per step;
- exact cross-multiplied polynomial normal forms;
- balanced state and candidate-evaluation resources;
- terminal reason and explicit proof, novelty and publication non-claims.

## Claim boundary

A green run establishes reproducible target-free formation and a measured
held-out reachability frontier for this one challenge. It does not establish:

- that every valid rational identity is discoverable by the selected form;
- that the selected form is the uniquely correct generalization;
- a formal theorem proof;
- external mathematical novelty;
- completion of the reusable-macro challenge;
- completion of issue #383 or publication readiness.
