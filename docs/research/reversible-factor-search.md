# Target-directed search on the additive factor/distribution fragment

## Frozen comparison

Use every `SYNTAX_EXACT` row of the unchanged #620 `historical-rediscovery-corpus.json`, its published source, target and oracle depth. Compare one-sided breadth-first search, bidirectional breadth-first search and the complete bounded closure under the same four native primitives, empty initial assumptions and a fixed controller. The `VALUE_EQUIVALENT` row has a different target relation. Add a public three-step control from `a*x + a*y + (b*x + b*y)` to `(a+b)*(x+y)` for an interior meeting, with the original formatted expression transport and exactly the same fragment. Also test depth limits, exhausted shared work, changed assumptions and forged steps.

Use the two declared inverse pairs `ast_factor_common_left` / `ast_distribute_left_add` and `ast_factor_common_right` / `ast_distribute_right_add`. The four rules express left and right distributivity over scalar addition. Admit a formatted transition only when the actual opposite primitive reproduces its starting syntax with no new assumptions. This restriction matters because expression formatting can erase intermediate grouping. Set the fragment language bound to 63 AST nodes per formatted state and allow 10,000 primitive-engine calls per policy or oracle run. Count calls plus their entire returned primitive proposal lists for forward traversal, reverse traversal and independent directed witness replay, including inverse probes. This is a visible-target diagnostic with no discovery consumer.

Use `pair(a*x+a*y, b*x+b*y)` to `pair(a*(x+y), b*(x+y))` as a second two-step control. The ordered function arguments keep the two independent sites separated during formatted transport. Retain both supplementary rows, including any unreached target.

## Results

The SHA-256 of the unchanged source corpus is `53bff974a9ecb101b85e01f202d095fb2a99f5f33127ed7ec0e4ade2685ac422`. All three algorithms agreed on all 13 selected rows. Three targets had a one-primitive witness, and the complete bounded closures excluded the other ten. To exhaust the closure even for positive rows, the oracle searched for a fresh variable absent from the source. These four primitives can only move or duplicate existing subterms. Reading the minimum primitive distance for the actual target from every retained state gives the independent reference. The oracle used each row’s frozen state cap and a 100,000-transition ceiling. All closures completed within their limits.

| Frozen panel (13 rows) | Primitive engine calls | Generated primitive proposals |
|---|---:|---:|
| Exhaustive oracle | 35 | 40 |
| One-sided BFS, including replay | 30 | 32 |
| Bidirectional BFS, including replay | 28 | 28 |

The three positives (`factor-common-left`, `factor-common-right`, `distribution-fitness-valley-control`) cost both searches the same work. On two negative rows the target frontier terminated after one call with no proposals, accounting for the smaller aggregate. On the separate two-site `pair` control, both searches used 12 calls and generated 24 proposals. The bidirectional path met after one layer from either endpoint. Its two forward primitive steps each used `ast_factor_common_left`, with a fresh directed replay of both applications. The grouped three-step control remained unreachable within its depth and cost the bidirectional arm 7 calls and 12 proposals versus 4 and 4 one-sided. These counts concern this four-rule fragment, its formatted syntax and logical engine work. Runtime and target-blind discovery were outside the comparison.

The [full table](reversible-factor-search.tsv) includes paths, per-direction and replay costs and every negative result. Each `calls/proposals` pair includes all inverse probes and every returned native candidate. A shortest distance of `-1` denotes absence from the exhausted bounded closure.

```sh
./gradlew :regelsuche-search:test --tests '*ReversibleFactorSearchTest' --tests '*BoundedReachabilityOracleTest' \
  :regelsuche-experiments:test --tests '*ReversibleFactorComparisonTest'
cmp docs/research/reversible-factor-search.tsv \
  regelsuche-experiments/build/reports/reversible-factor-search/comparison.tsv
```
