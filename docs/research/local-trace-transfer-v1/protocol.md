# Public development study of reusable intermediate rewrite sequences

This protocol fixes a prospective synthetic comparison of local schema induction before any study execution. All inputs are public. The final split tests new task families chosen by the developers. No external blinding or independence from knowledge of the eight inventory rules is claimed.

## Inputs and split

Use the eight unconditional primitive rules of `TraceStrategyTransferExample.inventory()`. In the following table E(A,B) is a textual abbreviation for `((A+B)*(A-B)+B*B)`, with both arguments parenthesized on substitution. It is expanded before parsing. The solver sees only the expression, the empty assumption set, the exact upper limit on output AST nodes and the budget. Names, split, family, abbreviation and reference expressions are unavailable to it. Each variable and literal counts as one AST node. The acquisition API receives TRAIN and GATE only. GATE selects whether a schema is worth promoting. Never tune after observing GATE or FINAL. Freeze the candidate model before evaluating FINAL.

| Split | Source (one per row unless specified) | Maximum nodes |
|---|---|---:|
| TRAIN | E(a,b)+(c+0) | 5 |
| TRAIN | (d*1)*E(e,f) | 5 |
| TRAIN | E(g,h)-(k*1) | 5 |
| TRAIN | (m+0)-E(p,q) | 5 |
| GATE | E(x*y,z)+9 | 7 |
| GATE | 13*E(r+s,t) | 7 |
| GATE | E(u,v) | 3 |
| GATE | j+19 | 1 |
| FINAL two-site energy | E(x*y,z)+E(y+n,w) | 11 |
| FINAL squared compensated product | (n-E(x*y,z))^2 | 9 |
| FINAL rational quotient | E(x-n,y)/n | 7 |
| FINAL common-factor reduction | (x+n)*y+(x+n)*z | 7 |
| FINAL residual near miss | ((x+y)*(x-y)+y*(y+n)) | 3 |
| FINAL irreducible sum | x+n | 1 |

For each FINAL family substitute n = 2, 3, 5, 7, in that order; keep all 24 cases. Family labels distinguish external compositions and reduction requirements; the first three deliberately share local algebra with TRAIN. The last three require other rules or provide negative controls. No FINAL occurrence may enter training, candidate selection, early stopping or a model update. A primitive goal specifies complexity only, never an answer or matching path.

## Arms and budgets

Run a paired comparison on every input. Use `MoveSearch.Budget(32,32,200000,512,200000)` and at most 1,000,000 acquisition units per learning batch. Each non-HAND arm uses the same primitive inventory. Independent final path replay remains mandatory; cache no answers between requests. Ranking for ORDER is the TRAIN frequency of each primitive provider in successful selected primitive paths, descending, falling back to the original ordering on ties. Learn no frequency from GATE or FINAL.

| Arm | Configuration |
|---|---|
| BASE | Existing prepared primitive solver, inventory order, no learning. |
| ORDER | Primitive solver, TRAIN-derived provider priority, no learned move. |
| LOCAL | Same priority as ORDER; additionally admit checked schemata from consecutive primitive subpaths in TRAIN, after the ordinary paid GATE promotion. |
| DROP | Full LOCAL acquisition and restoration, same priority and scheduler as LOCAL; remove just the learned providers during FINAL. |
| WHOLE | Same priority, same training and gate using existing whole-trace formation, without intermediate-subpath formation. |
| HAND | Fixed production direct rational polynomial profile POLYNOMIAL_DIRECT_V1 with its exact verifier and the same task goals and work limit. |

When a learned model is rejected, keep its acquisition costs and zero usable schemata. For an unsupported, failed or exhausted run preserve that outcome and charge observable work. Never substitute a more convenient algorithm after observing FINAL. Report per-case solved state, verified selected steps and learned step counts, all logical work and scoring/verification scope. Report training, promotion and restoration separately, with their sum charged once per batch. Include explicit accounting for priority training/sorting or disclose measurable operations outside the solver's work contract. Record thread CPU and elapsed time diagnostically on three runs in rotated arm order. Wall-clock rankings and external tool superiority are outside the acceptance criteria.

## Interpretation

Actual schema transfer requires an independently replayed learned step on a FINAL case from a different family. A causal search benefit requires a better result or lower paid query work at equal success than DROP on the full paired panel. Report the comparisons to ORDER, WHOLE and HAND and separately whether the gain survives acquisition and restoration. A broad positive claim also requires a benefit outside the deliberately repeated local pattern. Retain null results, negative controls, every output, immutable model hashes, and the commands to reproduce.
