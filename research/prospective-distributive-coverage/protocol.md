# Prospective work-budget coverage study

This public development protocol fixes the input generator, primitive inventory, goals, costs and comparisons before training or evaluation. The evaluator knows the generator. This is prospective and reproducible; it is not an independently blinded corpus. The eight unconditional primitive rules are the unchanged `TraceStrategyTransferExample.inventory()` in [the solver under test](https://github.com/carstenartur/Regelsuche/commit/c7e4593fcb02b6274a500b04578169e05ba70795). The parents of this study used a difference-of-squares compensation. Here the repeating local calculation involves distribution followed by cancellation. No new domain-specific move, priority, solver profile, or target expression is given to search.

## Inputs

Use exact parsed binary trees, parentheses around every metavariable substitution, and the following abbreviations only in the fixture. The solver receives the source and an upper bound on node count. Its primitive factorization and cancellation rules can in principle reach all positive goals. Constants count as one node.

- `F(A,B,C,D) = ((A*((B-C)-D) + A*D) + A*C)`
- `R(A,B,C,D) = ((((B-C)-D)*A + D*A) + C*A)`
- `J(A,B,C,D) = ((A*((B-C)-D) + A*D) + A*(C+0))`

TRAIN consists of `F(a,b,c,d)` (3 nodes) and `F(e+f,g,h,i)` (5). The two sources have different polynomial structures. Use the existing cumulative local-batch learner, default primitive inventory, and the ordinary promotion policy. Acquisition budget 1,000,000. GATE comprises `F(r,3,s,t)` (3), `F(x-7,y,z,w)` (5) and `F(p,q+11,u,v)` (5). The first two use ordinary depth 32, the last uses depth 2 to exercise the existing constrained-depth promotion policy. Each gate is independent of FINAL; never add, drop or retrain after freeze. The setup may produce no promoted model, which is a reportable outcome. Commit training evidence and the exact canonical snapshot and digest publicly before starting FINAL.

For each n in 2, 3, 5, 7 run all six FINAL families in the following order.

| Family | Exact input | Node goal |
|---|---|---:|
| Parallel difference | `F(x+n,y,z,w) - F(u,v,t,g)` | 9 |
| Serial product | `F(2,F(x,y+n,s,t),u,v)` | 7 |
| Normalization before reuse | `J(x,y+n,z,w)^2` | 7 |
| Mirrored distributivity | `R(x+n,y,s,t)/n` | 7 |
| Independent neutral operations | `((x+n)+0)*1` | 3 |
| Irreducible negative | `x+n` | 1 |

The first three families deliberately reuse the training relation in new computations. The fourth tests the opposite operand ordering and the fifth independent algebra. They measure distinct notions of transfer. Each coefficient variant reuses the family structure and is not an independent discovery. Across TRAIN, GATE, and FINAL check polynomial alpha-identities with the repository's exact checker, reporting any overlap explicitly.

## Budget and interpretation

All TRAIN tasks and all gates use 200,000 total work units. All FINAL tasks, arms and budgets use max primitive steps 32, max search depth 32, max theory work 200,000 and max states 512. Sweep the total work budget over 64, 96, 128, 192, 256, 384, 512, 768, 1,024, 1,536, 2,048, 3,072, 4,096, 6,144, 8,192, 12,288 and the 200,000-unit reference. Keep all rows, including losses, overruns and negative controls. A run solves only if all of the ordinary solver's score, outcome and budget checks succeed, its work accounting is complete, its selected witness replays and an external exact polynomial check confirms the output. Report overspent prefixes at their actual cost.

BASE uses the ordinary primitive checked solver. LEARNED uses the frozen checked model in the occurrence-selection-plus-routing solver's normal knowledge-first order. DROP uses the very same model, preparation, scheduling, policy and state scorer, with all learned providers removed from the problem just before searching. No study-specific frequency table is used. If training fails to promote, both model arms use the available empty generation and the standard primitive solver. Do two runs in separate JVMs, BASE/LEARNED/DROP and DROP/LEARNED/BASE. Require identity of all mathematical outcomes and work accounts, store per-arm/case/budget results and hashes, and report wall time separately as descriptive data.

Primary criterion: at a common swept work budget, **all four parameterizations of at least one of the first three declared positive families** succeed with independently replayed learned moves, both BASE and DROP fail, and those BASE cases solve at 200,000. In addition require a positive aggregate coverage difference over the entire 16-budget sweep against both controls, reporting losses. Sum the number solved over every budget and case; this descriptive integral treats the fixed log-like grid points equally. Report every qualifying family and budget, even if the primary criterion fails. Report mirrored and unrelated controls separately and never promote the unreachable negative into the success criterion. Separate query, model acquisition and load/preparation work. A query win says nothing about cold-start amortization. This study tests improvements from trained knowledge within this inventory and budget; it makes no comparison to external tools or human mathematicians.
