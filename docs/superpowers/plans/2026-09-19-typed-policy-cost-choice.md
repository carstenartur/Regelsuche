# Typed policy cost choice implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline with one final independent review.

**Goal:** Let TRAIN choose whether ranking earns its feature-inspection cost.

**Architecture:** Reuse TypedPolicySelection, HistoryMovePolicy, the actual trace
learner, TypedMoveSearch and existing canonical search-artifact serialization.

**Tech Stack:** Java 25, Maven, Gradle, JUnit.

**Spec:** `docs/research/typed-policy-cost-choice-1021.md`

## Global constraints

- No new dependency, historical study changes or production promotion.
- All configurations share the same task and search/work bounds.
- Formation, history collection and all selector trials retain separate costs.
- Evaluation cannot select profiles or mutate a frozen model.
- All successes retain registered-source replay and primitive expansion.

## Review focus

- Inventory order must bypass feature inspection, including on evaluation.
- Ranking must remain selectable when it actually reduces TRAIN work.
- Contradictory inventory-kind/nonzero-weight profiles must be rejected.
- Failed and over-budget development rows must survive artifact export.
- A cheaper one-edge witness must not be presented as CPU or general superiority.

### Task 1: Explicit inventory-order profile

**Files:** `TypedPolicySelection.java`, `TypedPolicySelectionTest.java`.
**Consumes:** Existing typed problems and immutable history snapshots.
**Produces:** `Profile.inventoryOrder(String)` and `Profile.kind()`.

- [x] Add real-generator tests comparing `selected.evaluate(problem)` with a
  direct `TypedMoveSearch` inventory-order control and a ranked control.
  Require identical outcomes and charged work for the inventory selection.
- [x] Include a twelve-distractor rewrite fixture where goal ranking beats the
  inventory profile, and reject inventory profiles with nonzero weights.
- [x] Observe the missing API and then a failing cost assertion against a
  compilation-only profile scaffold.
- [x] Dispatch by explicit profile kind, preserve the old ranked constructor,
  and serialize kinds using the v2 revision.
- [x] Run `TypedPolicySelectionTest,TypedHistoryMovePolicyTest,RuleHistoryMemoryTest`.
  Expected: all tests pass, including both opposing selection outcomes.
- [x] Commit implementation and tests.

### Task 2: Actual learned-work development comparison

**Files:** New `TypedLearningWorkStudy.java`, its JUnit test, learning Gradle task,
and the research spec's results section.
**Consumes:** Task 1 profile factory; `FrozenStrategy.typedMoves()`;
`LearnedSchedulingArtifacts.resultJson()`; content-addressed artifact writer.
**Produces:** `run()`, immutable report rows and `write(report, output)` artifacts.

- [x] Add integration tests requiring all 5 x 4 x 6 rows, retained budget
  failures, charged formation/history/profile work, frozen evaluation, and a
  successfully replayed learned solution under a budget the primitive control
  cannot meet.
- [x] Run tests against the absent/scaffold API and observe failures.
- [x] Implement the fixed protocol, separate TRAIN policies, all configurations,
  full search exports and the `typedLearningWorkStudy` Gradle task.
- [x] Run the new study tests, the complete affected Maven reactor and matching
  Gradle learning suite. Execute the study and record all outcomes honestly.
- [x] Obtain one independent review, repair material findings with regressions,
  then prepare the additional commits for the existing authorized feature PR #1037.
