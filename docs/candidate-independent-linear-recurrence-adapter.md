# Candidate-independent linear-recurrence adapter execution

## Scope

This execution slice continues issue #383 with the second candidate form
frozen for the `finite-difference-recurrences` challenge:

```text
a_n = c_1 a_(n-1) + ... + c_k a_(n-k)
```

The historical profile remains immutable and still records
`LINEAR_RECURRENCE` as `ADAPTER_REQUIRED`, which was true when the corpus was
frozen. The new run records the later implementation separately as
`AVAILABLE_AFTER_FREEZE`; it does not rewrite the preregistration.

## Authoritative commands

```bash
./gradlew verifyCandidateIndependentLinearRecurrenceAdapter
```

The task is part of root `check` and therefore `fullCheck`. It:

1. executes four configured campaigns twice;
2. requires the two complete run files to be byte-identical;
3. validates the strict Draft 2020-12 run schema;
4. recomputes all retained content hashes;
5. reconstructs campaign identities and seeds;
6. independently solves every frozen recurrence system in Python using exact
   `Fraction` arithmetic and Gaussian elimination;
7. verifies TRAIN-only formation and evaluator-only holdout access;
8. rejects manipulated leakage, missing rows, hidden refutations, rewritten
   frozen status and premature publication.

## Frozen formation boundary

Only `case-07` and `case-08` expose `formationInput`.

- `case-07` provides the square sequence prefix. It does not uniquely
  determine a homogeneous linear recurrence within the frozen order and data
  bound, so formation retains `NO_UNIQUE_LINEAR_RECURRENCE`.
- `case-08` uniquely determines `a_n = 2 a_(n-1)` and selects the candidate
  form.

The production domain requires a non-empty continuation field. Formation
therefore receives a synthetic term derived only from the visible prefix:

- a unique visible-prefix model predicts the synthetic next term;
- otherwise the last visible term is retained as an inert fallback.

This synthetic term is explicitly marked as formation-only and never counts
as held-out success evidence.

## Frozen evaluation result

Each of four campaigns evaluates all six preregistered sequence cases:

| Case | Exact visible-prefix model | Frozen held-out result |
|---|---|---|
| `case-07` | no unique model within order 4 | `NO_UNIQUE_LINEAR_RECURRENCE` |
| `case-08` | `[2]` | confirmed |
| `case-09` | `[-1,1,1]` | confirmed |
| `case-10` | `[1,1]` | confirmed |
| `case-11` | `[35/9,-49/9,26/9]` | refuted: first prediction is `352`, frozen term is `350` |
| `case-12` | `[4,-5,2]` | confirmed |

Across the complete 24-row matrix this yields:

- 16 confirmed linear-recurrence evaluations;
- 4 refuted evaluations;
- 4 inconclusive evaluations with no unique model.

The refuted cubic-prefix interpolation is retained as important negative
evidence. An exact model of the visible prefix is not silently promoted when
it fails the frozen continuation.

## Retained artifacts

```text
build/reports/candidate-independent-linear-recurrence-adapter/
  first/run.json
  second/run.json
  verification/verification.json
```

Every campaign and case retains the corpus hash, split, structural cluster,
formation visibility, exact coefficients, expected and predicted holdout,
production evidence root, terminal reason and bounded resources.

## Claim boundary

A green result establishes deterministic execution and exact held-out
assessment of the linear-recurrence candidate form on the frozen sequence
corpus. It does not establish:

- a unique infinite continuation from finite data;
- formal proof of a general recurrence theorem;
- external mathematical novelty;
- superiority over an information-parity baseline;
- completion of the other two benchmark challenges;
- publication readiness.

The next sequence-specific slice combines this run with the already executed
finite-difference adapter while retaining both supporting and refuting model
evidence per case.
