# Search Intelligence

This page is the entry point for everything that makes Regelsuche's
search "smart": profiles, goals, the transposition table that powers
DISCOVERY_PLUS, structural diversity, bounded reachability diagnostics,
and the universal-patterns surface.

For the long-form roadmap and historical context see
[`search-intelligence-roadmap.md`](search-intelligence-roadmap.md);
this page focuses on the **currently shipped** capabilities.

## Profile + Goal

A `SearchProfile` bundles a `SearchHeuristic` preset, a strategy and a
default `TransformationGoal`. The two axes are independent:

```
SearchProfile  →  picks strategy + heuristic knobs
TransformationGoal  →  picks the cost model used to compare candidates
```

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

The web UI exposes both as separate dropdowns. `POST /api/search`
accepts both fields; if `goal` is omitted the profile's default applies.

See [`docs/user-workflows.md`](user-workflows.md#goal-dropdown) for
when to pick which goal.

## Transposition table (DISCOVERY_PLUS)

`SearchProfile.DISCOVERY_PLUS` activates the
`TranspositionTable` so the search can:

- recognise canonical states it has seen before,
- prune already-known-better paths (`PruningReason.ALREADY_KNOWN_BETTER`),
- keep paths that introduce a **new rule combination** even when they
  don't improve the score (`PruningReason.KEPT_NEW_RULE_COMBO`).

The pruning decisions are exposed at `/api/memory/pruning`.

## Structural diversity

`DIVERSITY_DISCOVERY` is a deterministic target-blind diagnostic profile.
At each depth it retains at most one elite per structural cell instead of
collapsing every survivor into one scalar ranking. The frozen cell dimensions
are:

- AST-size band;
- expansion debt;
- last rewrite kind;
- denominator-count band;
- power-count band.

The policy never inspects a target identity. It is therefore suitable as an
information-parity control for scalar-fitness valleys. It is a bounded
MAP-Elites-style control, not an implementation or performance claim for the
complete MAP-Elites algorithm.

## Bounded rewrite reachability oracle

`BoundedRewriteReachabilityOracle` answers whether one expression is reachable
from another through the directed successors emitted by a frozen
`TransformationEngine`.

The oracle performs deterministic breadth-first traversal and retains a
shortest witness. It canonicalizes source, target and every generated successor
through the supplied identity function, so the experiment can choose exact
syntax, AC-aware syntax or another explicit identity contract without changing
the traversal algorithm.

The result distinguishes three states:

- `REACHABLE`: a witness is retained;
- `UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE`: the finite reachable closure was
  actually exhausted without hitting a declared bound;
- `BUDGET_INCONCLUSIVE`: an unseen successor exists beyond the depth or visited
  state limit.

A budget-limited miss is therefore never mislabeled as graph-theoretic
unreachability. The oracle is target-aware diagnostic infrastructure and is not
an autonomous-discovery claim.

## Historical rediscovery and reachability atlas

The atlas answers a narrower and more useful question than “did the current
search find the target?” For every frozen case it separates:

1. representation and parsing;
2. independent equivalence evidence;
3. directed reachability in the frozen production rewrite graph;
4. target-blind scalar search;
5. target-blind structural-diversity search;
6. target-guided diagnostic search;
7. a generic hypothesis bridge where preregistered;
8. an explicitly marked curated recognition control.

This separation prevents three common misreadings:

- a budget miss is not proof of graph-theoretic unreachability;
- a curated target-aware rule is not autonomous rediscovery;
- a mathematically equivalent target need not be connected by the currently
  directed production inventory.

### Frozen corpus

The source-controlled corpus is
`regelsuche-experiments/src/main/resources/de/regelsuche/benchmark/historical-rediscovery-corpus.json`.
Its strict Draft 2020-12 schema is
`docs/schemas/regelsuche-historical-rediscovery-corpus-v1.schema.json` and its
content SHA-256 is retained in every generated report.

The first version contains fourteen cases:

- five completing-the-square variants;
- two difference-of-squares forms;
- common-factor extraction on both operand orientations;
- two reverse/expansion directions;
- the Sophie-Germain identity;
- one information-parity search-policy control;
- one non-equivalent near-miss.

The corpus is a diagnostic fixture. Its provenance labels identify standard
published identities and synthetic controls, but they do not claim historical
priority or publication-grade bibliographic completeness.

### Evidence layers

The production layer executes `AstRewriteTransformationEngines.production(...)`
with the normal first-party AST inventory. The bounded oracle is compared with:

- existing target-blind scalar `BestFirstSearchStrategy`;
- target-blind `DIVERSITY_DISCOVERY`;
- target-guided best-first search as an explicitly diagnostic control;
- `DifferenceOfSquaresPreparationOperator` for the preregistered
  Sophie-Germain hidden-structure bridge;
- one explicitly marked completing-the-square recognition rule that proves
  representation and matcher capability without being counted as production
  reachability or autonomous rediscovery.

The generated evidence retains witnesses, rule IDs, primitive steps, explored
states, generated transformations, candidate-budget pruning, directionality and
one derived primary diagnosis per case.

### Derived diagnoses

Possible primary statuses include:

- production-reachable and found by scalar search;
- production-reachable but missed by scalar search and recovered by diversity
  or target guidance;
- production-reachable but missed by every bounded production search;
- generic bridge required and successful;
- curated control reaches while production does not;
- complete frozen closure exhausted without the target;
- budget inconclusive;
- negative control confirmed;
- correctness regression.

The final assessment is emitted only from retained statuses. It can be
`USEFUL_DIAGNOSTIC_STEP`, `USEFUL_BUT_INCOMPLETE` or
`INSUFFICIENT_SIGNAL`. It does not authorize a novelty or publication claim.

### Reproduction

Generate only the atlas:

```bash
./gradlew :regelsuche-experiments:generateHistoricalRediscoveryAtlas
```

Run the complete checkout-owned merge gate:

```bash
./gradlew --no-configuration-cache ciCheck
```

The generated files are retained under:

```text
regelsuche-experiments/build/reports/historical-rediscovery/
├── historical-rediscovery-atlas.json
└── historical-rediscovery-atlas.md
```

The JSON is the machine-readable evidence. The Markdown file is a compact human
review surface and must not be edited independently of the executable report.

The approach is considered useful when one frozen corpus distinguishes several
independent mechanisms: working production primitives, missing or
one-directional inventory, matched-work search-policy differences, a capability
frontier moved by a generic bridge and preserved rejection of the negative
control. It is insufficient when the atlas produces only budget-inconclusive
outcomes or requires direct case-specific target rules to manufacture every
positive result.

## Universal patterns

`GlobalMemoryService` scores every canonical state by a small
universality function (visit count, distinct rule combinations that
reached it, age). The top entries are surfaced at:

| Surface | Use |
| --- | --- |
| `GET /api/memory/universal` | JSON list of `patterns[]` and `ruleCoverage[]`. |
| Suchgedächtnis tab → *Universelle Muster* | Renders the same data with a link to the supporting best path. |

Patterns with a high universality score are the candidates the project
calls **macro-rule promotion targets** —
see [`macro-rules.md`](macro-rules.md) for how they're promoted.

## Related docs

- [`search-strategies.md`](search-strategies.md) — every strategy in detail.
- [`equality-saturation.md`](equality-saturation.md) — the EGraph layer.
- [`didactic-ranking.md`](didactic-ranking.md) — how `TEACHING_FRIENDLY`
  ranks alternative paths.

## Equality-Saturation Runtime-Metriken

Für `EQUALITY_SATURATION` werden zusätzlich zu den klassischen
Suchmetriken auch Matching-/Saturation-Metriken reportet:

- `classesScanned`, `nodesScanned`, `candidateClassesSkipped`
- `matchesFound`
- `matcherCacheHits`, `matcherCacheMisses`
- `saturationIterations`, `rulesFired`

Diese Felder sind in `SaturationStats` sowie in den Benchmark-JSON/MD
Artefakten enthalten.
