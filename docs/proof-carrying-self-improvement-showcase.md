# Proof-carrying self-improvement showcase

Status: **contract and Java pre-randomness architecture implemented; not run**  
Tracked by: [#597](https://github.com/carstenartur/Regelsuche/issues/597)

## Purpose

The showcase is designed to establish one narrow, reproducible result:

> Regelsuche learns a human-readable executable rewrite/search program from
> TRAIN-only observations. The complete candidate and configuration are frozen
> before concrete FINAL TEST cases are derived from verifiable public
> randomness. The learned program and every fixed baseline then receive the
> same cases, information and canonical mechanical-work budget.

A successful run may receive only `SHOWCASE_CONFIRMED`. It does not establish
external mathematical novelty, expert-reviewed importance, publication-grade
benchmark validity or universal superiority. The publication-grade experiment
in #521/#533 and the expert-review paths in #389/#391 remain separate.

## Java-owned architecture

Showcase semantics live in ordinary Java modules and are exercised directly by
JUnit. There is no Python verifier or generator, `ProcessBuilder` test bridge,
or special Gradle init-script test path.

The authoritative artifacts are:

```text
ProofCarryingShowcasePlan
ProofCarryingShowcaseCandidateFreeze
ProofCarryingShowcasePublicRandomnessReceipt
ProofCarryingShowcaseSeedReceipt
ProofCarryingShowcaseCaseGenerator
ProofCarryingShowcaseGeneratedCase
ProofCarryingShowcaseGeneratedFinalTest
```

Their constructors and factories enforce schema identity, canonical hashes,
claim boundaries, stage ordering and cross-artifact bindings. Jackson rejects
duplicate fields, trailing tokens and unknown fields. Generated mathematical
cases are checked through the production exact rational-equivalence service.
JSON Schema remains a language-neutral interchange description; it does not
own executable semantics.

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

A later receipt producer must use a pinned signature-verifying drand client and
retain chain information, client identity and verification evidence. A raw HTTP
response is insufficient. No randomness round has been consumed.

## Frozen positive threshold

A positive result requires:

- a candidate that is not exact- or alpha-equivalent to a seed;
- genuine composition and decision topology;
- at least three primitive operations on a retained successful path;
- improvement on at least four generated cases and two structural families;
- at least two newly reached cases, or a tenfold median reduction of canonical
  search work;
- no correctness, hidden-assumption or technical regressions;
- retention of null, losing, unsupported and budget-exhausted cases;
- two clean-checkout reproductions and one pinned-container reproduction.

The stretch objective is a hundredfold work reduction on a retained stress tier
or two solved difficulty levels beyond every fixed baseline. Missing the frozen
threshold produces `SHOWCASE_NULL_RESULT`; cases, thresholds and retries may not
be repaired after outcomes are visible.

## Comparison matrix and generated cases

Every generated case is executed against exactly these configurations:

1. primitive-only best-first search;
2. preregistered hand-written program;
3. randomized valid-program baseline;
4. no-composition ablation;
5. no-decision ablation;
6. learned rewrite program.

Canonical primitive and total-work ledgers are authoritative. Wall-clock time
is retained only as an environment-qualified engineering diagnostic.

`ProofCarryingShowcaseCaseGenerator` deterministically produces 24 cases: two
variants at difficulty levels 3 through 6 for each of these families:

- nested rational cancellation and quotient composition;
- factor-cancel-collect ordering;
- multi-stage rational/polynomial normalization with explicit nonzero
  assumptions.

Coefficients, expressions, assumptions, topology fingerprints, IDs and hashes
are derived from the authorized seed receipt. Every configuration receives the
same suite. Replacement, pruning and selective regeneration are forbidden. The
result remains `FINAL_TEST_GENERATED_NOT_EXECUTED`; generation is not execution
and authorizes no result claim.

## TRAIN candidate selection and freeze

The retained terminal TRAIN population preserves each complete candidate,
human-readable program and final evaluation. Selection is deterministic:

1. maximum TRAIN scalar fitness;
2. minimum program node count;
3. lexicographically minimum candidate hash.

Every rejected alternative remains visible with its TRAIN metrics, blockers,
seed-equivalence decisions and structural facts. An extinct population or a
population without an eligible candidate fails before a randomness boundary is
created.

`candidate-freeze.json` binds the repository commit, protocol-authorized TRAIN
root, selection evidence, exact and alpha-structural candidate identities,
human-readable program, primitive inventory, work budget, evaluation protocol,
all seed identities, structural properties and the not-before time. Its status
is `CANDIDATE_FROZEN_FINAL_TEST_UNSEEN`.

## Public-randomness and seed receipts

`public-randomness-receipt.json` binds the plan and candidate freeze to the
network, chain, round, round time, randomness, signatures, chain information,
pinned verifier and verification evidence. Only
`VERIFIED_BY_PINNED_DRAND_CLIENT` is accepted, and the round time must be later
than the frozen not-before boundary.

The seed-receipt factory derives SHA-256 over versioned, domain-separated
material binding the showcase, plan, candidate freeze, chain, round, verified
randomness and complete randomness receipt. Its status is
`FINAL_TEST_SEED_DERIVED_AFTER_CANDIDATE_FREEZE`; changing any bound input
changes the seed.

## Verification from a checkout

The ordinary lifecycle is authoritative:

```bash
./gradlew test
./gradlew check
./gradlew --no-configuration-cache ciCheck
```

Focused Java/JUnit execution:

```bash
./gradlew :regelsuche-learning:test \
  --tests 'de.regelsuche.evolution.ProofCarryingShowcase*'
```

The tests cover the committed plan and hash, strict JSON handling, TRAIN-only
selection, freeze/randomness ordering, deterministic seed derivation and
24-case generation, aggregate roots, schema vocabulary, exact mathematical
confirmation and the absence of Python showcase semantics.

## Current boundary and next stage

The contract, deterministic Java generator, complete terminal-TRAIN retention,
selection evidence and candidate-freeze implementation exist. No real TRAIN
run, candidate freeze, drand round or FINAL TEST has been consumed.

The next implementation stage is an ordinary Java/Gradle entry point that runs
TRAIN and writes the retained population, selection and freeze artifacts. After
that command is reviewed and frozen, the remaining stages are the pinned Java
drand adapter, one-time seed and suite generation, the paired comparison matrix,
and result-card generation from retained artifacts.
