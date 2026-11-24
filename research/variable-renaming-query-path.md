# Checked query-path transfer across variable renamings

`TaskKnowledgeQuerySession.withVariableRenaming(knowledge, maximumQueries, maximumCharacters)` enables path transfer between bounded arithmetic expressions with a consistent one-to-one renaming of free variables. Shape matching preserves ordered operators, exact constants and repeated-variable positions. The key also binds the node-count goal, all six budget fields, and the requested strategy within one immutable knowledge generation.

The first ordinary solve supplies a cheap admission signal. A profile of the root and its two children, goal, budget and strategy permits structural matching when the source has at most three nodes per observed witness step. The profile is a scheduling hint. Exact canonical source shape determines every transfer lookup. The profile table has the same entry limit as the path cache and stores a fixed number of scalar fields per entry. Sources that fail the admission heuristic can use a compact exact-request path on an actual repeat. An ordinary miss pays the profile lookup and any decision it records.

Two successful ordinary solves of a shape are required for structural path admission. A transfer path contains up to 32 unconditional primitive edges. It retains each rule's identity and metadata, a typed tree position and a replacement expression. The source and replacements use binary arithmetic, exact numeric leaves and unscoped variables, with at most 256 nodes and a depth limit of 64. The receiving session instantiates each replacement, rebuilds its ancestor chain and constructs a fresh source-bound proposal. Each proposal passes the regular primitive verifier and independent final selected-path replay. Scoped symbols, assumptions, new symbols introduced by a rewrite and exact-theory certificates are outside this transfer scope.

Failed replay pays for the attempt before starting ordinary search with the remaining query budget. A replay that fails or costs at least as many logical work units as its previous ordinary solve retires both its stored transfer path and the profile's permission to normalize more requests. The compact exact-request fallback retains rule metadata and the typed endpoints already produced by the solver. Their recorded lengths provide a conservative character bound without scanning a retained proof graph. To solve changing workloads without profile work, use `knowledge.solve(task, strategy)` or a query session with zero capacity. The standard positive-capacity constructor remains available for exact-request reuse.

## Paired development measurement

Each arm solved the same 80 requests over four solvable families and one unsolved control. The repeated stream presented the same named tasks in every block. The mixed stream interleaved repeated tasks, previously unseen names, and controls with changed constants. Each arm created its own prepared generation with the same eight primitive rules. Timing includes generation preparation, session opening and closing, query and retention work, proof checks, and fallback. The input cohort and the independent exact-polynomial output audit were outside the timed region. All arms achieved the same scores and expected solve status. The audit independently confirmed exact equality for every selected result.

| Stream | Configuration | Path hits | Hits on new sources | Logical work | Median wall ms | Median process CPU ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Repeated | Ordinary | 0 | 0 | 28,352 | 108.0 | 250 |
| Repeated | Exact request | 56 | 0 | 26,919 | 129.6 | 330 |
| Repeated | Transfer with compact exact fallback | 56 | 0 | 24,871 | 92.8 | 240 |
| Mixed | Ordinary | 0 | 0 | 28,352 | 98.0 | 180 |
| Mixed | Exact request | 16 | 0 | 30,394 | 91.9 | 240 |
| Mixed | Transfer with compact exact fallback | 26 | 9 | 28,014 | 90.7 | 220 |

Three repetitions ran in rotating arm order after a warm-up in the same JDK 25 process. The transfer arm saved 12.3% of logical work on the repeated stream and 1.2% on the mixed stream. It beat ordinary solving on both timing measures in one pair per stream. The mixed median process CPU time increased, and timing observations varied across development runs. The first implementation required 35,507 units in each stream and transferred 12 previously unseen mixed requests. The cheaper profile and proof representation trade three such hits for lower total work. These data qualify this fixed public workload, with broader paid savings left to further measurement.

Reproduce the development measurement with JDK 25 and Maven. The focused tests also cover a forged transplanted edge rejected before a paid fallback, a capped unsuccessful retry, and scoped or coalesced symbols.

```sh
mvn -B -pl regelsuche-learning -am \
  -Dtest=TaskKnowledgeQuerySessionTest,TaskKnowledgeVariableRenamingBenchmarkTest \
  -Dregelsuche.measureVariableRenaming=true \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
