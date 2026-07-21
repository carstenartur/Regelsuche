# Exact linear-recurrence discovery domain

## Scope

This slice implements the second candidate language preregistered for the
candidate-independent `finite-difference-recurrences` challenge in issue #383:

```text
a_n = c_1 a_(n-1) + c_2 a_(n-2) + ... + c_k a_(n-k)
```

The frozen profile continues to record `LINEAR_RECURRENCE` as
`ADAPTER_REQUIRED`, because that describes the state at corpus freeze time.
This implementation does not rewrite that historical artifact.

## Exact candidate formation

`LinearRecurrenceSequenceDomain` searches increasing orders from 1 through
the frozen `maximumOrder` and uses exact `BigInteger` rational arithmetic.
A candidate is emitted only when:

- the observed prefix contains at least `2 * order` terms;
- the coefficient system is consistent and has full column rank;
- a unique coefficient vector exists;
- the recurrence replays every observed term exactly.

Floating-point fitting, tolerances and post-hoc order enlargement are not
used. A finite prefix with no uniquely determined model remains
`INCONCLUSIVE`; it is not turned into a guessed recurrence.

## Independent holdout evaluation

The selected model is evaluated only after formation against the supplied
holdout continuation. The certificate records exact coefficients and all
replayed terms, with evidence strength:

```text
LINEAR_RECURRENCE_FINITE_DATA_VALIDATION_NOT_FORMAL_PROOF
```

This is deliberately not a claim that a finite prefix has one unique infinite
continuation.

## Frozen-corpus characterization

`CandidateIndependentLinearRecurrenceCorpusTest` runs the production domain
against the six sequence cases frozen before implementation:

| Case | Lowest unique observed-prefix recurrence | Outcome |
|---|---|---|
| `case-07` | no unique model within order 4 | `INCONCLUSIVE` |
| `case-08` | `a_n = 2 a_(n-1)` | `CONFIRMED` |
| `case-09` | `a_n = -a_(n-1) + a_(n-2) + a_(n-3)` | `CONFIRMED` |
| `case-10` | `a_n = a_(n-1) + a_(n-2)` | `CONFIRMED` |
| `case-11` | `a_n = 35/9 a_(n-1) - 49/9 a_(n-2) + 26/9 a_(n-3)` | `REFUTED` by the frozen holdout |
| `case-12` | `a_n = 4 a_(n-1) - 5 a_(n-2) + 2 a_(n-3)` | `CONFIRMED` |

The `case-11` result is important negative evidence: an exact recurrence can
interpolate the visible prefix and still fail to generalize. The test retains
that refutation and verifies byte-deterministic evidence plus balanced
resource accounting.

Run the focused checks with:

```bash
./gradlew :regelsuche-discovery:test \
  --tests de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomainTest
./gradlew :regelsuche-release:test \
  --tests de.regelsuche.release.CandidateIndependentLinearRecurrenceCorpusTest
```

## Remaining benchmark integration

This slice makes the production candidate form and frozen-corpus behavior
reviewable. Issue #383 still requires the canonical multi-campaign run
artifact, strict run schema, independent aggregate verifier and combined
coverage report with the existing finite-difference adapter.

## Claim boundary

A green result establishes exact bounded recurrence formation and held-out
validation for the frozen sequence corpus. It does not establish formal proof,
external novelty, universal sequence inference, information-parity
superiority or completion of the full three-challenge benchmark.
