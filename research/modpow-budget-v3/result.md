# Finite-budget search with the frozen modular-program learner

Primitive search used **232** logical work points to complete the ordered
12-program panel. Adding the learned pattern used **716**, including **67**
for acquisition. At a stream budget of 128, the primitive arm reached four
of the six designated positive optima and the learned arm reached two.
At 256 the counts were six and four. Both reached six at 512.
The panel produced no amortized search benefit.

| Product sites | Positive shape | Source DAG | Optimum DAG | Primitive first optimum | Learned first optimum, warm |
|---:|---|---:|---:|---:|---:|
| 1 | Forward | 49 | 34 | 9 | 8 |
| 1 | Reversed | 49 | 34 | 8 | 12 |
| 2 | Forward | 87 | 72 | 9 | 28 |
| 2 | Reversed | 87 | 72 | 14 | 32 |
| 4 | Forward | 220 | 163 | 30 | 99 |
| 4 | Reversed | 220 | 163 | 20 | 95 |

Each complete scan of a two-output program tests two ordered assignments.
Three outputs require six and five outputs require 20.

Across 78 positive row/budget pairs, the warm learned arm had one lower
incumbent DAG cost, nine higher costs and 68 ties. Its sole advantage was
the single-site forward case at eight points. Charging acquisition to each
individual search gave zero lower costs, 21 higher costs and 57 ties. Every
point is reported, including budgets that allow only the source. The stream
runs each search to exhaustion before starting the next. With one, three and
seven distinct primitive candidate programs, respectively, the two- and
four-site results show the cost of matching all ordered output assignments.

Each missing-modulus-premise control verified zero candidates. The one- and
two-site no-reuse controls retained source costs 49 and 87. On the four-site
no-reuse control, both arms found a saving between the distractor powers,
from 220 to 163. The primitive scheduler reached it after 20 work points,
the warm learned scheduler after 92. This row refutes a blanket expectation
that breaking one designated reuse relationship rules out other savings.

The [plan](plan.md) defines a finite logical-work model at action boundaries.
A single replay, scoring or generalizer invocation has unit weight. Native
matching attempts, evaluated steps and AC branches are counted separately.
These unit weights do not measure time or total arithmetic operations.
Acquisition uses a shared-left-side matching proxy for the original TRAIN
transport, whose returned candidate set was checked. Full uncapped primitive
generation over every subtree independently supplied the TEST reference.
Its unique candidate sets matched the bounded schedulers on all 12 programs.

The [public preregistration](https://github.com/carstenartur/Regelsuche/commit/a076e7f1ca0f0a6e3ba24d1950e72d92bd885562)
preceded TRAIN. Its [frozen implementation and TRAIN receipt](https://github.com/carstenartur/Regelsuche/commit/dc8d52a85eb49860daa7a214cc7563dd24b6b99e)
preceded TEST. The model reproduced the v1 digest. Independent evaluation
JVMs produced the same 46,191 [raw result bytes](result.json), SHA-256
`2c705a67ee232035156694fd07ecc689394fe1de650543692fa65a7c0c4d4b9c`.
This measures one-step search with a growing candidate inventory on public
inputs. #1026's requested benefit on harder cases remains open.

Reproduce the two stages and compare the complete report to the retained bytes
from the repository root with JDK 25.

```sh
mvn -pl app -am test -Dtest=ModPowBudgetStudyTest,ModPowAcTransferTest,ModPowFrozenTransferTest -Dsurefire.failIfNoSpecifiedTests=false
```
