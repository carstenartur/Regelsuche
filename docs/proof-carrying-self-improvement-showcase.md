# Proof-carrying self-improvement showcase

Status: **Java pre-randomness architecture implemented; not run**  
Tracked by: [#597](https://github.com/carstenartur/Regelsuche/issues/597)

## Purpose

> Regelsuche learns a human-readable executable rewrite/search program from
> TRAIN-only observations. Candidate and configuration are frozen before
> concrete FINAL TEST cases are derived from verifiable public randomness. The
> learned program and fixed baselines then receive identical cases, information
> and canonical mechanical-work budgets.

A successful run may receive only `SHOWCASE_CONFIRMED`. It does not establish
external novelty, publication-grade validity or universal superiority. Those
review paths remain separate.

## Java-owned architecture

Production Java classes own the frozen plan, candidate freeze, verified
randomness and seed receipts, deterministic case generator and generated FINAL
TEST. JUnit invokes those classes directly; no Python showcase verifier or
generator, process bridge or special Gradle init script remains.

Constructors and factories enforce schemas, canonical hashes, claim boundaries,
stage order and cross-artifact bindings. Jackson rejects duplicate or unknown
fields and trailing tokens. The production exact rational-equivalence service
checks generated mathematics. JSON Schema describes interchange only.

## Irreversible ordering boundary

```text
TRAIN execution
  -> complete candidate and configuration freeze
  -> finite not-before delay
  -> first eligible verified public-randomness round
  -> domain-separated seed derivation
  -> deterministic FINAL TEST generation
  -> one paired execution matrix
```

The plan pins the League of Entropy drand `default` chain:

```text
8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce
```

A later adapter must verify signatures with a pinned client and retain chain and
verification evidence; raw HTTP is insufficient. No round has been consumed.

## Selection, freeze and result contract

The terminal TRAIN artifact retains every complete candidate, readable program
and evaluation. Selection uses maximum TRAIN fitness, minimum node count and
then the lexicographically smallest candidate hash. Rejected alternatives keep
their metrics, blockers, seed-equivalence decisions and structural facts.
Extinction or absence of an eligible candidate fails before randomness.

An eligible candidate must be non-seed and contain composition, decision
topology and sufficient primitive path depth. `candidate-freeze.json` binds the
repository, TRAIN and selection roots, candidate identities and program,
primitive inventory, budget, evaluator, seed identities, structural properties
and not-before time. Its status is
`CANDIDATE_FROZEN_FINAL_TEST_UNSEEN`.

The authorized seed deterministically creates 24 cases across three structural
families, four difficulty levels and two variants. Primitive-only search, the
preregistered program, randomized-program baseline, two structural ablations
and the learned program receive the same immutable suite. Canonical work ledgers
are authoritative; wall-clock time is diagnostic.

A positive result requires the frozen cross-family improvement threshold with
no correctness, assumption or technical regressions. Losing, unsupported and
exhausted cases remain visible. Missing the threshold yields
`SHOWCASE_NULL_RESULT`; visible outcomes cannot be followed by repaired cases,
thresholds or retries.

## Verification

```bash
./gradlew test
./gradlew check
./gradlew --no-configuration-cache ciCheck
./gradlew :regelsuche-learning:test \
  --tests 'de.regelsuche.evolution.ProofCarryingShowcase*'
```

Tests cover the committed plan, strict JSON, TRAIN-only selection, stage
ordering, deterministic generation, aggregate roots, schemas, exact mathematics
and absence of Python showcase semantics.

## Current boundary

Terminal-TRAIN retention, deterministic selection and candidate freeze exist.
No real TRAIN run, candidate freeze, drand round or FINAL TEST has been consumed.
The next reversible stage is the ordinary Java command that atomically writes
TRAIN, selection and freeze artifacts.
