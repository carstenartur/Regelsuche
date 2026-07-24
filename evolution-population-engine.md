# Deterministic TRAIN population engine

Phase 2 of issue #220 introduces bounded population orchestration without
crossing the preregistered TRAIN/VALIDATION/FINAL-TEST boundary established by
the study plan.

## Scope

`EvolutionPopulationEngine` accepts:

- one immutable `EvolutionStudyPlan`;
- exactly the seed genomes bound by that plan;
- a deterministic mutation catalog;
- a `TrainFitnessEvaluator` that receives only an executable genome.

The engine does not receive validation cases, FINAL TEST cases, hidden targets,
post-hoc labels, proof outcomes or promotion decisions.

## Deterministic population policy

For every generation the engine:

1. evaluates pending genomes in a bounded fixed-size executor;
2. collects results in canonical genome-hash order, independent of completion
   order;
3. retains named raw TRAIN fitness components;
4. treats safety, leakage, correctness and evaluator failures as hard blockers;
5. keeps the preregistered number of elites;
6. enumerates bounded deterministic mutations from ranked parents;
7. rejects mutation kinds not declared by the frozen study plan before fitness
   evaluation;
8. suppresses content-identical and alpha-structurally identical candidates
   across the complete population;
9. enforces the maximum offspring count per parent lineage;
10. selects the next population by the frozen scalar profile with content hash
    as the final deterministic tie-breaker.

The scalar score is derived only after every declared raw component is present.
Undeclared or missing components block the candidate. Raw components remain the
authoritative retained values.

## Explicit terminal outcomes

A run ends with exactly one of:

- `COMPLETED`;
- `EXTINCT`;
- `STAGNATED`;
- `DIVERSITY_FLOOR_UNMET`;
- `MUTATION_BUDGET_EXHAUSTED`;
- `TRAIN_EVALUATION_BUDGET_EXHAUSTED`.

No hidden retry is performed after a terminal outcome.

## Evidence contracts

Each generation is represented by
`regelsuche.evolution-generation-report/v1`. It retains:

- every evaluated or blocked candidate;
- the raw named fitness map and derived score;
- the selected population hashes;
- accepted parent/child lineage edges;
- rejected mutations and blocker codes;
- cumulative mutation and TRAIN-evaluation budgets;
- structural-diversity count and terminal/continuation outcome.

The root `regelsuche.evolution-population-run/v1` binds the frozen study-plan
hash, seed identities, ordered generation-report hashes, final population
identities, resource use and terminal outcome.

Both schemas reject unknown fields. The root run explicitly fixes validation,
FINAL TEST, proof, external novelty, promotion and Public Evidence to
`NOT_EVALUATED`.

## Characterization

The JUnit characterization suite verifies:

- byte-identical output under pinned seeds and parallel execution;
- alpha-structural uniqueness across every selected population;
- per-parent offspring limits;
- preregistered mutation-kind enforcement;
- separation of hard blockers from scalar fitness;
- retention of all raw components;
- fail-closed resource-budget outcomes;
- absence of selection and FINAL-TEST result fields.

Run it from an ordinary checkout:

```bash
./gradlew :regelsuche-learning:test \
  --tests de.regelsuche.evolution.EvolutionPopulationEngineTest
```

The tests are part of the root `./gradlew test` lifecycle. No dedicated GitHub
Actions workflow is required.

## Claim boundary

This phase demonstrates deterministic bounded population mechanics and
machine-verifiable TRAIN evidence. It does **not** demonstrate evolutionary
improvement, useful discovery, VALIDATION selection, one-time FINAL TEST
performance, checkpoint/resume equivalence, proof, mathematical novelty,
promotion readiness or Public Evidence eligibility.

Those claims remain blocked until the later phases of issue #220 produce their
separate frozen artifacts and empirical results.
