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
ProofCarryingShowcaseTrainAndFreezeCommand
ProofCarryingShowcaseTrainFreezeAuthorityCommand
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

./gradlew :app:test \
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
- canonical one-attempt authority manifests and run-history rejection;
- authority-commit parent/file binding and retained execution receipts;
- the absence of Python showcase scripts and special init-script test paths.

After those gates are green, the reversible TRAIN-only stage can also be run
through the ordinary application entry point for development and characterization:

```bash
./gradlew :app:run --args='showcase-train-freeze \
  research/showcase/proof-carrying-self-improvement/showcase-plan.json \
  <repository-commit> <new-output-directory>'
```

The output directory must not already exist. A successful invocation contains
the plan, TRAIN-only split, suite, evaluator, seeds, study, protocol-bound
terminal population, selection, selected candidate, readable program and the
final candidate-freeze receipt. This development invocation is not the retained
one-attempt authority run.

## One-attempt execution authority

The retained real execution reuses the existing central `CI` workflow rather
than adding a showcase-specific workflow. GitHub Actions remains a thin
platform adapter: for the exact reserved branch-creation event it chooses the
checkout-owned Gradle task `authorizedShowcaseTrainFreeze`; for ordinary CI it
chooses `ciCheck`. The workflow contains no authority validation, candidate
selection or result interpretation.

`authorizedShowcaseTrainFreeze` depends on the unchanged authoritative
`ciCheck` lifecycle. Only after that lifecycle succeeds can the ordinary Java
class `ProofCarryingShowcaseTrainFreezeAuthorityCommand` verify the authority
and execute TRAIN/freeze. This preserves the repository rule that pass/fail
semantics belong to checkout-owned Gradle/Java/JUnit code rather than GitHub
Actions YAML.

After the execution support is merged and the resulting `main` commit is green,
an unreferenced single-parent authority commit is created whose only changed
file is:

```text
research/showcase/proof-carrying-self-improvement/
  train-freeze-authority-v1.json
```

The manifest binds the authority ID and branch, the reviewed implementation
commit, the TRAIN-and-freeze operation, a maximum of one workflow attempt and
the `AUTHORIZED_NOT_RUN` state. Creating the new branch
`showcase/train-freeze-authority-v1` directly at that commit emits the one
authorized GitHub `create` event. Later pushes to the branch are not execution
triggers.

The checkout-owned Java authority verifier fails closed unless all of the
following hold:

- the environment identifies a branch-creation `create` event for the exact
  authority ref;
- the workflow attempt number is one;
- no earlier `create`-triggered workflow run exists for that authority branch;
- the authority manifest is a bounded regular non-symlink file;
- the manifest is canonical one-line JSON with one final newline and exactly
  the fixed authority vocabulary;
- the authority commit has exactly one parent;
- that parent equals the manifest's reviewed implementation commit;
- the authority manifest is the commit's only added file and its local Git blob
  identity matches GitHub's commit evidence;
- the authority commit equals the event-bound repository SHA supplied by the
  thin workflow adapter;
- the produced candidate freeze parses through the production Java contract,
  remains compatible with the frozen plan and binds the same repository commit;
- the retained output contains no public-randomness or FINAL TEST artifact.

The verifier queries GitHub's read-only REST surface for immutable commit
metadata and the create-event run history. The workflow grants only `actions:
read`, `contents: read` and `packages: read`; neither the verifier nor the
TRAIN/freeze run can write repository state.

The special concurrency group never cancels an active authority run. Deleting
and recreating the same branch emits another `create` event, which is rejected
by the retained run-history check; GitHub's rerun action is rejected by the
attempt-number check. Cancellation or technical failure therefore consumes
authority version `v1`. Another attempt requires a newly reviewed authority
version instead of a silent retry.

A successful retained attempt writes the ordinary atomically published
TRAIN/freeze directory plus checkout-owned execution evidence containing a
deterministic SHA-256 file manifest, the authority-manifest hash and an
execution receipt. The existing artifact-upload step only retains these files;
it does not create their semantics. No drand round is selected or fetched and
no FINAL TEST can be generated by this authority path.

## Current boundary and next stage

The Java contract, retained TRAIN population, deterministic selection, atomic
TRAIN/freeze command and checkout-owned one-attempt authority are implemented
on the current development stack. No real TRAIN run, candidate freeze, drand
round or FINAL TEST has been consumed.

The remaining sequence is:

1. merge the one-attempt execution authority only after its normal checkout gate
   is green;
2. require the post-merge `main` gate to be green before creating the authority
   commit;
3. create the single authority commit and reserved branch exactly once, then
   retain the resulting TRAIN population and candidate freeze;
4. implement and verify the pinned Java drand adapter against official vectors;
5. choose the first eligible round strictly after the frozen not-before time;
6. derive the seed and generate the real suite exactly once;
7. execute the complete paired comparison matrix;
8. generate the result card and visual evidence from retained artifacts.
