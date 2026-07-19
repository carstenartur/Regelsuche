# Evolution study and split contracts

Issue #220 requires a strict boundary between candidate formation, configuration selection and the one-time FINAL TEST evaluation. Phase 1 introduces two versioned, fail-closed contracts without claiming that a population run has already occurred.

## Split manifest

`regelsuche.evolution-split-manifest/v1` freezes three non-empty partitions:

- `trainCases` for mutation and provisional fitness;
- `validationCases` for later population and hyperparameter selection;
- `finalTestCases` for one evaluation after selection is frozen.

Every case retains only immutable identities:

- case and family IDs;
- exact and alpha-normalized structural signature hashes;
- input hash;
- hidden-target hash.

Families, case IDs, exact signatures, alpha signatures, inputs and hidden targets must be pairwise disjoint across TRAIN, VALIDATION and FINAL TEST. The manifest computes canonical family- and signature-partition hashes and exposes exactly one `EvolutionGenome.TrainingScope`, whose `sourceSplit` is always `TRAIN`.

## Study plan

`regelsuche.evolution-study-plan/v1` binds:

- the split-manifest content hash;
- seed genomes that use exactly the manifest-derived TRAIN scope;
- the admitted deterministic mutation operators;
- population size, generations, elites, structural-diversity floor, per-lineage offspring limit, parallelism and random seed;
- named fitness components with frozen weights;
- mutation, TRAIN, VALIDATION, FINAL TEST and checkpoint budgets;
- the one-time FINAL TEST policy.

The v1 plan is preregistration only. It requires:

```text
status=NOT_STARTED
finalTestPolicy=ONE_TIME_AFTER_FROZEN_VALIDATION_SELECTION
proofStatus=NOT_EVALUATED
externalNoveltyStatus=NOT_EVALUATED
promotionStatus=NOT_EVALUATED
publicEvidenceStatus=NOT_EVALUATED
```

There is deliberately no selected-configuration hash, FINAL TEST outcome, checkpoint, generation report or promotion handoff in this artifact. Those belong to later phases and must reference the frozen plan rather than mutate it.

## Local reproduction

The canonical v1 example is generated through Gradle:

```bash
./gradlew :regelsuche-learning:writeEvolutionStudyPlan
```

Output:

```text
regelsuche-learning/build/reports/evolution-study-plan/
  evolution-split-manifest.json
  evolution-study-plan.json
```

Both files are read back through the strict codec. Unknown fields, alternative statuses, cross-split collisions and inconsistent content hashes fail closed. The task is part of the module `check` lifecycle and therefore runs from the central repository verification without a dedicated GitHub Actions workflow.

## Claim boundary

These contracts establish only that the study design, split isolation, budgets and selection policy are frozen and replayable. They do not establish evolutionary improvement, population diversity in practice, checkpoint equivalence, VALIDATION selection, a FINAL TEST result, proof, novelty, promotion or Public Evidence.
