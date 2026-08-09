# Populations of evolved rewrite programs

Status: TRAIN population with durable checkpoint/resume boundary and explicit execution-protocol identity for #220/#521/#613

## Combined candidate boundary

The population unit is `EvolutionRewriteProgramCandidate`, not a flat genome.
Its exact and alpha-structural identities bind both:

- the executable rule genome;
- the strategy topology that sequences, repeats, guards, prioritizes and prunes
  those rules.

This prevents two behaviorally different strategy programs over the same rules
from collapsing into one candidate and prevents renaming-only variants from
dominating the population.

## Preregistered study plan

`EvolutionRewriteProgramStudyPlan` freezes before TRAIN execution:

- the split manifest and exact TRAIN suite;
- the mutation catalog and permitted topology mutations;
- all seed candidate identities;
- population, diversity, elite, lineage and parallelism policy;
- raw fitness weights;
- mutation, TRAIN, VALIDATION, FINAL TEST and checkpoint budgets;
- the exactly-once FINAL TEST policy.

The study remains `NOT_STARTED`; proof, external novelty, promotion and Public
Evidence remain `NOT_EVALUATED`.

The plan additionally fails before execution when:

- its study ID differs from the split manifest;
- TRAIN case IDs or families differ between manifest and suite;
- a seed uses a non-TRAIN scope or another objective;
- the mutation catalog references a rule gene absent from any seed lineage;
- weights do not sum to 1000 permille;
- source identities drift.

The strict schema is
[`regelsuche-evolution-rewrite-program-study-plan-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-study-plan-v1.schema.json).

## Population execution protocol

The study plan describes **what** is evaluated. It historically did not identify
all implementation mechanics that decide **how** a bounded proposal set becomes
accepted offspring and survivors. That distinction matters because a scheduling
change can alter a deterministic population even when the study, seed and
mutation catalog stay unchanged.

`EvolutionRewriteProgramPopulationExecutionProtocol` therefore gives these
mechanics a separate, content-addressed identity without changing the historical
`EvolutionRewriteProgramStudyPlan/v1` schema. It binds:

- population-engine implementation class;
- deterministic mutator implementation class;
- proposal ordering policy;
- offspring scheduling policy;
- generated-pool multiplier;
- mutation-seed derivation policy;
- survivor-selection and tie-breaking policy.

`EvolutionRewriteProgramPopulationExecutionPlan` binds one immutable study-plan
hash to one execution-protocol hash before execution. A retained run and a
checkpoint can then be wrapped by
`ExecutionProtocolBoundRetainedEvolutionRewriteProgramPopulationRun` and
`ExecutionProtocolBoundEvolutionRewriteProgramPopulationCheckpoint` so neither
an uninterrupted run nor resume can silently switch execution semantics.

### Legacy identity

`legacyV1()` names the exact mechanics used before #613:

```text
proposal ordering: KEY_ASCENDING_THEN_GLOBAL_SEED_ROTATION_V1
offspring scheduling: ROTATED_PREFIX_V1
generated pool multiplier: 2
mutation seed: STUDY_HASH_GENERATION_PARENT_HASH_SHA256_PREFIX64_V1
survivor selection: FITNESS_DESC_NODES_ASC_HASH_ASC_UNIQUE_ALPHA_ELITES_V1
```

The first #613 tranche does **not** alter those mechanics. The ordinary historical
runner remains unchanged, and the new execution-bound legacy runner delegates to
that same engine/mutator path. Tests require the inner retained run from both
paths to be byte-identical and require uninterrupted versus checkpoint/resume
execution-bound results to be identical.

A future policy identifier, `STRATIFIED_MUTATION_KIND_V1`, is declared so a later
reviewed tranche can receive a distinct protocol hash. It is deliberately not
executable yet: the execution-bound runner rejects every protocol other than the
exact `legacyV1()` identity. Declaring a policy name is not permission to execute
unfinished semantics.

The strict schemas are:

- [`regelsuche-evolution-rewrite-program-population-execution-protocol-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-population-execution-protocol-v1.schema.json)
- [`regelsuche-evolution-rewrite-program-population-execution-plan-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-population-execution-plan-v1.schema.json)
- [`regelsuche-evolution-rewrite-program-execution-protocol-bound-retained-run-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-execution-protocol-bound-retained-run-v1.schema.json)
- [`regelsuche-evolution-rewrite-program-execution-protocol-bound-checkpoint-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-execution-protocol-bound-checkpoint-v1.schema.json)

Historical v1 artifacts retain their original meaning and need no rewritten hash.
A future showcase may use a new scheduling protocol only when its execution plan
is frozen before that version's TRAIN/preflight/authority execution.

## Deterministic population execution

`EvolutionRewriteProgramPopulationEngine` performs only TRAIN work:

1. evaluate unevaluated combined candidates;
2. exclude candidates carrying hard blockers;
3. enumerate bounded topology mutations with deterministic study/generation/
   parent seeds;
4. retain accepted and rejected lineage;
5. evaluate children in parallel but collect results in canonical candidate
   order;
6. retain elites;
7. suppress exact and combined alpha-structural duplicates;
8. select by frozen scalar profile, then smaller topology, then candidate hash;
9. stop on completion, extinction, diversity failure, budget exhaustion or
   stagnation.

Raw named components in the TRAIN evidence remain authoritative. The scalar
profile is used only for the frozen population ordering. Missing required
components and evaluator identity drift are blockers, not zero-valued successes.

## Flagship evaluator boundary

The population engine deliberately accepts a narrow evaluator interface so its
selection, diversity, parallelism and resume mechanics can be tested with small
synthetic fixtures. That generic entrypoint does not authorize a flagship
result. The production campaign must additionally bind the exact evaluator
protocol and implementation identity, and the selected evaluator must carry the
same protocol hash into every retained TRAIN evidence root.

Protocol binding is supplied by the following #521 slice. Matched primitive,
program-internal, outer-search and exact-audit work remains a separate mandatory
fairness gate in #527. Until both layers are green, a population result is
mechanical development evidence only and cannot advance to VALIDATION or consume
the one-time FINAL TEST.

## Complete generation evidence

Every generation retains:

- all evaluated candidate evidence roots;
- selected combined candidate identities;
- parent, child, child-plan and combined alpha identities;
- mutation kind and proposal key;
- rejected proposals and their blockers;
- combined alpha-structure count;
- cumulative mutation and TRAIN-evaluation resources;
- explicit terminal or continuation status.

An extinct generation is valid negative evidence and therefore has an empty
selected-candidate list rather than disappearing.

The strict generation schema is
[`regelsuche-evolution-rewrite-program-generation-report-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-generation-report-v1.schema.json).

