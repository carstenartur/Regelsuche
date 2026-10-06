# P04 structural index implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove unpaid recursive expression hashing/equality from the native expression store's optional index.

**Architecture:** Keep the existing shared search kernel and AST types. Use a bounded FIFO hash-bucket index, iterative DAG-aware hashing and collision-confirming structural comparison, with session-local scratch observed through the existing retention scope. This is a bounded continuation of P04, not its completion.

**Tech Stack:** Java 25, existing Gradle/JUnit; no new dependency.

**Spec:** User's P04 in “Regelsuche: vom gelernten Zusammenhang zur eingesparten Arbeit”; repository qualification boundary in `docs/research/native-expr-qualification.md`.

## Global Constraints

- Base: `da1f33195339efe08380b47c36e83455c1e3815f`; PR #1055 already integrated at `c9709bf4ec421ed42bee4bf157f841a3c6b33096`.
- Preserve mathematical authorization, scoped symbols, rational exactness, grouping and argument order.
- Do not modify global AST equality or historical search/work revisions.
- New native work revision; `PARTIAL_ATOMIC_INVENTORY`, inconclusive public outcome and false completion remain mandatory.
- Index eviction cannot release owned expressions; failures cannot partially acquire ownership.
- No sealed holdout access, second search algorithm or claimed learning/performance gain.

## Review Focus

- Deep independently allocated equal trees must not overflow the Java stack.
- Shared DAGs must not expand into an exponential occurrence traversal.
- Colliding bucket eviction must retain FIFO order and all live references.
- Cancelled comparison/hash work must remain paid and scratch visible before release.
- Numeric byte buffers and long symbol names must contribute work and retained storage.

### Task 1: Paid structural store index

**Files:**
- Create: `regelsuche-search/src/main/java/de/regelsuche/search/moves/SearchExpressionIdentity.java`
- Modify: `regelsuche-search/src/main/java/de/regelsuche/search/moves/SearchExpressionStore.java`
- Modify: `regelsuche-search/src/main/java/de/regelsuche/search/moves/SearchExpressionRef.java`
- Test: `regelsuche-search/src/test/java/de/regelsuche/search/moves/SearchExpressionIdentityTest.java`
- Modify: `regelsuche-search/src/main/java/de/regelsuche/search/moves/NativeMoveSearch.java`
- Test: `regelsuche-search/src/test/java/de/regelsuche/search/moves/NativeRetentionSearchTest.java`
- Modify: `docs/research/native-expr-qualification.md`

**Interfaces:**
- Consumes: immutable `Expr`, `RetainedOperation`, `RetainedGraph.View`, existing store limits and statistics.
- Produces: package-local `SearchExpressionIdentity.hash(SearchExpressionStore, Expr): int` and `same(SearchExpressionStore, Expr, Expr): boolean`; public store API unchanged.

- [x] Write regressions for size-dependent hit cost, deep trees, DAG sharing, collisions/FIFO, scalar work, disabled index, and cancellation ownership/retention.
- [x] Run `:regelsuche-search:test --tests '*SearchExpressionIdentityTest'`; observe failures in the unchanged implementation.
- [x] Implement iterative hashing/comparison with actual work, memo and scratch ownership; hash only when indexing is enabled. Use integer buckets so collection operations never call recursive `Expr.hashCode/equals`.
- [x] Update native revision and qualification documentation without promoting coverage.
- [x] Run focused identity/native tests, then `:regelsuche-core:test :regelsuche-search:test :regelsuche-learning:test`; expect all green. Re-run the unchanged public P03 compatibility probe if accessible.
- [x] Commit, obtain one independent whole-branch review, and address substantive findings RED→GREEN.
- [ ] Publish the bounded follow-up PR; integrate only after exact-head required CI and review qualification.
