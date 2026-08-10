# Search Intelligence

This page is the entry point for the mechanisms that make Regelsuche's search
selective and diagnosable: profiles, goals, transposition memory, structural
diversity, bounded reachability and universal patterns.

For roadmap and historical context see
[`search-intelligence-roadmap.md`](search-intelligence-roadmap.md). This page
describes the currently shipped behavior.

## Profile + Goal

A `SearchProfile` selects a strategy and heuristic preset. A
`TransformationGoal` independently selects the cost model used to compare
candidates.

| Profile | Strategy | Default goal |
| --- | --- | --- |
| `FAST_SIMPLIFY` | BestFirst | `SIMPLIFY` |
| `DISCOVERY` | Hybrid | `SIMPLIFY` |
| `DIVERSITY_DISCOVERY` | target-blind structural quality-diversity | `SIMPLIFY` |
| `TEACHING` | Beam | `TEACHING_FRIENDLY` |
| `PROOF_ORIENTED` | A* | `PROOF_FRIENDLY` |
| `EXHAUSTIVE_SMALL` | MCTS | `SIMPLIFY` |
| `DISCOVERY_PLUS` | BestFirst + transposition table | `SIMPLIFY` |
| `EQUALITY_SATURATION` | EGraph saturation | `SIMPLIFY` |

The web UI exposes profile and goal separately. `POST /api/search` accepts both;
when `goal` is omitted, the profile default applies. Usage guidance is in
[`user-workflows.md`](user-workflows.md#goal-dropdown).

## Transposition table

`DISCOVERY_PLUS` activates the `TranspositionTable`. It recognizes canonical
states, prunes already-known-better paths and may retain a non-improving path
when it introduces a new rule combination. Decisions are exposed at
`/api/memory/pruning`.

## Structural diversity

`DIVERSITY_DISCOVERY` is a deterministic target-blind diagnostic profile. At
each depth it retains at most one elite per structural cell instead of reducing
all survivors to one scalar ranking. Cells use only observable TRAIN-side
structure:

- AST-size band;
- expansion debt;
- last rewrite kind;
- denominator-count band;
- power-count band.

No target identity is inspected. The profile is a bounded
MAP-Elites-style control for scalar-fitness valleys, not a claim to implement
the complete MAP-Elites algorithm.

## Bounded reachability and historical atlas

`BoundedRewriteReachabilityOracle` performs deterministic breadth-first
traversal over the directed successors of a frozen `TransformationEngine`. It
retains a shortest witness and reports exactly one of:

- `REACHABLE`;
- `UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE`, only after complete finite-closure
  exhaustion;
- `BUDGET_INCONCLUSIVE`, when a depth or visited-state bound blocks an unseen
  successor.

The historical rediscovery atlas applies this boundary to a versioned 14-case
corpus and compares four explicitly separated layers:

1. production-inventory reachability;
2. target-blind scalar best-first search;
3. target-blind structural-diversity search;
4. target-guided search as a diagnostic control.

Preregistered controls add the generic
`DifferenceOfSquaresPreparationOperator` for the Sophie-Germain case and one
curated completing-the-square recognition rule. These controls distinguish a
missing production edge from representation or matcher failure; they never
count as autonomous rediscovery.

The source corpus and strict schema are:

- `regelsuche-experiments/src/main/resources/de/regelsuche/benchmark/historical-rediscovery-corpus.json`;
- `docs/schemas/regelsuche-historical-rediscovery-corpus-v1.schema.json`.

Generated JSON and Markdown retain the corpus hash, witnesses, rule IDs,
primitive work, search metrics, directionality and one evidence-derived primary
diagnosis per case. The aggregate assessment is one of
`USEFUL_DIAGNOSTIC_STEP`, `USEFUL_BUT_INCOMPLETE` or `INSUFFICIENT_SIGNAL`.

Generate the atlas with:

```bash
./gradlew :regelsuche-experiments:generateHistoricalRediscoveryAtlas
```

The output is written under
`regelsuche-experiments/build/reports/historical-rediscovery/`. The ordinary
checkout-owned merge gate remains:

```bash
./gradlew --no-configuration-cache ciCheck
```

### Claim boundary

The oracle and guided control receive published targets and are therefore
strictly diagnostic. Scalar and diversity searches are target-blind, but the
corpus is still a known historical benchmark. Results establish only bounded
reachability and search-policy behavior for the frozen representation,
inventory and budgets. They do not establish external novelty, autonomous
rediscovery or publication priority.

## Universal patterns

`GlobalMemoryService` scores canonical states by visit count, distinct rule
combinations and age. The leading entries are exposed through:

| Surface | Use |
| --- | --- |
| `GET /api/memory/universal` | JSON list of `patterns[]` and `ruleCoverage[]` |
| Suchgedächtnis → *Universelle Muster* | supporting best path and promotion context |

High-universality states are macro-rule promotion targets. Promotion rules are
documented in [`macro-rules.md`](macro-rules.md).

## Related docs

- [`search-strategies.md`](search-strategies.md) — strategy details.
- [`equality-saturation.md`](equality-saturation.md) — the EGraph layer.
- [`didactic-ranking.md`](didactic-ranking.md) — `TEACHING_FRIENDLY` ranking.

## Equality-saturation runtime metrics

`EQUALITY_SATURATION` additionally reports:

- `classesScanned`, `nodesScanned`, `candidateClassesSkipped`;
- `matchesFound`;
- `matcherCacheHits`, `matcherCacheMisses`;
- `saturationIterations`, `rulesFired`.

The fields are available in `SaturationStats` and the benchmark JSON/Markdown
artifacts.
