# Real TRAIN search fitness

`SearchTrainFitnessEvaluator` connects the deterministic population engine to
actual Regelsuche search executions. It does not estimate search utility from a
genome hash or from synthetic test values.

## Frozen input

A `regelsuche.evolution-train-search-suite/v1` document binds:

- a stable suite identifier;
- non-empty, uniquely identified TRAIN cases;
- each input expression and exact target expression;
- the complete bounded `SearchHeuristic`;
- a canonical content hash.

The suite contains no VALIDATION or FINAL TEST cases. Every genome must also
carry a `TrainingScope` whose source split is `TRAIN`.

## Paired execution

For each case, the evaluator runs the same production search implementation
twice:

1. the ordinary `AstRewriteTransformationEngine` rule set;
2. the same rule set plus the rules compiled from the genome.

Both sides use the same target, scorer, canonicalizer and effective search
budget. Genome limits may tighten candidate generation and path depth, but may
never enlarge the frozen suite budget.

The retained case evidence contains:

- baseline and candidate terminal status;
- whether the exact target was reached;
- reached path length;
- explored-state count;
- whether the candidate newly solved the case;
- whether it introduced a correctness regression.

A case that the baseline reaches but the candidate does not produces a hard
`TRAIN_CORRECTNESS_REGRESSION:<caseId>` blocker. The blocker remains separate
from scalar fitness.

## Supported components

All values are integral permille in `[-1000, 1000]` and higher is better.

| Component | Formation |
| --- | --- |
| `TRAIN_CASES_NEWLY_SOLVED` | fraction of suite cases reached only by the candidate |
| `SUPPORT` | fraction of all suite cases reached by the candidate |
| `TRAIN_PATH_LENGTH_REDUCTION` | aggregate paired path-length delta divided by aggregate baseline path length |
| `TRAIN_EXPLORED_STATE_REDUCTION` | aggregate paired explored-state delta divided by aggregate baseline states |
| `ASSUMPTION_SIMPLICITY` | inverse assumption count relative to `maxProgramLength` |
| `CANDIDATE_COMPLEXITY` | inverse source/target AST-node count relative to `maxAstNodes` |
| `PROOF_COST_PROXY` | inverse retained evidence-obligation count relative to the bounded program size |

The evaluator deliberately does not manufacture values for
`STRUCTURAL_DIVERSITY`, `PROJECT_NOVELTY` or `COUNTEREXAMPLE_RISK`. If a study
plan requests a component that this TRAIN evaluator cannot establish, the
component is retained as zero and a fail-closed
`UNSUPPORTED_TRAIN_FITNESS_COMPONENT:<component>` blocker is emitted.
Population-wide diversity remains owned by `EvolutionPopulationEngine`;
project/external novelty and counterexample search remain separate lifecycle
stages.

## Evidence boundary

The canonical `regelsuche.evolution-train-fitness/v1` document binds the suite,
genome, complete paired case measurements, raw components and blockers. It
requires:

```text
validationStatus=NOT_EVALUATED
finalTestStatus=NOT_EVALUATED
```

It contains no validation selection, final-test outcome, proof conclusion,
novelty conclusion, promotion decision or Public Evidence decision.

## Local verification

```bash
./gradlew :regelsuche-learning:test \
  --tests de.regelsuche.evolution.SearchTrainFitnessEvaluatorTest
./gradlew check
```

Schemas:

- `docs/schemas/regelsuche-evolution-train-search-suite-v1.schema.json`
- `docs/schemas/regelsuche-evolution-train-fitness-v1.schema.json`
