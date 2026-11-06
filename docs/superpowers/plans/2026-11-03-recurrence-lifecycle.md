# Recurrence lifecycle implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Measure and independently audit complete recurrence-learning process lifecycles.
**Architecture:** A thin Java JSONL adapter invokes public cumulative knowledge APIs. A Python driver reuses existing process measurement/runtime snapshots and independently checks exact laws.
**Tech Stack:** Java 25, Maven/JUnit, Python standard library on Linux with wait4.
**Spec:** `docs/superpowers/specs/2026-11-03-recurrence-lifecycle-design.md`.

## Global Constraints

- No production solver/learner change and no historical v2 file modification.
- Profiles BASELINE/EXACT_FROZEN/FAMILY; lengths [1,8,64]; repetitions 3; 27 actual lifecycles, 657 requests; PUBLIC_DEVELOPMENT.
- Query budget 100000, acquisition budget 500000; setup timeout 120s, query timeout 30s, Java -Xmx512m; no warmups.
- All failures retained; no ratios without complete receipts and equal independently audited success vectors.
- Whole worker CPU includes startup/shutdown/all threads; RSS is maximum process peak; finite whole-command boundary includes report/manifest child output.
- Native Java25 production runtime bytes/resources/dependencies, clean committed source and new hash-bound protocol; required gates unchanged.

## Review Focus

- Failed READY must retain owned process resources: Python real subprocess test.
- A valid alternate polynomial must not be treated as lower quality: exact auditor test.
- Learned generation must not mutate during queries: Java query test and Python binding check.
- Partial schedules/operations and missing receipts must never produce ratios: Python accounting/verify tests.
- Snapshot or runtime tampering must fail verification: Java restore and Python manifest tests.

### Task 1: Thin Java measurement adapter

**Files:** Create `regelsuche-learning/src/main/java/de/regelsuche/evolution/RecurrenceKnowledgeComparisonWorker.java` and matching `src/test/java/.../RecurrenceKnowledgeComparisonWorkerTest.java`.
**Interfaces:** Consume public CumulativeTaskKnowledge, Request, Budget, VerifiedLaw APIs. Produce the exact JSONL protocol in the spec, public handle(String) and main.

- [x] Write failing meaningful tests for actual learn/generalize/save/restore/run, baseline/producer/consumer state separation, strict fields, tampered snapshot, budget-rejected paid update and immutable query generation.
- [x] Run native Maven focused test and record expected initial failure.
- [x] Implement the thin adapter with strict bounded task conversion and paid-failure receipts; no second learning logic.
- [x] Run native focused tests to success; self-review; commit only owned files.
- [x] Independent scoped review and address material findings.

### Task 2: Protocol, driver, exact auditor and reproducible evidence

**Files:** Create `config/benchmarks/recurrence-knowledge-lifecycle-v1.json`, `scripts/external_polynomial_comparison/run_recurrence_knowledge.py`, `scripts/external_polynomial_comparison/test_run_recurrence_knowledge.py`, and `docs/research/recurrence-knowledge-lifecycle.md`.
**Interfaces:** Consume the JSONL contract above and generic existing AccountedSession/runtime provenance helpers. Produce load_protocol, schedule, audit_response, run_sequence, account_sequence, paired_comparisons, execute, verify and CLI (`--protocol`, `--classpath`, `--output`, `--verify`, internal `--execute-child`).

- [x] Write explicit protocol requests matching the existing family test and assert schedule count/order.
- [x] Write failing Python tests for exact valid/invalid law audits, coherent negative results, missing setup/rows/receipts, READY/query timeout retention and tamper rejection.
- [x] Implement driver/receipt retention/independent sparse-polynomial audit, finite supervisor boundary and report/manifest verification using existing generic machinery.
- [x] Run focused Python tests, then a real native Java producer/consumer transport check; verify distinct PIDs, complete receipts and actual unchanged-generation result.
- [x] Independent scoped review and address material findings; commit implementation and protocol before measurement.

### Task 3: Real run and integration

**Files:** Measurement evidence in a new output directory, summary documentation above; no old results rewritten.

- [ ] Confirm source clean and runtime freshly built under Java25; record exact production classpath and source/runtime binding.
- [ ] Execute all 27 lifecycles and 657 requests, retaining raw evidence and every failure. Run separate `--verify`; report measured results without presuming benefit.
- [ ] Whole-branch independent review; final targeted checks and required CI on exact published tree.
- [ ] Ensure #1135 merged; base next PR directly on main, then integrate only after all required gates pass. Record commits, CI and evidence links.
