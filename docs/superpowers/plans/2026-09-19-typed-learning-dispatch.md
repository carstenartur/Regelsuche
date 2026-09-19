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

- [ ] Add `TypedHistoryMovePolicyTest`: actual typed TRAIN search, freeze history,
  then rank typed proposals; inspect rejected and accepted decoded events.
- [ ] Run the new test against the old implementation and retain the failure.
- [ ] Add explicit `TypedMoveSearch.TypedPolicy`, typed structural context and
  TRAIN observation, preserving legacy factory behavior.
- [ ] Run `StagedMoveSearchTest,RuleHistoryMemoryTest,TypedHistoryMovePolicyTest`.
- [ ] Commit implementation and tests.

### Task 2: Compiled and persisted proposals

- [ ] Add `TypedProgramMoveProviderTest`: real two-stage program, primitive-depth
  bounds, missing premises, forged source/metadata, persisted proposal and budget
  exhausted by regeneration.
- [ ] Run new tests, then adapt the existing compiler and replay boundary.
- [ ] Run `TypedProgramMoveProviderTest,CompiledAstReplayCodecTest,CompiledAstReplayBoundaryTest`.
- [ ] Commit implementation and tests.

### Task 3: Measured TRAIN policy selection

- [ ] Add `TypedPolicySelectionTest`: choose a successful cheaper declared profile,
  retain all trial work, deterministic ties, reject non-TRAIN/duplicate inputs,
  and preserve the frozen snapshot through evaluation.
- [ ] Run new tests, implement the bounded selector, and rerun the tests.
- [ ] Run complete affected Maven suites and matching Gradle suites.
- [ ] Record actual results and remaining scientific boundaries in the spec.
- [ ] Commit, obtain fresh branch review, fix material findings, push a reviewable PR.
