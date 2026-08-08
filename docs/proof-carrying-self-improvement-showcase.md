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
external novelty, publication-grade validity or universal superiority. The
publication-grade and expert-review paths remain separate.

## Java-owned architecture

Ordinary Java modules own the semantics and JUnit exercises them directly. No
Python verifier or generator, `ProcessBuilder` bridge, or special Gradle init
script remains. The Java artifacts cover the frozen plan, candidate freeze,
verified randomness receipt, seed receipt, deterministic case generator and
generated FINAL TEST.

Constructors and factories enforce schemas, canonical hashes, claim boundaries,
stage order and cross-artifact bindings. Jackson rejects duplicate or unknown
fields and trailing tokens. Generated mathematics is checked through the
production exact rational-equivalence service. JSON Schema documents the
interchange surface but does not own executable semantics.

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

A later adapter must verify signatures with a pinned client and retain its chain
and verification evidence; raw HTTP is insufficient. No randomness round has
been consumed.

## Frozen result contract

A positive result requires an eligible non-seed program with composition,
decision topology and sufficient primitive path depth, plus the frozen
cross-family improvement threshold without correctness, assumption or technical
regressions. All losing, unsupported and exhausted cases remain in the evidence.
Missing the threshold produces `SHOWCASE_NULL_RESULT`; visible outcomes cannot
be followed by repaired cases, thresholds or retries.

## Comparison and generated cases

Each generated case is executed against primitive-only search, a preregistered
program, a randomized valid-program baseline, both structural ablations and the
learned program. Canonical primitive and total-work ledgers are authoritative;
wall-clock time is diagnostic only.

The generator creates 24 deterministic cases across three structural families,
four difficulty levels and two variants. Expressions, assumptions, topology,
IDs and hashes derive from the authorized seed receipt. Every configuration
receives the same immutable suite. Generation retains
`FINAL_TEST_GENERATED_NOT_EXECUTED` and authorizes no result claim.

## TRAIN selection and candidate freeze

The terminal TRAIN artifact retains every complete candidate, readable program
and evaluation. Selection is deterministic by maximum TRAIN fitness, minimum
node count, then lexicographically minimum candidate hash. Rejected alternatives
retain metrics, blockers, seed-equivalence decisions and structural facts.
Extinction or absence of an eligible candidate fails before randomness.

`candidate-freeze.json` binds the repository, protocol-authorized TRAIN root,
selection evidence, candidate identities and program, primitive inventory, work
budget, evaluation protocol, seed identities, structural properties and
not-before time. Its status is `CANDIDATE_FROZEN_FINAL_TEST_UNSEEN`.

## Public randomness and seed receipt

The randomness receipt binds plan and candidate freeze to the network, chain,
round, randomness, signatures, pinned verifier and verification evidence. Only
`VERIFIED_BY_PINNED_DRAND_CLIENT` after the not-before boundary is accepted.
The seed receipt then hashes all bound inputs with a versioned, domain-separated
construction and receives
`FINAL_TEST_SEED_DERIVED_AFTER_CANDIDATE_FREEZE`.

## Verification

```bash
./gradlew test
./gradlew check
./gradlew --no-configuration-cache ciCheck
./gradlew :regelsuche-learning:test \
  --tests 'de.regelsuche.evolution.ProofCarryingShowcase*'
```

Tests cover the committed plan and hash, strict JSON, TRAIN-only selection,
freeze/randomness ordering, deterministic seed and case generation, aggregate
roots, schemas, exact mathematics and absence of Python showcase semantics.

## Current boundary

Terminal-TRAIN retention, deterministic selection and candidate freeze now
exist. No real TRAIN run, candidate freeze, drand round or FINAL TEST has been
consumed. The next reversible stage is the ordinary Java/Gradle command that
writes the complete TRAIN, selection and freeze artifacts.
