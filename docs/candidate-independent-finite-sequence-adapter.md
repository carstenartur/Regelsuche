# Candidate-independent finite-sequence adapter

## Scope

This is the first evaluated execution slice of the candidate-independent benchmark in issue #383. It consumes the concrete corpus and formation profile frozen before any evaluated campaign in `case-payload-freeze/v1`.

The adapter covers one challenge:

```text
finite-difference-recurrences
```

It does not complete the overall benchmark. Assumption-sensitive rational rewrites, reusable macros and the linear-recurrence candidate form still require separate production adapters.

## Authoritative command

```bash
./gradlew verifyCandidateIndependentFiniteSequenceAdapter
```

The task is part of the root `check` and `fullCheck` lifecycles. GitHub Actions only invokes those checkout-local Gradle contracts.

## Frozen inputs

The runner binds and verifies:

```text
research/benchmarks/candidate-independent/case-corpus.json
research/benchmarks/candidate-independent/finite-sequence-candidate-forms.json
research/benchmarks/candidate-independent/corpus-freeze-receipt.json
```

The frozen profile authorizes two bounded candidate forms:

| Candidate form | Frozen implementation status | This slice |
|---|---|---|
| `FINITE_DIFFERENCE_POLYNOMIAL` | `AVAILABLE` | executed through `FiniteDifferenceSequenceDomain` |
| `LINEAR_RECURRENCE` | `ADAPTER_REQUIRED` | retained as an explicit coverage blocker |

No operation or candidate form is added after observing results.

## Formation boundary

Every campaign receives exactly the two TRAIN `formationInput` payloads, `case-07` and `case-08`. Formation receives the observed prefix, bounded order and candidate-form vocabulary. It does not receive `evaluationInput` or a frozen holdout continuation.

The production domain requires a non-empty holdout in its generic seed contract. For formation-only execution, the runner therefore supplies one synthetic continuation term derived exclusively from the visible observed prefix by bounded finite-difference extrapolation. The retained formation evidence records:

- `inputSurface: formationInput`;
- `evaluationInputRead: false`;
- `holdoutVisible: false`;
- `syntheticHoldoutSource: DERIVED_FROM_OBSERVED_PREFIX_ONLY`;
- the production-domain evidence hash.

This synthetic term is not used as benchmark success evidence. It only allows the already available production adapter to form or reject a finite-difference candidate without crossing the held-out boundary.

## Evaluation boundary

After candidate-form selection is frozen, the evaluator processes all six finite-sequence cases for each of four configured campaigns. The complete matrix contains 24 rows:

```text
4 campaigns × (2 TRAIN + 2 VALIDATION + 2 TEST cases)
```

For each row, the production `FiniteDifferenceSequenceDomain` receives the frozen observed prefix and holdout continuation at the evaluation stage. The output retains the production outcome and its content hash.

A production outcome `CONFIRMED` becomes:

```text
CONFIRMED_FINITE_DIFFERENCE_FIT
```

Any other finite-difference outcome becomes:

```text
INCOMPLETE_ADAPTER_COVERAGE
reasonCode: LINEAR_RECURRENCE_ADAPTER_REQUIRED
```

It is deliberately not reported as a benchmark refutation. The preregistered candidate vocabulary also permits `LINEAR_RECURRENCE`, and that adapter has not yet executed.

## Determinism and independent verification

Gradle generates two clean run files below:

```text
build/reports/candidate-independent-finite-sequence-adapter/
  first/run.json
  second/run.json
  verification/verification.json
```

The independent Python verifier:

1. requires the two run files to be byte-identical;
2. validates the strict Draft 2020-12 run schema;
3. recomputes the semantic hashes of the corpus, profile, freeze receipt and every nested result;
4. reconstructs the four deterministic campaign seeds;
5. verifies all two formation records and all six evaluation records per campaign;
6. checks TRAIN-only formation and evaluator-only held-out access;
7. independently recomputes confirmed and incomplete aggregate counts;
8. rejects held-out formation leakage, a missing row, premature completion/publication, removal of the recurrence blocker and a hidden false-positive outcome.

JUnit characterization additionally exercises byte determinism, held-out visibility and fail-closed profile manipulation.

## Authorized claim

A green result establishes only that the available finite-difference production adapter executed deterministically against the frozen sequence corpus with complete row accounting and without crossing the declared formation boundary.

It does not establish:

- a unique infinite continuation from finite data;
- complete coverage of the preregistered sequence candidate forms;
- completion of issue #383;
- superiority over an information-parity baseline;
- formal proof;
- external mathematical novelty;
- publication readiness.

All corresponding fields remain false, `NOT_EVALUATED` or `ADAPTER_REQUIRED` in retained evidence.
