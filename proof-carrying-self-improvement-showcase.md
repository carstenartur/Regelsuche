# Proof-carrying self-improvement showcase

Status: **Java pre-randomness architecture implemented; not run**  
Tracked by: [#597](https://github.com/carstenartur/Regelsuche/issues/597)

## Purpose

The showcase is intended to produce one result that is understandable before a
reader studies the complete Regelsuche evidence architecture:

> Regelsuche learns a human-readable executable rewrite/search program from
> TRAIN-only observations. The complete program and configuration are frozen.
> Only afterwards are concrete FINAL TEST cases generated from verifiable
> public randomness. The learned program and every fixed baseline receive the
> same cases, information and canonical mechanical-work budget.

A successful run may receive only `SHOWCASE_CONFIRMED`. It does not establish
external mathematical novelty, expert-reviewed importance, publication-grade
benchmark validity or universal superiority.

The publication-grade experiment in #521/#533 and the expert-review paths in
#389/#391 remain separate and deferred until qualified independent researchers
can participate.

## Java-owned architecture

Showcase semantics are implemented in the ordinary Java modules and exercised
directly by JUnit. There is no Python verifier, Python generator, `ProcessBuilder`
test bridge or special Gradle init script.

The authoritative classes include:

```text
ProofCarryingShowcasePlan
RetainedEvolutionRewriteProgramPopulationRun
ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun
ProofCarryingShowcaseCandidateFreezer
ProofCarryingShowcaseCandidateFreeze
ProofCarryingShowcasePublicRandomnessReceipt
ProofCarryingShowcaseSeedReceipt
ProofCarryingShowcaseCaseGenerator
ProofCarryingShowcaseGeneratedCase
ProofCarryingShowcaseGeneratedFinalTest
```

The constructors and factories enforce schema identity, canonical content
hashes, claim boundaries, stage ordering and cross-artifact bindings. Jackson
is configured for duplicate-field detection, trailing-token rejection and
unknown-field rejection. JUnit invokes these Java classes directly and sends
every generated mathematical case through the production exact rational
equivalence service.

JSON Schema files remain a language-neutral interchange description. They do
not replace the Java invariants and do not own executable test semantics.

## Ordering boundary

Concrete FINAL TEST cases must not exist while the candidate is selected:

```text
TRAIN execution
  -> complete candidate and configuration freeze
  -> finite not-before delay
  -> first eligible verified public-randomness round
  -> domain-separated seed derivation
  -> deterministic FINAL TEST generation
  -> one paired execution matrix
```

The frozen plan pins the League of Entropy drand `default` chain:

```text
8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce
```

A later receipt producer must use a pinned signature-verifying drand client and
retain chain information, client identity and verification evidence. A raw HTTP
response is not sufficient. No randomness round has been consumed.

## Frozen positive threshold

A positive result requires all of the following:

- a candidate that is not exact- or alpha-equivalent to a seed;
- genuine composition and decision topology;
- at least three primitive operations on a retained successful path;
- improvement on at least four generated cases and two structural families;
- at least two newly reached cases, or at least a tenfold median reduction of
  canonical search work;
- zero correctness regressions;
- zero hidden-assumption regressions;
- zero technical failures in the counted positive route;
- complete retention of null, losing, unsupported and budget-exhausted cases;
- two clean checkout reproductions and one pinned-container reproduction.

The stretch objective is either a hundredfold canonical-work reduction on a
retained stress tier or two solved difficulty levels beyond every fixed
baseline.

Missing the threshold produces a complete `SHOWCASE_NULL_RESULT`. Thresholds,
cases and retries may not be repaired after outcomes are visible.

## Frozen comparison matrix

Every generated case is executed against exactly these configurations:

1. primitive-only best-first search;
2. preregistered hand-written program;
3. randomized valid-program baseline;
4. no-composition ablation;
5. no-decision ablation;
6. learned rewrite program.

Canonical primitive and total-work ledgers are authoritative. Wall-clock time
is retained only as an environment-qualified engineering diagnostic.

## Deterministic structural families

`ProofCarryingShowcaseCaseGenerator` produces 24 cases, eight from each family:

- nested rational cancellation and quotient composition;
- factor-cancel-collect ordering;
- multi-stage rational/polynomial normalization with explicit nonzero
  assumptions.

Each family contains two variants at difficulty levels 3 through 6. Case
coefficients, expressions, assumptions, topology fingerprints, IDs and content
hashes are derived deterministically from the authorized seed receipt. Every
configuration receives the same generated suite. Manual replacement, pruning
and selective regeneration are forbidden.

The generated artifact remains:

```text
FINAL_TEST_GENERATED_NOT_EXECUTED
```

Generation is not execution and authorizes no result claim.

## TRAIN retention and deterministic selection

The TRAIN runner retains every terminal candidate, canonical genome and program,
human-readable rendering and final TRAIN evaluation. The retained population is
bound to the exact evaluator protocol and implementation class. Uninterrupted
and checkpoint/resume execution must produce byte-identical terminal evidence.

Every terminal alternative retains exact and alpha identities, raw TRAIN
components, blockers, structural facts and seed-equivalence decisions. Eligible
candidates are ranked deterministically by:

1. maximum TRAIN scalar fitness;
2. minimum program node count;
3. lexicographically minimum candidate hash.

No eligible candidate means terminal TRAIN extinction. The process fails before
creating a public-randomness boundary.

## Candidate-freeze boundary

Before the public-randomness not-before time, `candidate-freeze.json` binds:

- repository commit;
- complete TRAIN run and selection evidence;
- candidate exact and alpha-structural identities;
- human-readable program rendering;
- primitive inventory, work budget and evaluation protocol;
- all seed candidate identities;
- program node count;
- composition, decision and primitive-path properties;
- freeze time and public-randomness not-before time.

Its status is fixed as:

```text
CANDIDATE_FROZEN_FINAL_TEST_UNSEEN
```

Exact seed equivalence, missing topology, inadequate path depth and an
insufficient delay fail closed.

The ordinary Java command accepts no clock, drand, VALIDATION or FINAL TEST
input. It obtains the freeze time only after the complete retained TRAIN runner
has returned. It writes every prerequisite into a private sibling staging
directory, writes `candidate-freeze.json` last and publishes the complete set
only through an atomic directory move.

## Public-randomness and seed receipts

`public-randomness-receipt.json` binds the plan, candidate freeze, network,
chain, round, round time, randomness, signatures, chain information, pinned
verifier and verification evidence. Only this status is accepted:

```text
VERIFIED_BY_PINNED_DRAND_CLIENT
```

The round time must be strictly later than the candidate's frozen not-before
boundary.

The seed-receipt factory derives the generator seed as SHA-256 over versioned
domain-separated material binding:

- showcase ID;
- plan content hash;
- candidate-freeze content hash;
- drand chain and round;
- verified randomness;
- complete randomness-receipt content hash.

The resulting receipt has status:

```text
FINAL_TEST_SEED_DERIVED_AFTER_CANDIDATE_FREEZE
```

Changing any bound input changes the derived seed.

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

The focused tests cover:

- the committed plan and its exact content hash;
- strict JSON roundtrips and rejection of duplicate, unknown and trailing data;
- complete terminal TRAIN retention and deterministic candidate selection;
- candidate-freeze and randomness ordering;
- deterministic domain-separated seed derivation;
- substitution sensitivity;
- deterministic 24-case generation and complete family coverage;
- case, family and suite content roots;
- schema/Java vocabulary agreement;
- exact mathematical confirmation and complete assumption coverage;
- the absence of Python showcase scripts and special init-script test paths.

After those gates are green, the reversible TRAIN-only stage is launched through
the ordinary application entry point:

```bash
./gradlew :app:run --args='showcase-train-freeze \
  research/showcase/proof-carrying-self-improvement/showcase-plan.json \
  <repository-commit> <new-output-directory>'
```

The output directory must not already exist. A successful invocation contains
the plan, TRAIN-only split, suite, evaluator, seeds, study, protocol-bound
terminal population, selection, selected candidate, readable program and the
final candidate-freeze receipt.

## Current boundary and next stage

The Java contract, retained TRAIN population, deterministic selection and
atomic TRAIN/freeze command are implemented on the current development stack.
No real TRAIN run, candidate freeze, drand round or FINAL TEST has been consumed.

The remaining sequence is:

1. merge the complete pre-randomness Java stack only after the normal checkout
   gate is green;
2. run TRAIN and atomically publish one real candidate freeze from the pinned
   clean checkout;
3. implement and verify the pinned Java drand adapter against official vectors;
4. choose the first eligible round strictly after the frozen not-before time;
5. derive the seed and generate the real suite exactly once;
6. execute the complete paired comparison matrix;
7. generate the result card and visual evidence from retained artifacts.