## Checkpoint and resume

A checkpoint binds:

- study, TRAIN suite and mutation-catalog identities;
- seed candidates;
- complete current population values;
- all retained candidate evaluations;
- all completed generation reports;
- cumulative resource counters;
- the deterministic next generation;
- absent VALIDATION and FINAL TEST outcomes.

Resume first compares every frozen source identity. It reuses retained TRAIN
evaluations and executes only candidates not already represented. Focused tests
require uninterrupted and resumed runs to produce the same canonical population
run and require the sum of pre-checkpoint and post-resume evaluator calls to
equal the uninterrupted count.

For execution-protocol-bound studies, the additive checkpoint wrapper also binds
the execution plan and protocol. A checkpoint produced under one scheduling
identity cannot be resumed through another execution-bound runner even if the
historical checkpoint's study hash is unchanged.

### Durable process-independent artifact

`EvolutionRewriteProgramCheckpointArtifact` persists that existing checkpoint
semantics without creating a second population model. A committed checkpoint
directory has exactly three regular files:

- `checkpoint.json` — the existing canonical checkpoint/root inventory;
- `state.json` — the complete current candidates, candidate evaluations and
  completed generation reports needed to reconstruct the immutable checkpoint;
- `checkpoint-artifact-manifest.json` — written last and binding the exact
  checkpoint/state roots, payload byte hashes, byte lengths and commit protocol.

The commit protocol is `MANIFEST_LAST_ATOMIC_RENAME`. Payloads are written via a
temporary file, forced to storage and atomically renamed; the manifest is
committed only after both payloads. Therefore a directory without the manifest
is not a completed checkpoint artifact.

The loader is intentionally fail-closed. It rejects:

- missing or unexpected files;
- symbolic-link entries or symbolic-link ancestry;
- oversized or malformed UTF-8 payloads;
- duplicate JSON keys, trailing JSON or unknown fields;
- byte-length or SHA-256 mismatch;
- non-canonical embedded genome/program JSON;
- candidate, evaluation, generation or checkpoint root mismatch;
- missing/reordered generation evidence;
- any VALIDATION or FINAL TEST outcome in a TRAIN checkpoint.

Genome and program-plan values are reconstructed with their existing strict
codecs. Candidate/evaluation/generation constructors then recompute the semantic
content hashes. Finally the reconstructed `PopulationCheckpoint` must serialize
byte-identically to `checkpoint.json`. The durable boundary therefore cannot
turn a different process state into a valid resume merely by editing the
manifest.

Focused artifact tests require:

- persist → reload → resume to match the uninterrupted canonical population run;
- pre-checkpoint plus reloaded-resume evaluator calls to equal uninterrupted
  evaluator calls;
- repeated exports of one checkpoint to be byte-identical;
- state tampering, missing manifest and foreign directory entries to fail
  closed before resume.

The strict checkpoint, durable-artifact/state and run schemas are:

- [`regelsuche-evolution-rewrite-program-population-checkpoint-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-population-checkpoint-v1.schema.json)
- [`regelsuche-evolution-rewrite-program-checkpoint-artifact-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-checkpoint-artifact-v1.schema.json)
- [`regelsuche-evolution-rewrite-program-checkpoint-state-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-checkpoint-state-v1.schema.json)
- [`regelsuche-evolution-rewrite-program-population-run-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-population-run-v1.schema.json)

## Claim boundary

This layer establishes deterministic TRAIN-only population mechanics, explicit
execution-protocol identity for new experiments and process-independent
checkpoint/resume semantics. It does not establish fair work-accounted
self-improvement, VALIDATION selection, exactly-once FINAL TEST utility, formal
proof or external novelty.

Binding legacy execution semantics is not evidence that a future scheduling
policy is better. Such a policy needs a separate reviewed implementation and
TRAIN-only characterization before a future held-out experiment is frozen.
