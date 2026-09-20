# Work-replacing search implementation plan

> **For agentic workers:** Execute with `superpowers:executing-plans`; preserve the existing search and frozen experiments.

**Goal:** Let validated shortcuts replace dominated continuation work and let source-only quality requests stop once their declared quality is reached.

**Architecture:** Extend `MoveSearch`, not a second solver. Separate continuation identity from Pareto resource labels under an explicit state-local caller contract. Retain proof admission and all attempted-edge evidence. Preserve the existing path-sensitive default and complete-reference mode.

**Tech stack:** Existing Java 25 multi-module project, JUnit. Focused source subsets can additionally be checked with Java 21, without changing the project toolchain.

**Spec:** The approved 2026-09-20 work-replacement design: knowledge must avoid generation, repeated continuations and unnecessary search, not merely reduce witness length.

## Global constraints

No security-rule, quality-threshold, production-default or frozen-protocol changes. No unverified proposal may dominate a verified state. Different assumptions/capabilities and incomparable resource labels remain separate. The state-local contract covers providers, admission, valuation and objective; arbitrary history-sensitive integrations keep path identity. Logical work is not CPU time.

## Review focus

1. A later cheaper route must invalidate suspended work, not only future admissions.
2. Primitive depth, search depth, theory work and complexity debt can trade off; retain Pareto alternatives.
3. Rejected proofs and work overruns must never authorize pruning or success.
4. Early quality acceptance is not target-expression discovery or proof of global optimality.
5. Complete-reference and old source-only results must remain unchanged.

## Task 1 — Safe continuation replacement

Files: `SearchContinuationContract.java`, package-private `MoveSearchVisits.java`, existing `MoveSearch.java`, `TypedMoveSearch.java`, and `MoveSearchDominanceTest.java` in the search module.

- [x] Add behavioral RED tests for duplicate continuations, stale queued routes, invalid proofs, default parity and reference-mode rejection.
- [x] Implement explicit `search(problem, contract)` overloads. `PATH_SENSITIVE` is the default. `DECLARED_STATE_LOCAL` indexes expression/assumptions/capabilities and compares all four path resources componentwise. Charge index operations and keep rejected proposals in events.
- [x] Test different premise/capability keys, incomparable labels, equality, bounded work and late arrival. Run the existing search tests.

## Task 2 — Online source-only quality control

Files: existing search engine/typed adapter/source-only wrapper, a focused online-objective state holder and source-only quality tests.

- [x] Reproduce continued generation after a sufficient verified incumbent, using an explicit quality threshold rather than a desired expression.
- [x] Evaluate root and admitted candidates online and charge each inspection within the same search budget. Terminate with an explicit quality outcome; independently replay the chosen witness.
- [x] Cover initial satisfaction, ties, rejected proof, unreachable threshold, inspection/replay overruns and unchanged legacy behavior.

## Task 3 — Qualification and evidence

- [x] Exercise actual AST matching and replay as well as synthetic graph contract tests.
- [x] Add actual learner/schema persistence and compound-substitution integration diagnostics. Retain training/restore charges; do not assert lifecycle superiority from query work alone.
- [x] Run focused local tests and document their Java 21 scope without changing the Java 25 project requirement.
- [ ] Complete unchanged Java 25 CI and review before considering the implementation qualified.
- [ ] Establish an independently registered lifecycle advantage. Lazy schema generation, full object-native transport and general learned-policy improvement remain separate slices, not claims of this implementation.
