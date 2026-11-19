# BestFirst memory continuation implementation plan

> Execute inline in the isolated `work/696-preserve-memory-continuations`
> worktree. Parent review precedes any commit or publication.

**Goal:** Preserve the current BestFirst run's legal continuations when durable
value observations omit the application history that controls further moves.

**Architecture:** Keep the current typed visited key and durable observation
schema. Use the existing migration-aware observation-only gate path for every
BestFirst mode; leave other strategies' gate semantics unchanged.

**Stack:** Java 25, JUnit Jupiter, Gradle `:regelsuche-search:test`.

**Spec:** [Design and claim limits](../specs/2026-11-19-best-first-memory-continuations.md).

**Base:** `eb3faf92487ab839ac53c0e3df5b78ada0d7ea11`.

## Task 1: Reproduce and preserve continuations

- [x] Add a real `ast_power_of_power` regression for `((x^2)^2)^2` with
  fresh, repeated and warmed memory; compare actual states and reduction paths
  with the same search without memory. Include continued value-target and A*
  controls.
- [x] Run `:regelsuche-search:test --tests '*BestFirstMemoryContinuationTest'`
  before the production change. Expected: the memory-enabled cases lose
  `x ^ 8` while the no-memory controls reach it.
- [x] Replace BestFirst's durable cutoff with observation-only recording;
  retain local visited checks, legacy migration, assumptions and budgets.
- [x] Update the obsolete telemetry expectation to verify actual exploration,
  no phantom pruning and retained legacy observations. Exercise assumption
  separation with memory. Leave standalone gate behavior unchanged.
- [x] Run the focused class, telemetry, goal-aware and transposition integration
  tests. Expected: all pass, including existing exact-target controls.

## Task 2: Document and verify the bounded contract

- [x] Update the public search-intelligence contract and relevant Java comments.
- [x] Add a strategy-index entry with hypothesis, implementation, observed
  comparison and limits; refresh and check source anchors.
- [x] Run `:regelsuche-search:test` without a class filter. Expected: all search
  tests pass; retain exact counts and logs in build-support.
- [x] Review the final diff for unintended schema, API or other-strategy changes.
- [x] Parent review of diff and test evidence; independent whole-branch review.
- [x] Run affected Maven tests and the pinned repository quality gate.
- [ ] Publish and merge the focused PR under the existing user authorization
  once all applicable hosted checks pass; keep #696 open.

## Execution record

- Pre-flight: Task 2 documents the Task 1 contract; no new shared interface or
  persisted field is introduced.
- Ruling: use observation-only BestFirst memory, as approved by the parent,
  because the existing durable summary cannot certify controller continuations.
  The compatibility cost is potentially more current-run exploration.
- Task 1: complete. Before the production change, all five new regressions
  failed at the explored-state comparison: fresh/value-target memory retained
  two states and warmed/repeated memory retained only the root, against four
  states without memory. After the change, all 36 focused tests passed with
  zero skips, failures or errors. Logs: `issue696-regression-red.log` and
  `issue696-focused-green.log` in the session's build-support directory.
- Strategy source links were regenerated and checked with
  `StrategyCodeReferences.java --write` and `--check`.
- Task 2: local verification complete. The full search-module suite passed
  769 tests across 134 suites with zero skips, failures or errors. Log:
  `issue696-search-full.log`; counts: `issue696-search-full-results.json`.
  `git diff --check` and the final strategy-anchor check also passed. The
  production change is confined to BestFirst's observation/cutoff boundary;
  the gate implementation, public signatures and persisted schema are unchanged.
- Parent verification and independent review of local commit `920e3c13`
  completed with no critical or important findings. Optional cleanup of the
  private zero-valued transposition counter is deferred; the public metric
  remains accurate. The review does not establish global completeness or
  safety of the other strategies' durable pruning.
- The affected Maven reactor passed 42 tests across five classes with zero
  skips, failures or errors and four successful modules. The pinned
  `aiKnowledgeCheck` passed, including eight coverage controls and all 17
  complexity hotspots. Logs: `issue696-maven-tests.log` and
  `issue696-quality.log`. Hosted checks and integration remain pending.
