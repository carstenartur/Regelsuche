# Equality Saturation

Regelsuche ships an e-graph implementation in
`de.regelsuche.egraph` and exposes it via the `EQUALITY_SATURATION`
search profile. This page summarises what the surface looks like and
where to read more.

## TL;DR

```
Expression  ──parse──►  EGraph
                          │  hash-consing, congruence closure
                          ▼
                  apply every RewriteRule
                  until fix-point or budget
                          │
                          ▼
                  extract cheapest tree
                  using the goal's cost model
```

Because every equivalent rewrite collapses into one shared EClass, the
combinatorial explosion of "which order do I apply the rules?" simply
disappears. We don't even need to commit to a single cost model up
front: re-running `extract()` with a different `CostModel` (i.e.
switching `TransformationGoal`) immediately yields a different
canonical form from the same saturated graph.

## Surfaces

| Surface | Purpose |
| --- | --- |
| `de.regelsuche.egraph.EGraph` | Core data structure (EClass, ENode, UnionFind). |
| `de.regelsuche.egraph.EqualitySaturation` | Saturation loop with `SaturationStats`. |
| `de.regelsuche.search.strategy.EqualitySaturationStrategy` | Plug-in for the existing search platform. |
| `SearchProfile.EQUALITY_SATURATION` | Profile users select in the UI. |

Stats from the most recent saturation are available via
`EqualitySaturationStrategy.lastStats()` and surfaced in the benchmark
report.

## Matcher-Indizes & Skalierung

Die Engine nutzt jetzt drei Kernmechanismen, um große E-Graphs nicht
mehr blind zu scannen:

- direkter `EClassId -> EClass` Lookup (`EGraph.classOrThrow(...)`),
- `ENodeSignature(symbol, arity)`-Index über Root-Kandidaten
  (`EGraph.classesWith(...)`, plus Prefix-Lookup für Kategorien wie
  `num:*`),
- Worklist/Dirty-EClass-Saturation statt Vollscan pro Iteration.

`EGraphPatternMatcher` memoisiert zusätzlich Matches pro
`(patternId, eclassId, egraphVersion)`.
Bei jeder strukturellen E-Graph-Änderung wird die Version erhöht und der
Matcher-Cache dadurch korrekt invalidiert.

Der Symbol-/Arity-Index verursacht bei sehr kleinen Add/Rebuild-
Mikrobenchmarks zusätzlichen Buchhaltungsaufwand. Dieser Overhead ist
bewusst zugunsten größerer Discovery-Läufe akzeptiert: neue JMH-
Vergleichspunkte (`egraphPatternMatchFullScanLarge` vs.
`egraphPatternMatchIndexedLarge`) messen den Crossover explizit, indem
sie denselben großen In-Memory-EGraph einmal per Vollscan und einmal per
Signaturindex matchen. Die Add/Rebuild-Kurven bleiben durch
`egraphAddAndRebuildSmall/Medium/Large` separat sichtbar.

### Verfügbare Metriken

Neben den bisherigen Feldern enthält `SaturationStats` jetzt:

- `classesScanned`
- `nodesScanned`
- `candidateClassesSkipped`
- `matchesFound`
- `matcherCacheHits`
- `matcherCacheMisses`
- `saturationIterations`
- `rulesFired`

## When to pick this profile

- Many equivalent rewrites compete; you want to see them all before
  committing to a cost model.
- You want to switch between `SIMPLIFY`, `FACTORIZE` and
  `NUMERICALLY_STABLE` on the same expression without re-running.
- You care about a clean, deterministic canonical form (the EGraph is
  hash-consed, so identical sub-expressions are shared).

## Limitations

- The current ruleset uses the same atomic
  `RewriteRule`/`PatternExpr` shape as the path-based strategies — no
  conditional rewrites yet. See [`limits.md`](limits.md).
- Memory grows with the number of distinct rewrites; saturate small to
  medium expressions, not 200-term ones.
- For tiny e-graphs, maintaining matcher indices can be slower than the
  previous no-index bookkeeping. The intended win is reduced matching
  work once the graph has many unrelated root symbols/classes; benchmark
  reports should therefore compare both Add/Rebuild and large matcher
  scan metrics.

## Related code & tests

- `regelsuche-egraph/src/main/java/de/regelsuche/egraph/EGraph.java`
- `regelsuche-egraph/src/main/java/de/regelsuche/egraph/EGraphPatternMatcher.java`
- `regelsuche-egraph/src/main/java/de/regelsuche/egraph/EqualitySaturation.java`
- `regelsuche-search/src/main/java/de/regelsuche/search/strategy/EqualitySaturationStrategy.java`
- `regelsuche-egraph/src/test/java/de/regelsuche/egraph/EqualitySaturationScalabilityTest.java`
