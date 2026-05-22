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

## Related code & tests

- `app/src/main/java/de/regelsuche/egraph/EGraph.java`
- `app/src/main/java/de/regelsuche/egraph/EqualitySaturation.java`
- `app/src/main/java/de/regelsuche/search/strategy/EqualitySaturationStrategy.java`
- `app/src/test/java/de/regelsuche/egraph/EGraphTest.java`
