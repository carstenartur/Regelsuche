# Knowledge lifecycle improvement implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans inline. One fresh final review; no per-task implementer/reviewer dispatch.

**Goal:** Reuse verified prepared generations and adopt existing cumulative learning only when measured total costs justify it.
**Architecture:** Opt-in indexed preparation, a caller-owned bounded load cache, and an actual-cost adoption loop around the existing learner/search/checker. Keep controls and historical experiments unchanged.
**Tech Stack:** Java25, Maven/JUnit, existing Python exact polynomial auditors and process receipts.
**Spec:** docs/superpowers/specs/2026-11-07-knowledge-lifecycle-improvement-design.md

## Global constraints

- Base main c3644ec29a0ae061c3f7980638f0680b21799c8d; new feature branch, no dependency on open PR1150.
- Existing default loading/learning behavior, snapshot format, proof gates and historical protocols remain compatible.
- No global cache, answer cache, second learning mechanism or search kernel.
- Reuse requires same inventory and identical complete previously verified canonical bytes.
- Charge actual preparation, failed/rejected learning, all measured trials and cache reads/lookups; projection is not a measured win.
- No timing assertions in unit tests. Runtime proof authority is never a hash or cost receipt.

## Review focus

- Changed files at a previously cached path and forged content addresses never reuse old proof authority.
- LRU eviction, oversized generations, close and foreign inventory do not silently retain or authorize knowledge.
- Indexed preparation survives later generations and fresh loads with exact results and charged setup.
- A single regressed or incomplete task prevents adoption even when aggregate costs improve; huge horizons do not overflow.
- Failed learning, missing CPU and atomic budget overruns retain costs and the previous active generation.

## Task1: Prepared execution and bounded reuse

**Files:** modify CumulativeTaskKnowledge.java and TypedLearnedMoveInventory.java; create TaskKnowledgeSession.java and TaskKnowledgeSessionTest.java in the existing evolution packages.

**Interfaces:** Add CumulativeTaskKnowledge.emptyPrepared(inventory,profile), restorePrepared(inventory,json), loadPrepared(inventory,path), usesIndexedPreparation(). Carry a runtime-only preparation flag through constructors and learner successors. TypedLearnedMoveInventory.newTaskSolver(model,boolean indexed) shares the existing implementation. TaskKnowledgeSession(inventory,maxGenerations,maxCharacters) exposes prepare(Path):Preparation, statistics():Statistics and close(). Preparation contains knowledge, reused, lookupWork and preparationWork; statistics report hits/misses/failures/evictions/current capacity and aggregate paid work.

- [x] Write failing real-knowledge tests: indexed restore and successor equivalence; first load charged/second reuses same immutable object; changed/false/symlink snapshot rejected; foreign inventory rejected; LRU eviction, uncached over-size load, close and failed load accounting.
- [x] Run Maven focused tests and retain expected missing-API RED.
- [x] Implement opt-in indexed preparation and bounded full-canonical-byte reuse using TaskKnowledgeSnapshot.read and existing restore/proof APIs.
- [x] Run focused tests and the complete learning reactor; commit.

Run: `mvn -o -B -ntp -pl regelsuche-learning -am -Dtest=TaskKnowledgeSessionTest -Dsurefire.failIfNoSpecifiedTests=false test` (Java25).
Expected: all new semantic tests pass, no failures/errors/skips; default existing contracts stay green.

## Task2: Actual-cost adoption and repeated improvement

**Files:** create KnowledgeCostGate.java, TaskKnowledgeSelfImprover.java and corresponding JUnit tests.

**Interfaces:** KnowledgeCostGate.Cost(wallNanos,cpuNanos), Sample(cost,solved,score,complete), Trial(previous,candidate), and evaluate(paid,List<Trial>,futureQueries):Decision. CPU=-1 means unavailable; exact BigInteger comparison of projected wall AND CPU savings against paid cost, per-task quality protection. TaskKnowledgeSelfImprover.Batch(lessons,validation,promotion), Policy(maximumLearningWork,maximumWallNanos,maximumCpuNanos,maximumBatches,measurementRepeats,futureQueries), improve(initial,batches,policy):Result. Result retains active immutable knowledge, ordered attempts, known work, actual wall/process CPU and accounting status. Attempts retain candidate IDs, measured trials, decisions, paid work and failure details.

- [x] Write literal failing gate tests for profitable/unprofitable horizon, equal/slower costs, one-case quality regression, missing CPU, invalid samples and long-count overflow.
- [x] Write real learner tests for no-op/rejected attempts retaining costs, split overlap rejection, budget exhaustion, technical failures and accepted-parent chaining whenever adoption occurs (no nondeterministic timing acceptance assertion).
- [x] Run focused tests RED; implement the controller using learnBatch and process CPU/monotonic clocks, with actual alternating-order promotion trials and alpha-disjoint promotion inputs.
- [x] Run focused and full Java reactor; commit.

Expected: pure gate decisions match literal arithmetic; real tests verify actual learner paths and all costs without assuming timings. Per-call atomic work can overrun a cutoff only with explicit full paid receipt and no adoption.

## Task3: Freeze and execute lifecycle evidence

**Files:** new opt-in lifecycle worker/driver and frozen small protocol under config/benchmarks; docs/research/knowledge-lifecycle-improvement-2026-11-07.md and numeric summary. Reuse exact polynomial auditing and wait4 transport. No workflow additions.

**Interfaces:** Source-only tasks, declared lesson/validation/promotion/query splits, fixed settings. Compare legacy repeated loads and prepared-session repeated loads with first independent restore included; one-load and repeated-load controls, retained negative cases, three rotated repetitions. Exercise actual self-improvement including rejected attempts. Publish source before first measured execution; retain originals/runtime and failure receipts. Driver validates quality before computing ratios.

- [ ] Freeze exact tasks/budgets/lengths and write negative evidence tests before executing the new corpus.
- [ ] Run full Java/Python suites and unchanged semantic coverage/complexity gate; commit runnable source.
- [ ] Execute original protocol, independently audit results and complete CPU/RSS/lifecycle costs; keep negative results.
- [ ] Report factual gains/limits and expose reusable APIs with examples, then commit.
- [ ] Fresh whole-branch review using the five Review Focus lines; one RED→GREEN fix pass for substantive findings, affected full tests and reverify untouched data.
- [ ] Publish one focused PR against main and durable evidence. Do not claim broad SymPy superiority from this experiment.
