# Binding-aware learning: first integration slice

Approved direction: the 16 September 2026 self-learning roadmap. Source baseline:
`ade1057df35a2b394106e9a2dc6adfaf0bd69eef`. First implementation: PR #1008.

## Problem and first deliverable

`TraceStrategyDispatchLearner` currently selects continuations from a bitset of
available rule IDs. Scoped symbol identity is already present, but shared bindings
must remain meaningful across later pattern constraints before dispatch can use
them. The existing `RulePatternMatcher` also commits to a locally successful
commutative binding too early: `(A+B)*(A-B)` can fail against `(y+x)*(x-y)`.

Correct this existing application path and supply a bounded typed sequence matcher
in the same matcher API. A later constraint may reject an earlier alternative;
search must resume from the earlier choice, without leaking its partial bindings.
Use explicit search frames, not a recursion stack proportional to the number of
matched nodes. Preserve direct-before-swapped-before-repeated-associative order.

## Contracts

- Repeated placeholders compare actual `Expr` values, including scoped symbol IDs.
  Display names do not become identities. Alias values keep their existing meaning.
- Binary commutativity is limited to the existing ADD/MUL behavior. The existing
  repeated-placeholder associative special case remains supported; this is not
  a new general associative-commutative matcher.
- Existing one-expression entry points keep their return types and successful
  first-binding preference. No caller receives a partial mutable binding map.
- Typed sequence matching takes 1 through 64 pattern/expression pairs, immutable
  initial bindings and a positive work limit. Outcomes distinguish MATCH,
  NO_MATCH and BUDGET_EXHAUSTED. A cutoff is not evidence of impossibility.
- Work counts attempted matching/decomposition operations, including failed
  alternatives. Parsing, allocation, value equality internals and numerical bit
  complexity are not claimed as CPU costs by this counter.
- A binding result is applicability data, not a mathematical proof, learned-rule
  authorization or permission to promote a strategy.

## Verification

Start with actual failing cases through the current API. Add cross-step order
recovery, scoped identity/alias controls, repeated composite bindings, immutable
seeds/results, negative residuals, exact work boundaries, and an independently
specified small permutation oracle. Existing matcher and learning tests remain.
Current-head Java-25 CI and review remain mandatory; unavailable local execution
must not be described as a successful test run.

## Remaining approved sequence (not delivered by this slice)

1. Complete scoped CLI/HTTP input integration and bind actual learned dispatch to
   shared substitutions and occurrence context, with separately versioned policy
   identity and source-bound replay. Preserve the old experimental control.
2. Train the choice of continuation, application site and expensive state probes;
   charge selection cost and compare against strong fixed policies.
3. Learn parameterized strategy structure from multiple TRAIN traces, including
   guards, intermediate goals and negative examples; do not merely relabel macros.
4. Exercise a generation-separated curriculum where learned knowledge enables
   acquisition of further useful knowledge. Freeze evaluation before application.
5. Compare on independent structural families, reporting training, search and
   verification work, walltime, allocation and failures separately.

No phase is complete merely because a supporting API exists. No frozen corpus,
old evidence, benchmark threshold, production default or repository protection
is changed to make this work pass. No protected study is launched by this slice.
