# Evolutionary search genome and study foundation

Issue #220 is intentionally split into reviewable stages. The genome foundation
makes evolved operator programs and search-policy candidates explicit,
replayable, executable and reject-by-default. The study-contract stage freezes
nested splits, population policy, fitness components and FINAL TEST rules before
population execution. Neither stage claims that an evolution campaign has
already produced a validated mathematical discovery.

## Versioned genome contracts

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

The retained genome schemas are:

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

The frozen study source strengthens that boundary by binding all 18 cases from
the candidate-independent benchmark and independently computing exact and
alpha-structural fingerprints. Every family, signature and fingerprint must be
unique across TRAIN, VALIDATION and FINAL TEST. A collision fails verification
instead of being silently removed.

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

## Frozen population study

The authoritative Phase-1 source is:

```text
research/evolution/evolution-study-source.json
```

Its Draft 2020-12 schema is:

```text
docs/schemas/regelsuche-evolution-study-source-v1.schema.json
```

Verify the study from a plain checkout with:

```bash
python3 scripts/verify-evolution-study.py
```

The study freezes before execution:

- the exact candidate-independent benchmark identity and canonical source hash;
- six TRAIN, six VALIDATION and six FINAL TEST cases;
- exact and alpha-structural split fingerprints;
- one pinned population seed, population size and generation budget;
- elite, offspring, replacement and duplicate-suppression policies;
- a minimum alpha-structural diversity requirement;
- named hard blockers that are never converted to scalar penalties;
- eight separately retained TRAIN-only fitness components;
- a versioned scalar selection profile whose weight signs are checked against
  component direction;
- VALIDATION-only population and hyperparameter selection;
- a full-diversity versus no-diversity ablation under identical budgets;
- one-time FINAL TEST access with a new study identity required for reruns;
- complete terminal-outcome policies for populations and FINAL TEST cases.

The current source deliberately remains:

```text
executionStatus: NOT_STARTED
finalTestAccess: NOT_ACCESSED
publicationAuthorized: false
```

Changing the benchmark, split cases, fingerprints, population policy, fitness
profile or FINAL TEST rule invalidates the source hash and requires a new study
identity before execution.

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
assumption templates from concrete pattern bindings. Population execution must
still enforce the compiled program's recorded budgets and route every retained
candidate through the existing semantic validation, counterexample, proof,
novelty and promotion gates.

## Remaining stages for issue #220

The frozen study source completes the contract portion of Phase 1 but does not
close #220. Follow-up slices still need to add:

1. deterministic population/island orchestration using the frozen policy;
2. a TRAIN-only fitness evaluator that writes every raw component and blocker;
3. canonical generation reports with structural-diversity and lineage data;
4. complete checkpoints, strict resume and uninterrupted/resumed equivalence;
5. VALIDATION-only selection and an immutable selection receipt;
6. one-time FINAL TEST evaluation with every terminal outcome retained;
7. promotion adapters that can only invoke existing validation, proof, novelty
   and release gates;
8. information-parity baselines, diversity ablation and pinned reproduction.
