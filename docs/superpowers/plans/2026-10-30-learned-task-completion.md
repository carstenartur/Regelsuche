# Learned task completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make existing learned schemata solve declared tasks instead of continuing unrelated exploration.
**Architecture:** A prepared solver in the learning module binds the existing primitive inventory, lazy checked-schema cursor and verifier. Every task delegates to the existing source-only stopping frontier and independent final replay. Existing workers and defaults are unchanged.
**Tech Stack:** Java 25, existing Gradle/Maven, JUnit; no new dependencies.
**Spec:** `docs/superpowers/specs/2026-10-30-learned-task-completion-design.md`

## Global Constraints
- One search kernel and existing learner; no test/quality threshold changes.
- Preparation and learning costs remain visible; no automatic native qualification.
- No merge or release without fresh complete CI.

## Review Focus
- Goal reached but final replay exceeds budget: never report solved.
- Required premises absent: learned provider must not issue evidence; primitive fallback remains possible.
- Shared preparation across queries: no leaked cursor, result or assumption state.
- Unsupported or near-miss input: retain unsolved outcomes, never silently convert them into successes.
- Smaller and larger budgets: no additional budget for the learned arm or final replay.

## Task 1 — Prepared task-solving API and real integration contracts
Files: create `regelsuche-learning/src/main/java/de/regelsuche/evolution/CheckedSchemaTaskSolver.java`; modify `TypedLearnedMoveInventory.java` in the same directory; create corresponding `CheckedSchemaTaskSolverContract.java` and `CheckedSchemaTaskSolverTest.java` in test sources.
Interfaces: `newTaskSolver()`, `newTaskSolver(CheckedLearnedSchemaModel)`; immutable `Goal`, `Task`, `Solution`; `solve(Task)` and a preparation receipt.
- [x] Write contracts against actual learned/restored models. Initial RED was a missing-API compiler error; a later compiled mutation reverting to exploration fails behaviorally.
- [x] Implement the smallest wrapper around the existing verified stopping search and lazy cursor.
- [x] Run positive, negative, residual-work, repeat-use and post-replay budget tests.
- [x] Compare with the unchanged exploration path and baseline stopping search, including negative cases.

## Task 2 — Reproducible lesson-to-task example and documentation
Files: executable test contract plus `docs/research/learned-task-completion.md`.
- [x] Produce per-task results and a separately charged train/load/prepare comparison without a predetermined speed winner.
- [x] Run the new contracts and attempted complete project build; report exact local toolchain limitations.
- [ ] Publish a single PR from current main with fresh CI; do not relabel old experiments.
- [ ] Require full hosted Java-25 CI and independent review before merge.

## Local verification receipt

Nine real-model contracts pass, plus save/load in a separate JVM. Three compiled
mutations fail as expected: continued exploration, omitted final budget check,
and primitive-first priority. These are supplemental negative controls, not
retroactively claimed original behavioral RED. The complete Gradle build was
attempted and failed to resolve `services.gradle.org`. The exact Java-21
compatibility-copy boundary is documented in the research report.
