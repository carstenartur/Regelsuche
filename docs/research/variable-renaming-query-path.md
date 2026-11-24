# Checked query-path transfer across variable renamings

`TaskKnowledgeQuerySession.withVariableRenaming(knowledge, maximumQueries, maximumCharacters)` enables path transfer between bounded arithmetic expressions with a consistent one-to-one renaming of free variables. Shape matching preserves ordered operators, exact constants and repeated-variable positions. The key also binds the node-count goal, all six budget fields, and the requested strategy within one immutable knowledge generation.

Two successful ordinary solves of the shape are required before retaining a path. The path may contain up to 32 unconditional primitive edges. The source and intermediate expressions are limited to binary arithmetic terms, exact numeric leaves and unscoped variables, with at most 256 nodes and depth 64 per term. Each transferred edge receives a fresh source-bound application identity and passes the regular primitive verifier and independent final selected-path replay. Scoped symbols, assumptions, new symbols introduced by a rewrite and exact-theory certificates are outside this transfer scope.

Failed replay pays for the attempt before starting ordinary search with the remaining query budget. A shape stays on ordinary search once an attempted replay fails or costs at least as many logical work units as its previous ordinary solve. This decision lasts while the key remains in the bounded cache. Shape recognition still incurs work during ordinary fallback in the transfer configuration. To solve changing workloads without this overhead, use `knowledge.solve(task, strategy)` or a query session with zero capacity. The standard positive-capacity constructor remains available for exact-request reuse.

## Paired development measurement

Each arm solved the same 80 requests over four solvable families and one unsolved control. The repeated stream presented the same named tasks in every block. The mixed stream interleaved repeated tasks, previously unseen names, and controls with changed constants. Each arm created its own prepared generation with the same eight primitive rules. Timing includes generation preparation, session opening and closing, query and retention work, proof checks, and fallback. The input cohort and the independent exact-polynomial output audit were outside the timed region. All arms achieved the same scores and expected solve status. The audit independently confirmed exact equality for every selected result.

| Stream | Configuration | Path hits | Hits on new sources | Logical work | Median wall ms | Median process CPU ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Repeated | Ordinary | 0 | 0 | 28,352 | 169.7 | 350 |
| Repeated | Exact request | 56 | 0 | 26,923 | 106.9 | 340 |
| Repeated | Variable renaming | 17 | 0 | 35,507 | 103.0 | 280 |
| Mixed | Ordinary | 0 | 0 | 28,352 | 92.8 | 170 |
| Mixed | Exact request | 16 | 0 | 30,362 | 103.2 | 200 |
| Mixed | Variable renaming | 17 | 12 | 35,507 | 111.4 | 160 |

Three repetitions ran in rotating arm order after a warm-up in the same JDK 25 process. The renaming arm beat ordinary solving on both timing measures in two repeated-stream pairs and one mixed-stream pair in the shown run. Timings varied across development runs. The 12 new-source hits establish structural transfer, while the 25.2% increase in total logical work and variable timings leave an overall efficiency advantage unestablished. The opt-in mode is suitable for further qualification of specific workloads.

Reproduce the development measurement with JDK 25 and Maven. The focused tests also cover a forged transplanted edge rejected before a paid fallback, a capped unsuccessful retry, and scoped or coalesced symbols.

```sh
mvn -B -pl regelsuche-learning -am \
  -Dtest=TaskKnowledgeQuerySessionTest,TaskKnowledgeVariableRenamingBenchmarkTest \
  -Dregelsuche.measureVariableRenaming=true \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
