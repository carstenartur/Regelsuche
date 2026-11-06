# Historical SymPy Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan inline, task by task.

**Goal:** Execute and publish the actual historical task comparison with exact independent validation and complete process costs.

**Architecture:** Thin adapters call existing cumulative native APIs and pinned SymPy procedures. Reuse the existing measured process transport, runtime retention and Fraction-polynomial evaluator; keep experiment-specific judgments separate from the operation-count benchmark.

**Tech Stack:** Java 25, Maven/JUnit, Python, SymPy 1.14.0.

**Spec:** `docs/superpowers/specs/2026-11-06-historical-sympy-design.md`

## Global Constraints

- Preserve production solvers, historical protocols, workflows and quality thresholds.
- Freeze inputs, procedures and budgets before execution; targets never enter worker requests.
- 18 tasks, lengths 1/18/72, three profiles, three repetitions: 27 lifecycles / 819 rows.
- Query/setup deadlines 30/120 seconds; structural native budget (24,12,1000000,128,2000000), recurrence budget 100000.
- Keep actual setup/persistence/reproof/CPU/RSS/failures; no aggregate speed ratio with unequal success vectors.

## Review Focus

- A valid but wrong output shape must fail the intended goal, including unchanged products as two-square answers.
- Universal recurrence substitution must be simultaneous and bound to the exact matrix/initial state/degree.
- Case relabeling, incomplete matrices and missing/duplicated process receipts must fail verification.
- Setup timeouts and consumer failures must retain the actual owned-process receipts.
- Missing resources, false claims and bounded misses must remain distinct from successful comparable runs.

### Task 1: Executable comparison

**Files:** new `HistoricalComparisonWorker.java` and test in `regelsuche-learning`; `historical_judge.py`, `historical_sympy_worker.py`, `run_historical.py`, `test_historical.py` in the existing external comparison package; frozen protocol; reproduction shell entrypoint.

**Interfaces:** Java accepts `initialize`/`restore`/`run` JSONL operations over existing public task APIs. Python workers receive only task payloads; `audit_task(task, response)` validates any reported result independently. `run_historical --protocol --classpath --output` retains the full experiment; `--verify --output` verifies without executing competitors.

- [x] Freeze protocol and write literal negative tests for shape, identity, recurrence binding, relabeling, incomplete matrices and failed startup ownership.
- [x] Run and retain the failing tests before implementation.
- [x] Implement the adapters, independent auditor, complete lifecycle runner and strict verifier; run targeted Java and Python tests.
- [x] Commit the runnable source before the first full measurement; execute the exact frozen protocol and retain every outcome.

### Task 2: Qualification and report

**Files:** `docs/research/historical-sympy-comparison-2026-11-06.md`, numeric summary and source/evidence links.

- [x] Independently reverify original evidence, derive per-case quality and matched-quality resource summaries without retuning inputs or algorithms.
- [ ] Obtain a fresh whole-change review, reproduce/fix substantive findings, and rerun affected tests.
- [ ] Publish one focused PR and complete evidence; distinguish classical tasks, development variants and NOT_MEASURED gaps in the German result.
