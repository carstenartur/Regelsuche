# Evolution population checkpoints and deterministic resume

## Scope

`EvolutionPopulationEngine` can stop after a completed TRAIN generation, emit a content-addressed `EvolutionPopulationCheckpoint`, and resume the unchanged study later. The same production mutation, evaluation and selection implementation is used before and after the checkpoint.

The checkpoint binds:

- the frozen study-plan hash;
- a canonical mutation-catalog hash;
- seed genome identities;
- the completed generation number;
- the selected executable population;
- all cached TRAIN evaluations;
- retained generation reports;
- cumulative mutation and TRAIN-evaluation budgets.

VALIDATION and FINAL TEST remain structurally `NOT_EVALUATED`.

## Resume contract

`resume(...)` rejects study-plan, seed or mutation-catalog substitution. It restores the selected population, evaluation cache, cumulative budgets and generation numbering. Mutation seeds therefore use the same plan identity, parent identity and absolute generation as an uninterrupted run.

A checkpoint JSON document uses strict duplicate-field detection and rejects unknown fields. Its constructor independently reconstructs the content hash and cross-checks selected population, cached evaluations, generation sequence and budget totals.

## Verification

```bash
./gradlew :regelsuche-learning:test \
  --tests de.regelsuche.evolution.EvolutionPopulationCheckpointTest
```

The characterization requires:

- byte-identical uninterrupted and resumed population-run evidence;
- the same total number of TRAIN evaluator invocations;
- byte-identical checkpoint JSON after a parse/serialize roundtrip;
- rejection of mutation-catalog substitution;
- rejection of unknown JSON fields;
- absence of VALIDATION and FINAL-TEST outcomes.

## Claim boundary

This phase establishes deterministic TRAIN checkpoint/resume mechanics only. It does not perform VALIDATION selection, freeze a selected configuration, execute FINAL TEST, establish evolutionary improvement, or authorize proof, novelty, promotion or Public Evidence claims.
