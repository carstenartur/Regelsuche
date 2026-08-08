# Proof-carrying self-improvement showcase

Status: **contract and Java pre-randomness architecture implemented; not run**  
Tracked by: [#597](https://github.com/carstenartur/Regelsuche/issues/597)

## Purpose

The showcase targets one narrow, reproducible result:

> Regelsuche learns a human-readable executable rewrite/search program from
> TRAIN-only observations. Candidate and configuration are frozen before
> concrete FINAL TEST cases are derived from verifiable public randomness. The
> learned program and all fixed baselines then receive identical cases,
> information and canonical mechanical-work budgets.

A successful run may receive only `SHOWCASE_CONFIRMED`. It establishes neither
external mathematical novelty nor publication-grade validity or universal
superiority. The publication-grade experiment in #521/#533 and expert-review
paths in #389/#391 remain separate.

## Java-owned architecture

Ordinary Java modules own the semantics and JUnit exercises them directly. No
Python verifier or generator, `ProcessBuilder` bridge, or special Gradle init
script remains. The authoritative artifacts are:

```text
ProofCarryingShowcasePlan
ProofCarryingShowcaseCandidateFreeze
ProofCarryingShowcasePublicRandomnessReceipt
ProofCarryingShowcaseSeedReceipt
ProofCarryingShowcaseCaseGenerator
ProofCarryingShowcaseGeneratedCase
ProofCarryingShowcaseGeneratedFinalTest
```

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

A later adapter must verify signatures with a pinned client and retain chain,
client and verification evidence; raw HTTP is insufficient. No randomness round
has been consumed.

## Frozen result threshold

A positive result requires a non-seed candidate with genuine composition and
decision topology, at least three primitive operations on a retained successful
path, improvement on at least four cases across two families, and either two
newly reached cases or a tenfold median canonical-work reduction. Correctness,
hidden-assumption and technical regressions are forbidden. Null, losing,
unsupported and budget-exhausted cases remain in the evidence, followed by two
clean-checkout and one pinned-container reproduction.

The stretch objective is a hundredfold retained stress-tier work reduction or
two solved difficulty levels beyond every fixed baseline. Missing the threshold
produces `SHOWCASE_NULL_RESULT`; cases, thresholds and retries cannot be repaired
after outcomes are visible.

## Comparison matrix and generated cases

Each generated case is executed against:

1. primitive-only best-first search;
2. the preregistered hand-written program;
3. a randomized valid-program baseline;
4. no-composition and no-decision ablations;
5. the learned rewrite program.

Canonical primitive and total-work ledgers are authoritative; wall-clock time is
only an environment-qualified diagnostic.

`ProofCarryingShowcaseCaseGenerator` produces 24 deterministic cases: two
variants at difficulty levels 3 through 6 for nested rational cancellation,
factor-cancel-collect ordering, and multi-stage rational/polynomial
normalization with explicit nonzero assumptions. Coefficients, expressions,
assumptions, topology fingerprints, IDs and hashes derive from the authorized
seed receipt. Every configuration receives the same suite. Replacement,
pruning and selective regeneration are forbidden. Generation retains
`FINAL_TEST_GENERATED_NOT_EXECUTED` and authorizes no result claim.

## TRAIN selection and candidate freeze

The terminal TRAIN artifact retains every complete candidate, readable program
and final evaluation. Selection is deterministic by maximum TRAIN fitness,
minimum node count, then lexicographically minimum candidate hash. Rejected
alternatives retain metrics, blockers, seed-equivalence decisions and structural
facts. Extinction or absence of an eligible candidate fails before randomness.

`candidate-freeze.json` binds repository commit, protocol-authorized TRAIN root,
selection evidence, exact and alpha-structural candidate identities, readable
program, primitive inventory, work budget, evaluation protocol, seed identities,
structural properties and not-before time. Its status is
`CANDIDATE_FROZEN_FINAL_TEST_UNSEEN`.

## Public randomness and seed receipt

`public-randomness-receipt.json` binds plan and candidate freeze to network,
chain, round, round time, randomness, signatures, chain information, pinned
verifier and verification evidence. Only `VERIFIED_BY_PINNED_DRAND_CLIENT` is
accepted, and the round must follow the frozen not-before boundary.

The seed-receipt factory hashes versioned, domain-separated material binding the
showcase, plan, candidate freeze, chain, round, verified randomness and complete
randomness receipt. Its status is
`FINAL_TEST_SEED_DERIVED_AFTER_CANDIDATE_FREEZE`; changing a bound input changes
the seed.

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

The contract, Java generator, terminal-TRAIN retention, selection evidence and
candidate-freeze implementation exist. No real TRAIN run, candidate freeze,
drand round or FINAL TEST has been consumed. The next reversible stage is an
ordinary Java/Gradle command that runs TRAIN and writes population, selection
and freeze artifacts. Only after that command is reviewed and frozen follow the
pinned drand adapter, one-time seed and suite generation, paired comparison and
result-card generation.
