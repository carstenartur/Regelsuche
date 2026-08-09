# Stratified rewrite-program mutation scheduling

Status: TRAIN-only scheduler implementation for issue #613. This is a new
execution protocol; it does not reinterpret or rerun showcase v1.

## Why the scheduler is versioned

`EvolutionRewriteProgramStudyPlan/v1` freezes the allowed mutation operators,
mutation catalog, seeds, population policy, fitness weights and budgets. It does
not by itself identify the algorithm that maps a deterministically ordered
proposal set to the bounded accepted-offspring prefix.

That missing identity matters when `maxOffspringPerLineage` is small. Under the
historical algorithm a valid proposal can consume one of those slots before the
population engine discovers that its mutation kind is not preregistered. A
later valid and permitted kind can therefore be starved even though neither the
study plan nor its hash changed.

The historical behavior remains named by:

```text
population engine semantics: LEGACY_POPULATION_ENGINE_V1
mutator semantics: ROTATED_PREFIX_MUTATOR_V1
offspring scheduling: ROTATED_PREFIX_V1
mutator implementation: DeterministicRewriteProgramMutator
```

The new behavior has a different content-addressed identity:

```text
population engine semantics: PROTOCOL_DRIVEN_POPULATION_ENGINE_V2
mutator semantics: STRATIFIED_MUTATION_KIND_MUTATOR_V2
offspring scheduling: STRATIFIED_MUTATION_KIND_V1
mutator implementation: StratifiedMutationKindRewriteProgramMutator
```

The execution-bound runner accepts only the exact hash of one of its implemented
protocols. Reusing the STRATIFIED enum with another implementation class creates
a different protocol hash and fails closed.

## `STRATIFIED_MUTATION_KIND_V1`

The v2 scheduler keeps the historical proposal surface and work window:

1. enumerate the same bounded proposals;
2. sort by the same canonical proposal key;
3. apply the same global deterministic seed rotation;
4. keep the same `maxProposals` limit;
5. preflight construction, compilation and identity without reserving another
   proposal's alpha structure;
6. consider only mutation kinds preregistered in the study plan for offspring
   allocation;
7. in the first pass, select at most one structurally new child per represented
   permitted mutation kind while capacity remains;
8. in the second pass, fill spare `maxAccepted` capacity in the original rotated
   proposal order;
9. reserve an alpha structure only when a proposal is actually selected;
10. retain every proposal outcome and blocker in the canonical mutation batch.

If capacity is smaller than the number of represented valid mutation kinds, the
kinds whose first selectable proposal occurs earliest in the frozen rotated
order win. The result is therefore deterministic rather than dependent on hash
iteration order or thread scheduling.

A regression fixture covers the important alpha-equivalence edge: an
unpreregistered `APPEND_SOURCE` proposal that is alpha-equivalent to a later
permitted `PREPEND_SOURCE` proposal may be rejected, but it cannot reserve the
alpha structure and poison the permitted proposal.

The generic scheduler contains no showcase freeze predicate, no VALIDATION or
FINAL TEST data and no threshold for a desired result. Its only fairness target
is coverage of mutation operators already declared by the study.

## Legacy compatibility

`DeterministicRewriteProgramMutator.mutate(...)` remains the historical rotated
prefix path. The only class-level change needed for v2 is that the class is
subclassable; the legacy method is not redirected through the new scheduler.

`StratifiedMutationKindRewriteProgramMutator` is a distinct implementation type
whose `mutate(...)` delegates to the explicitly named stratified method. The
population engine itself remains unchanged and receives the protocol-specific
mutator through its existing constructor.

Tests retain the stronger compatibility checks from the legacy protocol work:

- the execution-bound legacy inner run must equal the historical retained run;
- uninterrupted legacy execution must equal legacy checkpoint/resume;
- uninterrupted stratified execution must equal stratified checkpoint/resume;
- protocol or implementation substitution changes identity and is rejected.

## TRAIN-only fitness-valley diagnostics

`EvolutionRewriteProgramTrainDiagnostics/v1` is a separate content-addressed
artifact. `PopulationRun/v1` is not extended, so historical bytes retain their
original meaning.

The diagnostic root binds:

- study-plan hash;
- execution-plan hash;
- execution-protocol hash;
- execution-bound retained-run hash;
- underlying population-run hash.

For every mutator invocation it retains:

- generation and parent candidate hash;
- semantic mutation-batch content hash;
- SHA-256 of the exact canonical mutation-batch JSON bytes;
- the canonical mutation-batch JSON itself, including every accepted/rejected
  proposal and blocker.

For every generation it derives:

- eligible proposal counts by mutation kind;
- actual accepted offspring counts by mutation kind from lineage evidence;
- proposals rejected *only* because `maxAccepted` was exhausted;
- generated alpha-structure hashes;
- which newly generated alpha structures survived survivor selection;
- maximum lineage depth from a seed reached in that generation.

For every seed and lineage child admitted to the population it records generic
program facts:

- node count;
- composition topology present/absent;
- decision topology present/absent;
- conservative minimum structural primitive-path depth;
- earliest observed generation;
- minimum observed lineage depth from a seed.

A content-addressed candidate may be reached again in a later generation or by
a different lineage. Repeated observations therefore merge only the two
observation fields above by their minima. Candidate hash, alpha-structural hash,
plan hash and all structural facts must remain identical; any disagreement
fails diagnostic construction closed.

These facts come from `EvolutionRewriteProgramStructureAnalyzer`, the same
representation-level analysis used by the showcase freezer after extraction.
They are descriptive evidence only; they do not alter TRAIN fitness.

`dataScope` is fixed to `TRAIN_ONLY`. The diagnostic API receives the TRAIN
suite and population evidence only; it has no VALIDATION or FINAL TEST payload.
The strict schema is
[`regelsuche-evolution-rewrite-program-train-diagnostics-v1.schema.json`](schemas/regelsuche-evolution-rewrite-program-train-diagnostics-v1.schema.json).

## Checkpoint boundary

The execution protocol itself supports deterministic checkpoint/resume and tests
require the resumed execution-bound run to equal an uninterrupted run for both
implemented protocols.

The additional diagnostic trace is currently produced by
`runWithDiagnostics(...)` during an uninterrupted TRAIN characterization run.
It is deliberately not smuggled into the historical checkpoint schema. If a
future authority-bearing campaign requires diagnostic-trace continuation across
process restarts, that trace needs its own separately reviewed durable checkpoint
binding rather than changing `PopulationCheckpoint/v1` after the fact.

## Showcase-v2 gate

This implementation is not permission to run a future showcase v2. Before any
v2 preflight or authority execution:

1. the scheduler implementation and schemas must be merged through the ordinary
   checkout-owned gate;
2. the exact execution-protocol hash must be frozen;
3. the future study/execution plan must bind that hash before execution;
4. TRAIN-only characterization may be inspected, but no VALIDATION or FINAL TEST
   data may influence the freeze;
5. showcase v1 evidence and claims remain immutable.
