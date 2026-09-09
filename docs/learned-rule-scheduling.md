# Learned-rule utility and search scheduling

Implementation chain: #953, with scheduling in #696, capability transfer in #750,
authorization/replay in #745, and information parity in #235. The representation
selection experiment in #952 is a separate result.

## PR 1: diagnose the existing search before changing its policy

Run `./gradlew :regelsuche-learning:learnedRuleBaseline`. The ordinary learning
test suite also runs the comparison and retains its report under
`build/reports/learned-rule-baseline`. Content-addressed bundles contain the frozen
protocol, learned TRAIN program, all observations, and complete search replays.
Walltime is a separate diagnostic CSV and does not affect hashes, budgets, or scores.

| Profile | Knowledge and policy |
| --- | --- |
| BASE | The existing eight primitive rules and ordinary work-budget best-first search |
| LEARNED_NAIVE | The same primitives plus the frozen learned rewrite program, eagerly appended |
| LEARNED_RANKED | Exactly NAIVE in this initial control; no new heuristic yet |
| EXPERT | The same primitives plus an explicitly handwritten cancellation/factoring program |

Seven public development cases and four declared total-work budgets are fixed
before training. The learner sees only the existing four TRAIN sources, never
the application targets. Inputs overlapping any observed TRAIN polynomial up to
variable renaming are rejected. Every profile receives the same visible syntax
target, primitive inventory, primitive-depth limit, state/candidate limits,
work allowance and exact polynomial verifier. Unsupported inputs, wrong-target
controls, budget exhaustion and regressions remain in the report. This is not a
sealed final holdout and it does not qualify a new production policy.

Successors are counted at the returned-source/frontier boundary. **Consumed** means
the candidate admission loop actually inspected the successor. **Discarded** is
generated minus enqueued, including generated successors never consumed. Family
matches count returned source matches; internal failed matcher attempts and
engine-internal filters are not observable through the old engine API and are
explicitly outside that field. Internal duplicate counters remain separate from
frontier outcomes. A dead end requires a fully inspected expansion with no
admitted successor; a budget-truncated expansion is not called a dead end.
Effective branching is defined as admitted successors / expanded states.

The v1 cost partition preserves the historical ledger: primitive-source candidate
events, remaining mechanical search events, and actual exact audit calls per
retained primitive edge. Their sum is the reported total. Each macro's complete
primitive lineage is audited, so a one-edge search decision can still require
several proof checks. Atomic generation may cross the work boundary; those runs
remain failures with the full observed overrun, never successful under-budget
runs. Parsing, compilation, identity projection and rational bit complexity are
not CPU-instruction costs in this event ledger. Training search, replay,
minimality work and exact audit calls are disclosed separately; this initial
diagnosis does not claim complete training-cost amortization.

The new observer leaves the existing v1/v2 canonical search replay bytes and
production search policy unchanged. Later PRs add a neutral move model, scoped
utility evidence, lazy scheduling, activity, history, executable landmarks and a
separate frozen evaluation protocol. Mathematical authority is never inferred
from a useful heuristic score.
