# Continuation-safe search quotient implementation plan

> **For agentic workers:** Use superpowers:executing-plans for this task and an independent final code review.

**Goal:** Add an explicit BestFirst policy that collapses dominated future-equivalent arrivals without losing syntax, assumptions or resource-sensitive continuations.

**Architecture:** Reuse BestFirstSearchStrategy. A bounded owner-local tracker uses ExprValueArena IDs and separate syntax/resource/proof labels; SearchState retains primitive lineage. A named SearchProfile exposes the new version.

**Tech Stack:** Java 25, Maven 3.9, JUnit 5, existing exact AST and arena.

**Spec:** ../specs/2026-11-20-continuation-quotient-design.md

## Global constraints

- Exact equality must never depend on floating-point values or hashes alone.
- Keep historical policy and v1 value hashes available for retained evidence.
- Keep mathematical values separate from notation, assumptions and controller state.
- No threshold, quality-gate or proof-authority relaxation.
- Reuse existing executor, path records and SDK boundaries.

## Review focus

- Same value with different syntax can expose different future rewrites.
- A deep first arrival cannot suppress a later shallow continuation.
- Expansion-budget and proof-strength tradeoffs can require multiple labels.
- Assumption-changing cycles must not authorize unconditional equivalence.
- Arena exhaustion or unsupported parsing must not silently create equality.

## Task 1: Resource-safe continuation tracker in the existing executor

**Files:** BestFirstSearchStrategy.java, new SearchContinuationQuotient.java, BestFirstContinuationQuotientTest.java, SearchProfile.java.

**Interface:** BestFirstSearchStrategy.IdentityPolicy.APPLICATION_HISTORY_V1 and CONTINUATION_QUOTIENT_V1; constructor accepts the policy; no-argument constructor retains the historical policy. A continuation tracker exposes admit(SearchState), dominated(SearchState), size() and immutable metrics within one search.

- [x] Write failing graph controls for diamond duplicate work, resource tradeoffs, conditional assumptions, syntax-specific continuation, repeated application identifiers and primitive replay.
- [x] Run the focused Maven test and confirm the missing policy/behavior.
- [x] Implement the tracker and policy in the existing executor. Keep histories as path labels, not node identity; freeze expression-local successor results per owner.
- [x] Run the focused tests and the full core/egraph/search Maven reactor.
- [x] Add the named profile and update strategy documentation with measured finite controls.

## Task 2: Arena accounting and persistent identity

**Files:** ExprValueArena.java, an arena snapshot codec, ExprValueArenaTest.java, arena snapshot tests and research documentation.

**Interface:** Versioned snapshot data must reconstruct structural values into a fresh owner; numeric local IDs never authorize cross-owner equality. Structural byte accounting is explicitly separate from JVM heap measurements.

- [x] Write negative and round-trip tests for foreign IDs, changed snapshots, invalid graph topology, resource limits and allocation-order independence.
- [x] Implement bounded import/export and exact structural accounting.
- [ ] Run all core/search tests and compare retained canonical evidence twice.
- [ ] Independently review, resolve important findings, publish a focused PR and verify hosted CI and integrated main.
