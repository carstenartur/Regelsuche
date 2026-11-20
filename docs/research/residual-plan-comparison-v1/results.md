# Frozen residual-controller comparison

Training evaluated all eight structural conjunctions against exact native
composition. The selected mask 7 admits disjoint pairs with opposite signs
and equal absolute monomials, including the rational coefficient. Acquisition
cost 366,347 work units. The selector contains no expression from the public
historical comparison. The plan and its training receipts were committed in
`39ee189d`. Validation and final candidate receipts were committed in
`4f94fd4d` before the historical comparison.

| Evaluation source | Exact results per arm | Learned | Fixed | Random | Exhaustive | Hand | Native batch |
|---|---:|---:|---:|---:|---:|---:|---:|
| validation `u^2+u^6+u^10+u^14` | 2 | 6,066 | 13,164 | 13,176 | 42,258 | 6,066 | 8,702 |
| validation `r^2+s^2+t^2+v^2` | 0 | 2,200 | 7,132 | 7,144 | 23,752 | 2,200 | 4,551 |
| final `(a^2+b^2)*(c^2+d^2)` | 2 | 6,033 | 12,005 | 12,017 | 36,185 | 6,033 | 8,284 |
| final `(4*p^2+9*q^2)*(25*r^2+49*s^2)` | 2 | 7,831 | 15,603 | 15,615 | 47,343 | 7,831 | 10,742 |
| final `j^2+k^2+l^2+m^2` | 0 | 2,200 | 7,132 | 7,144 | 23,752 | 2,200 | 4,551 |

The table charges inference per source. All controller arms completed within
their registered 100,000-unit cap. The learned selector tied the hand selector
on reach and work throughout. It used fewer units than the batch composer on
each evaluated source. Adding acquisition once makes the learned method more
expensive than the batch composer over this entire evaluation.

Each of the six arms formed the same two positively expanded representations
on the public product. After freezing the final receipts, the observer compared
both historic reference expressions with the candidates through structural
canonicalization and exact polynomial equivalence. Each reference matched
exactly one root per arm. Source polynomial equivalence alone would accept
all equivalent expressions. The two distinct structural checks and primitive
rule lists distinguish the two construction outcomes. The random comparator
uses one registered deterministic order. The evidence measures a finite
four-component public grammar. Public historical correspondence does not
establish independent mathematical novelty or a blind historical test.

The source runner is `de.regelsuche.evolution.ResidualPlanComparison`. Run
`train OUTPUT`, then `validation OUTPUT PLAN_SHA` and `final OUTPUT PLAN_SHA`.
Run `de.regelsuche.evolution.ResidualCorrespondence OUTPUT FINAL_SHA` only
after freezing the candidates. The JUnit
`ResidualPlanComparisonTest.detachedRegenerationPreservesEveryArmAndBothHistoricalMatches`
recreates all five receipts in a temporary directory and compares their bytes.

## Executing a checked plan

`ResidualPlanCompiler.bind(source, effectIds, verifierBudget)` re-derives the
two effects from the original expression. It resolves the existing schematic
IR's two occurrence-pair and two sign holes against the prepared source tree.
The resolution requires disjoint occurrences, complete coverage, zero residual
and equivalence to the original polynomial. It preserves primitive applications
and assumptions. `compile(bound, verifierBudget)` repeats these checks and
compares the entire receipt before constructing a budgeted rewrite program.

`ResidualPlanController.open(source, mode, mask, budget)` exposes one action at
a time. A selected pair has four open obligations until the native checker
returns. The continuation key binds the source, selector, cursor, pending
pair and remaining work. Equal source states with no future controller action
share a terminal key. Proof roots and work remain available as observations.
The length change of the prepared expression and the number of unresolved
obligations are distinct diagnostics. Reference mode enumerates all pairs.
Discovery mode applies the supplied structural conjunction. Both call the
same native preparation and checker as the frozen experiment.

`compileAccepted(root, verifierBudget)` admits only a root issued by that
search, recompiles it independently, and charges both the measured discovery
prefix and the new verification work to the resulting transition. Execute it
through `RewriteProgramInterpreter.executeBudgetedSource`. The interpreter
also records its mechanical work. Each transition is a standalone path cost.
Users comparing multiple paths should retain their shared prefix separately.
The score-learning phase remains a separate acquisition charge reported above.
