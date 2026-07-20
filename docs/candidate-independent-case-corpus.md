# Candidate-independent case-corpus freeze

## Why an amendment is required

The original candidate-independent preregistration fixed:

- 18 case identities;
- three structural challenge classes;
- TRAIN, VALIDATION and TEST assignments;
- structural-cluster names;
- budgets, metrics, outcome policies and formation visibility.

It did not contain the concrete expression tasks, sequence prefixes and holdouts, replay and paired-utility tasks, or the exact formation inventories. Executing campaigns against payloads or operations selected after observing results would turn the benchmark into a post-hoc evaluation.

`case-payload-freeze/v1` repairs that omission **before any evaluated campaign execution**. It does not overwrite or pretend to be part of the original source. The amendment binds the original source semantic hash and Git blob, the previously merged all-incomplete execution foundation, the concrete corpus and all formation-inventory profiles.

## Authoritative files

```text
research/benchmarks/candidate-independent/
  benchmark-source.json
  case-corpus.json
  rational-assumption-primitives.json
  finite-sequence-candidate-forms.json
  macro-primitives.json
  corpus-freeze-receipt.json
```

The verification command is:

```bash
./gradlew verifyCandidateIndependentCaseCorpus
```

It is part of `check` and `fullCheck`.

## Frozen corpus

The corpus contains exactly 18 cases:

- 6 assumption-sensitive rational rewrite cases;
- 6 finite-difference or bounded-recurrence sequence cases;
- 6 reusable symbolic-macro cases;
- 6 TRAIN, 6 VALIDATION and 6 TEST cases.

Every case binds its original identity, split and structural cluster plus one challenge-specific payload.

### Rational rewrites

TRAIN payloads expose only seed expressions and side conditions. Evaluation-only payloads contain symbolic source/target pairs and exact nonzero or pole assumptions for linear cancellation, affine factors, quadratic denominators, partial fractions, nested denominators and parameterized poles.

The expected references are evaluator-only. Formation must not receive them.

### Finite differences and recurrences

TRAIN cases expose an observed integer prefix, candidate-form vocabulary, index origin and order bound. Holdout continuations are evaluator-only.

VALIDATION and TEST cases expose no formation payload at all. The evaluator payload retains observed prefix, hidden continuation and ambiguity policy. No case may authorize a unique infinite-sequence claim from finite data.

### Reusable macros

TRAIN cases expose replay traces expressed through the versioned `macro-primitives/v1` operation vocabulary. Evaluation-only tasks compare baseline and macro-enabled search under identical inputs, targets, inventory, strategy and budgets.

VALIDATION and TEST tasks cover distribute/collect, substitution/simplification, cross-family composition and assumption-guarded reuse. Macro utility remains distinct from truth, novelty and mathematical importance.

## Frozen formation inventories

The freeze receipt binds the semantic roots of three profiles.

### `rational-assumption-primitives/v1`

The profile declares the permitted assumption-sensitive operation classes and identifies which are already implemented and which still require an adapter. `ADAPTER_REQUIRED` is not silently treated as success: an execution before implementation must retain an explicit unsupported or incomplete outcome.

### `finite-sequence-candidate-forms/v1`

Only `FINITE_DIFFERENCE_POLYNOMIAL` and `LINEAR_RECURRENCE` candidates are authorized, each with a bounded order. The finite-difference implementation is already available; the recurrence adapter remains explicit. The profile forbids inferring a unique infinite continuation from finite observations.

### `macro-primitives/v1`

Stable profile operations map to one or more current rewrite-rule IDs. For example, one semantic distribution operation covers the left/right addition/subtraction implementation variants. This preserves a stable benchmark vocabulary without allowing post-hoc rule additions.

Changing a profile changes its content hash and invalidates the combined preregistration root.

## Exposure boundary

For every TRAIN case:

```text
candidateFormationMayRead = [formationInput]
candidateFormationMustNotRead = [evaluationInput]
```

For every VALIDATION or TEST case:

```text
formationInput = null
candidateFormationMayRead = []
candidateFormationMustNotRead = [evaluationInput]
```

The corpus is source controlled, so this is a runtime and evidence boundary rather than a claim that maintainers cannot inspect the repository. The evaluated runner must prove that candidate formation received only the declared formation surface.

## Freeze receipt

`regelsuche.candidate-independent-corpus-freeze-receipt/v1` records:

- the original benchmark-source semantic hash;
- the exact original Git blob identity;
- the case-corpus semantic hash;
- all three formation-inventory hashes;
- the prior execution-foundation commit;
- a combined preregistration root;
- `executionStatusAtFreeze: NOT_STARTED`;
- zero executed campaigns and evaluations;
- `NO_EVALUATED_RESULTS_EXIST`;
- publication and external novelty not authorized.

## Independent verification

`scripts/verify-candidate-independent-case-corpus.py`:

1. validates the corpus, receipt and formation-profile Draft 2020-12 schemas and rejects duplicate JSON fields;
2. recomputes every case, profile, corpus and receipt hash;
3. reconstructs the original source semantic hash and Git blob;
4. checks that the prior execution-foundation commit is an ancestor of the current checkout;
5. requires exact case identity, split and structural-cluster agreement;
6. requires TRAIN-only formation payloads and evaluator-only heldout fields;
7. verifies sequence observed/holdout separation, candidate-form vocabulary and ambiguity limits;
8. verifies rational and macro profile bindings and bounded task inventories;
9. recomputes the combined preregistration root including all inventory hashes;
10. proves that missing cases, cluster substitution, heldout formation leakage, inventory drift and a post-execution freeze receipt fail closed.

Reports are retained under:

```text
build/reports/candidate-independent-corpus-freeze/
```

## Claim boundary

A green corpus-freeze check establishes only that concrete inputs and formation inventories were fixed while the benchmark still had no evaluated results. It does not establish:

- candidate formation;
- held-out success;
- search improvement;
- proof;
- external novelty;
- benchmark publication readiness.

The next implementation step may add execution adapters, but it must consume these exact payloads and profiles without modifying them after observing outcomes.
