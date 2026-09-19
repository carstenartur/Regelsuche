# Typed learning dispatch implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Connect typed search, learned scheduling and checked compiled continuations.

**Architecture:** Reuse MoveSearch and CompiledAstRewriteProgram. Add explicit typed
policy and feedback entry points; select a frozen policy from measured TRAIN runs.

**Tech Stack:** Java 25, Maven, Gradle, JUnit.

**Spec:** `docs/research/typed-learning-dispatch-1021.md`

## Global constraints

- Keep historical String execution and frozen study artifacts unchanged.
- No new dependencies; Java 25 and existing build/quality gates remain mandatory.
- All mathematical proposals require registered-source regeneration.
- Search, policy and verification work use one ledger; training cost is separate.

## Review focus

- Noncanonical or scoped ASTs must never pass through ExpressionParser.
- Failed replay must still charge actual regeneration work.
- Missing assumptions and wrong occurrence must fail closed.
- Persisted data cannot authorize an edge without regeneration.
- A frozen policy must not learn from evaluation outcomes.

### Task 1: Typed history and priority integration

- [x] Add `TypedHistoryMovePolicyTest`: actual typed TRAIN search, freeze history,
  then rank typed proposals; inspect rejected and accepted decoded events.
- [x] Run the new test against the old implementation and retain the failure.
- [x] Add explicit `TypedMoveSearch.TypedPolicy`, typed structural context and
  TRAIN observation, preserving legacy factory behavior.
- [x] Run `StagedMoveSearchTest,RuleHistoryMemoryTest,TypedHistoryMovePolicyTest`.
- [x] Commit implementation and tests.

### Task 2: Compiled and persisted proposals

- [x] Add `TypedProgramMoveProviderTest`: real two-stage program, primitive-depth
  bounds, missing premises, forged source/metadata, persisted proposal and budget
  exhausted by regeneration.
- [x] Run new tests, then adapt the existing compiler and replay boundary.
- [x] Run `TypedProgramMoveProviderTest,CompiledAstReplayCodecTest,CompiledAstReplayBoundaryTest`.
- [x] Commit implementation and tests.

### Task 3: Measured TRAIN policy selection

- [x] Add `TypedPolicySelectionTest`: choose a successful cheaper declared profile,
  retain all trial work, deterministic ties, reject non-TRAIN/duplicate inputs,
  and preserve the frozen snapshot through evaluation.
- [x] Run new tests, implement the bounded selector, and rerun the tests.
- [x] Complete final Maven and matching Gradle reruns after review corrections.
- [x] Record final test counts and remaining scientific boundaries in the spec.
- [x] Commit, obtain fresh branch review and fix material findings.
- [ ] Publish the prepared branch and PR after the user explicitly authorizes the publication blocked by automatic approval review.

### Task 4: Connect the existing trace learner to the typed inventory

- [x] Add an integration test that trains the real `TraceRewriteStrategyLearner`,
  compiles its admitted primitive traces, and solves a typed transfer using a
  learned edge under a one-edge limit where the primitive control cannot succeed.
- [x] Preserve scoped symbols, exact rationals and nested function arguments in
  that same transfer, with the training formation cost exposed separately.
- [x] Add `FrozenStrategy.typedMoves()` backed by `TypedLearnedMoveInventory` and
  the existing compiler; no duplicate learner or execution algorithm.
- [x] Obtain final review on the complete branch; both material findings have regression fixes.
- [x] Complete the final module-suite rerun recorded under Task 3.
