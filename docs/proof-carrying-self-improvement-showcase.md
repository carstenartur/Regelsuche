# Proof-carrying self-improvement showcase

Status: **v1 ended at the TRAIN candidate-formation boundary; reusable preflight and Stage-C infrastructure are implemented for a separately preregistered future version**  
Tracked by: [#597](https://github.com/carstenartur/Regelsuche/issues/597), [#609](https://github.com/carstenartur/Regelsuche/issues/609)

The immutable v1 result is documented separately in
[proof-carrying-self-improvement-showcase-v1-result.md](proof-carrying-self-improvement-showcase-v1-result.md).

## Purpose

The showcase asks a deliberately narrow systems-research question:

> Can Regelsuche learn a human-readable executable rewrite/search program from
> TRAIN-only observations, freeze that program before any hidden test material
> exists, and then outperform fixed baselines on a FINAL TEST generated from
> externally verifiable future public randomness under the same information and
> canonical work budgets?

A positive result may receive only `SHOWCASE_CONFIRMED`. It does not establish
external mathematical novelty, expert-reviewed importance, publication-grade
benchmark validity or universal superiority.

The publication-grade experiment in #521/#533 and the independent-review paths
in #389/#391 remain separate.

## Current empirical status

Showcase v1 was executed exactly once under its one-attempt authority.

The checkout-owned `ciCheck` passed. The real TRAIN run then reached candidate
selection but no terminal alternative satisfied the preregistered freeze
eligibility policy. Selection therefore failed before the atomic TRAIN/freeze
bundle was published.

For v1:

```text
TRAIN:                 EXECUTED
eligible selection:    NOT PRODUCED
candidate freeze:      NOT CREATED
public randomness:     NOT CONSUMED
FINAL TEST seed:       NOT DERIVED
FINAL TEST cases:      NOT GENERATED
FINAL TEST execution:  NOT RUN
showcase claim:         NOT ESTABLISHED
```

This is a **terminal candidate-formation null attempt**, not a failed hidden-test
result. No hidden FINAL TEST material was generated or observed.

The v1 authority is consumed and must not be rerun, recreated or replaced. See
the immutable result page for the exact implementation commit, authority
commit, workflow run and retained artifact digest.

## Stage ordering

A future showcase version must keep four different activities separate:

```text
0. deterministic TRAIN-only preflight / characterization
   -> no authority consumption
   -> no candidate freeze
   -> no clock boundary
   -> no public randomness
   -> no FINAL TEST material

1. one-attempt authority
   -> exact production TRAIN
   -> deterministic terminal selection
   -> candidate freeze, if and only if an eligible candidate exists

2. public-randomness stage
   -> first eligible verified drand round after the frozen not-before boundary
   -> domain-separated seed derivation
   -> deterministic FINAL TEST generation

3. paired FINAL TEST execution
   -> fixed baselines and learned program receive identical cases and budgets
   -> complete positive, negative, null and technical-failure accounting
```

The preflight exists to prevent another avoidable authority consumption when the
production TRAIN configuration cannot reach the already declared candidate
eligibility boundary. It is not a way to reconstruct or retry v1.

## Java-owned architecture

Showcase semantics live in the ordinary Java modules and are exercised directly
by JUnit. There is no Python verifier, Python generator, `ProcessBuilder` test
bridge or special Gradle init-script semantics.

Important production classes include:

```text
ProofCarryingShowcasePlan
RetainedEvolutionRewriteProgramPopulationRun
ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun
ProofCarryingShowcaseCandidateFreezer
ProofCarryingShowcaseTrainSelectionEvidence
ProofCarryingShowcaseCandidateFreeze
ProofCarryingShowcaseTrainPreflightCommand
ProofCarryingShowcaseTrainAndFreezeCommand
ProofCarryingShowcaseTrainFreezeAuthorityCommand
ProofCarryingShowcaseDrandVerificationEvidence
ProofCarryingShowcasePublicRandomnessReceipt
ProofCarryingShowcaseSeedReceipt
ProofCarryingShowcaseCaseGenerator
ProofCarryingShowcaseGeneratedFinalTest
```

Constructors and factories enforce schema identity, canonical content hashes,
claim boundaries, stage ordering and cross-artifact bindings. JSON parsing uses
strict Jackson settings including duplicate-field, unknown-field and
trailing-token rejection.

JSON Schema files provide language-neutral interchange descriptions; the Java
invariants remain authoritative executable semantics.

## TRAIN retention and eligibility

The TRAIN runner retains the terminal population and final TRAIN evaluations.
Candidate selection is deterministic.

A candidate is freeze-eligible only when:

- the TRAIN evaluator reports no blocker;
- it is not exact-equivalent to any seed;
- it is not alpha-structurally equivalent to any seed;
- composition topology is present;
- decision topology is present;
- the minimum structural primitive path depth meets the frozen threshold.

Eligible alternatives are ranked by:

1. maximum TRAIN scalar fitness;
2. minimum program node count;
3. lexicographically minimum candidate hash.

`ProofCarryingShowcaseTrainSelectionEvidence` can represent both outcomes:

```text
ELIGIBLE_CANDIDATE_AVAILABLE_FINAL_TEST_UNSEEN
NO_ELIGIBLE_TRAIN_ALTERNATIVE_FINAL_TEST_UNSEEN
```

The artifact retains terminal identities, raw TRAIN fitness components,
evaluator blockers, seed equivalence, structural facts and freeze blockers.
The same production eligibility and ranking logic is shared with the candidate
freezer so that preflight and authority selection cannot silently diverge.

A null selection is distinct from population extinction. It means a terminal
population exists, but none of its retained alternatives crosses the frozen
eligibility boundary.

## Deterministic TRAIN-only preflight

For a **future preregistered showcase contract that explicitly permits TRAIN-only
characterization before authority**, the ordinary application exposes:

```bash
./gradlew :app:run --args='showcase-train-preflight \
  <future-showcase-plan.json> <new-output-directory>'
```

The command uses the same production `TrainConfiguration`, evaluator, mutator,
population policy and seed definitions as the TRAIN/freeze path. It stops at
terminal selection evidence and publishes no `candidate-freeze.json`, clock
boundary, drand receipt, seed or FINAL TEST material.

Do **not** use this command with the consumed v1 plan to reconstruct v1 terminal
alternatives. That would be a new TRAIN execution after the one-attempt result
and would not be valid v1 evidence.

The preflight is intentionally not part of v1's historical `ciCheck`. A future
showcase version must preregister whether and how preflight evidence is a gate
before its authority is created.

## Candidate-freeze boundary

Only an eligible selection may produce `candidate-freeze.json`. The freeze
binds at least:

- repository commit;
- complete protocol-bound TRAIN run and selection evidence;
- candidate exact and alpha-structural identities;
- human-readable program identity;
- primitive inventory, work budget and evaluation protocol;
- seed candidate identities;
- structural facts;
- freeze time and public-randomness not-before time.

The status is:

```text
CANDIDATE_FROZEN_FINAL_TEST_UNSEEN
```

The ordinary Java TRAIN/freeze command obtains the freeze time only after TRAIN
has completed. Successful output is first written into a private sibling
staging directory and becomes visible only through an atomic directory move.
A null selection therefore creates no partial candidate freeze.

## Public randomness

The frozen plan pins the League of Entropy drand `default` chain:

```text
8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce
```

Reusable Stage-C infrastructure pins chain information and typed verification
evidence before seed derivation. A future receipt producer must use the pinned,
signature-verifying drand client and retain enough evidence to verify chain,
client, round timing, signature verification and the derived randomness.

A raw HTTP response is not sufficient.

For v1 this infrastructure is **unused**: there is no candidate freeze, so no
round is eligible to be consumed.

After a valid freeze, the accepted round must be the first eligible verified
round strictly after the frozen not-before boundary. The seed receipt then
binds the showcase, plan, candidate freeze, drand chain and round, verified
randomness and randomness-receipt identity using a versioned domain-separated
SHA-256 derivation.

## Frozen positive threshold

A future experiment that reuses the v1 scientific objective should preregister
its threshold before authority. The v1 threshold required all of the following:

- candidate not exact- or alpha-equivalent to a seed;
- genuine composition and decision topology;
- at least three primitive operations on a retained successful path;
- improvement on at least four generated cases and two structural families;
- at least two newly reached cases, or at least a tenfold median reduction of
  canonical search work;
- zero correctness regressions;
- zero hidden-assumption regressions;
- zero technical failures in the counted positive route;
- complete retention of null, losing, unsupported and budget-exhausted cases;
- two clean-checkout reproductions and one pinned-container reproduction.

The stretch objective was either a hundredfold canonical-work reduction on a
retained stress tier or two solved difficulty levels beyond every fixed
baseline.

These thresholds must never be repaired after outcome visibility. A new version
may change its contract only by preregistering a new plan **before** executing
that version.

## Comparison matrix and generated families

The frozen comparison matrix is:

1. primitive-only best-first search;
2. preregistered hand-written program;
3. randomized valid-program baseline;
4. no-composition ablation;
5. no-decision ablation;
6. learned rewrite program.

Canonical primitive and total-work ledgers are authoritative. Wall-clock time is
only an environment-qualified engineering diagnostic.

The deterministic generator defines 24 cases across three families:

- nested rational cancellation and quotient composition;
- factor-cancel-collect ordering;
- multi-stage rational/polynomial normalization with explicit nonzero
  assumptions.

Each family contains two variants at difficulty levels 3 through 6. Concrete
cases must not be materialized before the public-randomness stage.

## One-attempt authority

The retained execution uses the existing central CI workflow only as a thin
platform adapter. Repository semantics remain checkout-owned Gradle/Java/JUnit
logic.

For v1, a single-parent authority commit added only the canonical authority
manifest and the reserved authority branch was created exactly once. The
checkout-owned verifier bound the event, attempt number, branch, implementation
commit, authority commit and output constraints. Cancellation or technical
failure consumes that authority version; retry requires a separately reviewed
new version.

That rule remains important even though v1 ended in candidate formation: v1 is
closed evidence, not a development run waiting for a retry.

## Verification from a checkout

The ordinary lifecycle remains authoritative:

```bash
./gradlew test
./gradlew check
./gradlew --no-configuration-cache ciCheck
```

Focused showcase tests:

```bash
./gradlew :regelsuche-learning:test \
  --tests 'de.regelsuche.evolution.ProofCarryingShowcase*'

./gradlew :app:test \
  --tests 'de.regelsuche.evolution.ProofCarryingShowcase*'
```

The test surface covers strict artifact parsing, canonical hashes, retained
TRAIN evidence, deterministic selection, candidate-freeze ordering, drand
verification evidence, domain-separated seed derivation, deterministic case
generation, mathematical confirmation and the one-attempt authority contract.

## Next admissible step

The next scientific attempt must **not** be a v1 rerun.

Before any future authority is consumed:

1. finish the #609 engineering hardening and keep null-selection evidence
   first-class;
2. require a green post-merge `main` gate;
3. define a new preregistered showcase version with a new plan/authority
   identity;
4. explicitly declare whether deterministic TRAIN-only preflight is permitted;
5. if permitted, require byte-reproducible preflight evidence and at least one
   freeze-eligible terminal alternative before authority creation;
6. freeze the future version before any live public-randomness round is fetched;
7. consume exactly the first eligible verified round after the not-before
   boundary;
8. generate and execute the paired FINAL TEST exactly once;
9. publish the result whether positive, negative or null.

The engineering objective is therefore no longer to make v1 succeed. It is to
make the next experiment impossible to accidentally overclaim, silently retry
or lose the evidence needed to explain a null result.
