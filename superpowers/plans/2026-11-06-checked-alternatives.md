# Checked alternatives implementation plan

> **For agentic workers:** Use superpowers:executing-plans to finish this interrupted implementation in the current session.

**Goal:** Expose bounded independently checked alternatives through existing task knowledge.

**Architecture:** Continue the existing typed quality search, collect admitted paths,
and wrap the same solver in generation-bound knowledge exploration. Preserve solve,
learning, and snapshot behavior.

**Tech Stack:** Java 25, Maven/JUnit, existing AST and exact polynomial verifier.

**Spec:** `docs/superpowers/specs/2026-11-06-checked-alternatives-design.md`

## Global constraints

- No target expressions or ordered derivations are supplied to search.
- One original budget includes routing, selection and independent replay.
- Existing solve/learn and persisted formats retain their behavior.
- Exploration cannot promote unvalidated knowledge.

## Review focus

- Stateful or throwing objective/verifier callbacks must not manufacture evidence.
- Tight routing/reserve budgets must not silently gain extra work.
- Unsupported sources and intermediate steps keep strict domain checks.
- Alternative limits, deduplication and truncated output remain explicit.
- Learned and automatic routes stay bound to the immutable generation.

### Task 1: Recover and check continued frontier exploration

Files: `MoveSearch`, `TypedMoveSearch`, `TypedSourceOnlySearch`,
`TypedSearchAlternatives`, `TypedSourceOnlyBestTest` in `regelsuche-search`.
Produces `searchBest` and `searchAlternatives` with source-bound replay evidence.

- [x] Reproduce missing API against the published tree, then run recovered search tests.
- [x] Cover both continuation contracts, independent replay, bounds, failures and deduplication.
- [x] Preserve all existing source-only quality tests.

### Task 2: Connect checked tasks and knowledge routing

Files: `CheckedSchemaTaskSolver`, `CumulativeTaskKnowledge`,
`StructuralAlternativeSearchTest` in `regelsuche-learning`.
Consumes `TypedSearchAlternatives.Result`; produces `explore(task, strategy, reserve, limit)`.

- [x] Reproduce the missing knowledge API with the recovered integration test.
- [x] Share strict input inspection and objective preparation with `solve`.
- [x] Reuse automatic strategy routing and charge its work before exploring.
- [x] Verify historical forms only after search, domain rejection, learned replay and budget boundaries.

### Task 3: Verify and integrate

- [ ] Run affected Maven reactor, review the complete diff independently and address important findings.
- [ ] Document measured outcomes and limits; publish the checked change.
- [ ] Consolidate the continuation in existing #1148 rather than opening a dependent PR.
- [ ] Verify hosted checks and merge #1148 when green.
