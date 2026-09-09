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

## PR 2: a common evidence-bearing move boundary

`regelsuche-search` owns `SearchMove`, `MoveProvider`, `MovePriorityPolicy`,
`MovePicker`, source-only `MoveState` and goal/assumption `MoveContext`. The first
picker is explicitly eager, providing a control for the later lazy implementation.
It evaluates each score once and preserves inventory order for equal scores.
Provider batch work is charged once; the generation cost attached to each move
describes that shared batch, not an additional per-move charge. Unknown
verification cost is -1, not zero.

`MoveProviders.from` exposes the base engine and individual hypotheses instead
of executing the old wrapper's global append limit. Inventory macro wrappers
expose the same provider contract. `DiscoveryEngineFactory.createMoveEngine`
uses this common boundary and a caller-supplied policy; its experimental engine
rejects the PRODUCTION phase pending the separate #745 qualification. The old
engine entry points remain the historical/production controls until that gate.
Opaque engines do not assert complete successor enumeration.

The `ReusableRule` value object now belongs to Learning, with its existing Java
package and constructors retained. `DynamicOperatorCompiler.compile(rule)`
preserves the entire rule evidence, including confidence, supporting observations,
assumptions and provenance. A quarantined dynamic operator remains empirical
even if its old transformation flag or inventory metadata looks stronger.
Supporting path IDs alone do not become an instantiated primitive expansion.
Actual program moves keep their complete typed primitive sequence. These
descriptors carry evidence for later verification; they do not grant production
authority or replace an independent verifier.

## PR 3: persisted utility and concrete primitive reference evidence

`RuleUtilityAssessor` uses the existing complete primitive trace verifier, with
the new macro excluded by construction: its inventory contains only the frozen
primitive genes. `RuleUtilityEvidence` retains the observed path length, best
known primitive connection, known compression, minimum-proof flag, application
and replay work, outcome counters, capability observations, confidence and an
inventory/endpoints/budget-bound reference receipt. An observed 20-step path
with a proved 2-step primitive connection has compression 1, not 19; its retained
original proof still has 20 primitive replay edges. Actual application audits
continue to measure the path used, including any shortened proof.

If the observed path replay completes but reference exploration exhausts a
limit, that path remains a known upper bound (for example 7), while
`boundedMinimumProved` is false. If even the observed replay exceeds its limit,
the best known length remains -1. The finite reference scope never becomes a
universal minimum claim about every substitution into a generalized rule.
Reference work is retained separately; unknown application/replay work is -1.

Utility survives all rule-copy methods, compilation, JSON inventory snapshots,
export/import and the Neo4j adapter. Older records load with unknown utility.
The adapters also retain confidence, occurrences, supporting path IDs and
assumptions that older export/Neo4j paths omitted. Malformed distance claims are
rejected. Imported utility remains scheduling data: neither deserialization,
high confidence nor frequent usage authorizes a mathematical rule or RewriteProgram.

## PR 4: lazy stages and suspended expansion

`WorkBudgetBestFirstSearchStrategy.search(MoveSearch.Problem)` exposes the opt-in scheduler through the existing search entry point and `TransformationSearchService.searchMoves`. A frontier ticket can represent either a new state or a suspended parent expansion. A verified goal child wins before the parent opens later providers. `StagedMovePicker` orders provider metadata first, opens batches only on demand, and gives a primitive lane a turn after two valuable learned candidates, including within large learned batches. Eager enumeration remains an explicit control.

The reference mode never drops a provider due to its priority. Its declared relation is bounded by primitive depth, search depth and theory path work; complete per-rule AST providers remove hidden candidate/growth caps. Work/state exhaustion, opaque providers and rejected proof/assumption claims are inconclusive, never proofs of unreachability. State identity includes depth, previous rule, assumptions, capabilities, debt and theory work. Reference inclusion is evaluated on exhausted bounded closures, not falsely asserted for two runs truncated by equal work limits.

The new event ledger separately records generated mathematical application work, source/scheduling events and independent verifier work. All generated moves, including unused batch tails, are charged. A provider is currently an atomic batch: its measured overrun is retained and makes the run fail its work budget before a goal can be accepted. These deterministic event units are not CPU instructions or walltime. The polynomial admission implementation replays every leaf against the frozen primitive inventory and checks exact identity with measured node/term work; imported labels and utility never authorize a move. Existing v1/v2 reports and production defaults retain their established behavior.

## PR 5: rule activity without proof promotion

`RuleActivityMemory` persists a versioned sidecar keyed by durable inventory identity. HOT/WARM/COLD/SHADOW are derived from explicit activity observations, with deterministic 0.95 decay per TRAIN epoch. Only retained successful witness edges receive success credit; duplicates and fully inspected dead ends receive penalties. Budget-cut enqueues are not mislabeled dead ends. Measured paired work savings are supplied separately and never inferred from the observed trace length. Unused rules remain in the inventory.

`ActivityMovePolicy` takes an immutable snapshot and moves cold/shadow learned providers to exploration. It cannot remove providers, alter assumptions, grant replay authority or change proof status. Frozen evaluation and production phases reject all memory updates before mutation. Snapshot round trips, decay, reference enumeration of cold knowledge, retained empirical proof labels and TEST immutability are covered by tests. Production admission remains the independent #745 boundary.

## PR 6: contextual history and continuations

`StructuralMoveContext` records root operator, bounded syntactic polynomial degree (unknown explicitly), variable count, product/power structure, repeated subtrees, assumptions and currently known capabilities. Context identity omits variable spelling. `RuleHistoryMemory` persists both `(context, family)` and `(previousRule, nextRule, context)` tables, including successful witness use, failed admission/inspected dead ends, duplicate rate, measured work savings, capability unlocks and verification work. All updates require TRAIN.

`HistoryMovePolicy` exposes its eight fixed weights and per-move feature vector. Compression, context/continuation history, capability/goal progress and proof descriptors compete with branching, failure and verification cost. A supported successful continuation can enter the principal stage, while expensive and exploratory providers retain their cost stage. Structural feature work is charged once per state/picker. The read-through feature cache is recreated per run and never changes weights or observations. The policy composes with immutable activity snapshots; there is no neural model or TEST adaptation.

Tests establish continuation transfer within context, absence of credit in a different context, duplicate/dead-end feedback, persistence, alpha-renaming invariance, explicit unsupported degree, feature-work accounting and TEST immutability. The frozen mathematical evaluation in PR 8 consumes these exact policy interfaces; PR 1's historical eager control remains reproducible.
