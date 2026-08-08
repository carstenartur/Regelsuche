# Proof-carrying self-improvement showcase

Status: **Java pre-randomness architecture implemented; not run**  
Tracked by: [#597](https://github.com/carstenartur/Regelsuche/issues/597)

## Goal and claim boundary

Regelsuche learns a human-readable executable rewrite/search program from
TRAIN-only observations. Candidate and configuration are frozen before concrete
FINAL TEST cases are derived from verified public randomness. The learned
program and fixed baselines then receive identical cases, information and
canonical mechanical-work budgets.

A successful run may receive only `SHOWCASE_CONFIRMED`; it does not establish
external novelty, publication-grade validity or universal superiority.

## Java-owned lifecycle

Production Java classes own the plan, TRAIN retention, deterministic selection,
candidate freeze, randomness and seed receipts, case generator and generated
FINAL TEST. JUnit invokes them directly. No Python showcase verifier, process
bridge or special Gradle init script remains. Constructors enforce strict JSON,
canonical hashes, claim boundaries, stage order and cross-artifact bindings;
the production exact rational-equivalence service checks generated mathematics.

```text
TRAIN
  -> complete candidate/configuration freeze
  -> not-before delay
  -> first eligible verified drand round
  -> domain-separated seed
  -> deterministic FINAL TEST
  -> one paired comparison matrix
```

The pinned drand `default` chain is:

```text
8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce
```

Raw HTTP is insufficient: a later adapter must verify signatures with a pinned
client and retain chain and verification evidence. No round has been consumed.

## Selection and result contract

Every terminal TRAIN candidate, readable program and evaluation is retained.
Selection ranks eligible alternatives by maximum TRAIN fitness, minimum node
count and candidate hash. Eligibility requires non-seed identity, composition,
decision topology and sufficient primitive path depth. Rejections retain their
metrics, blockers and structural facts; extinction fails before randomness.

`candidate-freeze.json` binds repository, TRAIN and selection roots, candidate
identities and program, inventory, budget, evaluator, seed identities,
structural properties and not-before time. Its status is
`CANDIDATE_FROZEN_FINAL_TEST_UNSEEN`.

The authorized seed creates 24 immutable cases across three families, four
difficulty levels and two variants. Primitive search, preregistered and random
programs, two ablations and the learned program receive the same suite. A
positive result requires the frozen cross-family threshold with no correctness,
assumption or technical regressions. Otherwise the complete result is
`SHOWCASE_NULL_RESULT`; cases, thresholds and retries cannot be repaired after
outcomes are visible.

## Verification and current boundary

```bash
./gradlew test
./gradlew check
./gradlew --no-configuration-cache ciCheck
```

The ordinary CLI exposes the reversible TRAIN-and-freeze command. It accepts no
drand, VALIDATION or FINAL TEST input and publishes retained TRAIN, selection
and freeze evidence atomically. No real TRAIN run, candidate freeze, drand round
or FINAL TEST has yet been consumed.
