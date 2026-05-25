# Search Intelligence

This page is the entry point for everything that makes Regelsuche's
search "smart": profiles, goals, the transposition table that powers
DISCOVERY_PLUS, and the universal-patterns surface.

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
