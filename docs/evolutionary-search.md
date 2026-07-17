# Evolutionary search genome foundation

Issue #220 is intentionally split into reviewable stages. This foundation stage
makes evolved operator programs and search-policy candidates explicit,
replayable, executable and reject-by-default. It does **not** claim that an
evolution campaign has already produced a validated mathematical discovery.

## Versioned contracts

The implementation lives in `regelsuche-learning` under
`de.regelsuche.evolution`.

- `EvolutionGenome` is the canonical v1 candidate envelope.
- `EvolutionGenomeCodec` performs strict JSON replay. Unknown properties,
  unsupported enum values and hash mismatches fail closed.
- `EvolutionGenomeValidator` produces a canonical preflight report with named
  blockers.
- `EvolutionGenomeCompiler` converts an accepted genome into normal
  `RewriteRule` instances. Compiled candidates deliberately return
  `isEquivalencePreservingByConstruction() == false`; evolution cannot grant
  proof status.
- `DeterministicGenomeMutator` enumerates bounded mutations from a pinned seed
  and retains every accepted or rejected attempt in a canonical lineage batch.

The retained schemas are:

- `docs/schemas/regelsuche-evolution-genome-v1.schema.json`
- `docs/schemas/regelsuche-evolution-preflight-v1.schema.json`
- `docs/schemas/regelsuche-evolution-mutation-batch-v1.schema.json`

## Identity and provenance

A genome has two hashes with different purposes.

`contentHash` binds the complete canonical payload, including TRAIN partition
hashes and seed lineage. `alphaStructuralHash` ignores placeholder names, gene
IDs and training provenance while retaining executable structure, ranking
features, safety policy, resource budgets and capabilities. Population code can
therefore suppress alpha-equivalent candidates without losing reproducible
provenance.

Every derived candidate records the parent `contentHash` in
`seedGenomeHashes`. Mutation batches additionally retain the pinned seed,
proposal order, mutation kind, child hashes and all preflight blockers.

## Information boundary

Genome formation is restricted to a `TrainingScope` whose only legal split is
`TRAIN`. The scope binds hashes for the corpus, family partition, signature
partition and feature schema. The v1 genome has no fields for hidden targets,
TEST labels, expected answers or post-hoc discovery outcomes.

This type-level boundary is necessary but not sufficient for the complete
issue. The campaign runner still has to prove that its input adapters only
supply TRAIN material and that VALIDATION/TEST evaluators remain separate.

## Hard preflight blockers

Preflight rejection occurs before fitness evaluation. In particular, the gate
rejects:

- unparsable patterns and unbound target or assumption placeholders;
- identity rewrites, alpha-duplicate rules and rewrite cycles of any length;
- AST, per-step growth, application and program-length budget violations;
- disabled cycle, growth, applicability, duplicate or deterministic-ordering
  guards;
- target-directed features in open-target genomes and fitness weights whose
  sign contradicts their declared direction;
- missing semantic validation, counterexample, proof/certificate or holdout
  obligations;
- unsupported or unknown assumption kinds;
- alpha-equivalent mutations, missing parent lineage and mutation-shape drift;
- assumption removal without an explicit discharge certificate. The v1
  mutator enumerates removal attempts, but the gate rejects them until a later
  certificate contract exists.

These are blockers, not soft penalties. Search cannot trade them against a
higher provisional fitness score.

## Bounded deterministic mutations

The v1 mutator supports:

- placeholder generalization and specialization;
- exact pattern-program composition;
- assumption addition and attempted removal;
- rewrite reversal where the parent gene marks it reversible;
- ranking-feature addition and removal.

Proposals are generated from canonical input order, sorted by a stable proposal
key and rotated by a deterministic 64-bit seed mixer. `MutationLimits` bounds
both evaluated proposals and accepted children. Accepted children must be
structurally unique and pass the normal preflight gate.

## Replay and execution

```java
EvolutionGenome genome = new EvolutionGenomeCodec().read(path);
EvolutionGenomeValidator.ValidationReport preflight =
    new EvolutionGenomeValidator().validate(genome);
if (!preflight.accepted()) {
    throw new IllegalArgumentException(preflight.blockerCodes().toString());
}
EvolutionGenomeCompiler.CompiledProgram program =
    new EvolutionGenomeCompiler().compile(genome);
```

The compiler exposes ordinary `RewriteRule` objects and instantiates symbolic
assumption templates from concrete pattern bindings. Search integration must
still enforce the compiled program's recorded budgets and route every retained
candidate through the existing semantic validation, counterexample, proof,
novelty and promotion gates.

## Remaining stages for issue #220

The foundation does not close #220. Follow-up slices still need to add:

1. deterministic populations and islands with structural-diversity metrics;
2. a TRAIN-only fitness evaluator with explicit component and penalty records;
3. frozen family and structural-signature TRAIN/VALIDATION/TEST splits;
4. campaign checkpoints, resume/replay and resource-budget accounting;
5. promotion adapters that can only invoke existing validation, proof, novelty
   and release gates;
6. bounded benchmark campaigns comparing evolved candidates with non-evolved
   baselines and publishing negative as well as positive results.
