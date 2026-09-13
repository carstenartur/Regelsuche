# JMH precision study implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Preregister and execute a finite comparison of seven measurement protocols over the existing 29 regression benchmarks on three independent shared runners, retaining all raw evidence without selecting a winner in advance.

**Architecture:** A new study policy owns the finite matrix, order, budgets and analysis rules. A standard-library Python harness builds the existing JMH jar once, executes the declared cells serially, and retains raw JSON, logs, provenance and timing receipts. A separate report command revalidates the complete three-replicate corpus and derives descriptive precision, fail-closed ratchet and comparability results; historical v2/v3 authorities stay unchanged.

**Tech Stack:** Python 3 standard library/unittest, existing JMH 1.36 and Java 25, existing Gradle wrapper, JSON and Markdown.

**Spec:** [Issue #981](https://github.com/carstenartur/Regelsuche/issues/981); checkout-owned preregistration in `config/quality/jmh-precision-study-policy-v1.json` and `docs/jmh-precision-study.md`.

## Global constraints

- Preserve all 29 benchmark identities, families, units, maximumAllowedScore values and multipliers from the frozen v2 policy.
- Keep v2/v3 policy, verifier and history bytes unchanged; study results have their own v1 schema and are not history points or replacement production evidence.
- Seven declared protocols and exactly three independent replicas; no retries, adaptive stopping, discarded measurements or automatic winner.
- Keep workflow edits deferred until #985 and the harness review are complete; then add the explicitly authorized one-time study jobs to the existing workflow. No third workflow or weakening of ciCheck or the maximum-two-workflow contract.
- No local Gradle/benchmark start without root coordination; no protected FINAL TEST or frozen evidence refresh.
- Run controls before implementation, obtain independent review, commit locally; root owns publication and authorizes the actual shared-runner launch after reviewing the complete integration.

## Task 1: Bind the preregistration and finite matrix

**Files:** Create `config/quality/jmh-precision-study-policy-v1.json`, `scripts/jmh_precision_study_v1.py`, `scripts/test-jmh-precision-study-v1.py`.

**Interfaces:** `load_policy(root)` binds policy to unchanged threshold/decision bytes; `cells(policy, replicate)` returns the exact finite execution order; `jmh_command(java, jar, cell, output)` builds arguments without a shell.

- [x] Write unittest cases requiring the exact 29-row inventory and 1,809 nominal iteration-seconds per replica, anchored benchmark regexes, fixed three-replica orders and no threshold edits.
- [x] Run `python3 -B scripts/test-jmh-precision-study-v1.py` and retain the failing assertions.
- [x] Implement seven protocols: baseline 2/3/1; measurement 2/8/1; warm-up 6/3/1; forks 2/3/2; measurement time 2 seconds; family mix CORE 2/3/1, REWRITE_PROGRAM 4/6/2, END_TO_END_SEARCH 6/6/2; GC-profile control 2/3/1. Other times are one second.
- [x] Re-run the focused tests and retain output.

## Task 2: Reject malformed measurement evidence and preserve narrow decisions

**Files:** Extend core and unittest script.

**Interfaces:** `evaluate_result(payload, cell, inventory)` validates complete cell inventory, contract metadata, finite raw samples and semantic work counters and returns unit-aware benchmark rows; `summarize(rows)` returns median/p90 error ratio, precision and decision counts.

- [x] Add failing controls for missing/duplicate/extra benchmarks, mismatched iterations/forks/times/JDK/unit, omitted or nonfinite raw data, incompatible search counters and profiler evidence.
- [x] Add numerical boundary tests: below-ratchet LOW_PRECISION remains visible; uncertainty overlap is INCONCLUSIVE; lower bound above the unchanged maximum is FAILED.
- [x] Implement checks and the unchanged v3 arithmetic in the study schema, retaining score, scoreError, raw samples and every status.
- [x] Run the focused tests; do not edit historical verifier behavior to make candidate execution contracts acceptable there.

## Task 3: Bound execution and revalidate retained evidence

**Files:** Create `scripts/jmh_precision_study_process_v1.py`, `scripts/jmh_precision_study_supervisor_v1.py`, `scripts/run-jmh-precision-study-v1.py`, `scripts/report-jmh-precision-study-v1.py`; extend core/tests.

**Interfaces:** Runner accepts `--replicate 1|2|3 --output PATH`; report accepts exactly three `--replicate PATH` arguments and `--output PATH`. Runner records build, commands, exit codes, exact checkout and jar identities, raw-file digests, runner metadata, per-cell and complete elapsed time. Report validates receipts/digests and all replica identities before deriving canonical JSON/Markdown.

- [x] Add subprocess controls with a temporary executable emitting realistic JMH-shaped evidence, including nonzero exit, missing result, timeout, attempted output reuse and tampered artifacts.
- [x] Run controls red, then implement a private Linux subreaper per command, fixed budgets and complete failure retention without retries. Verify detached, TERM-ignoring descendants are killed and reaped; unrelated caller children survive; normal detached children finish within the original deadline. Translate and bind namespace PIDs with pidfds before signaling.
- [x] Add report controls rejecting missing/duplicate replicas, changed source/policy/binary identities, changed bytes, stale output and cross-run relabeling; require identical report bytes on replay.
- [x] Implement descriptive median aggregation with worst-status propagation, no pooled confidence intervals, within-family rankings and paired control/frozen-baseline comparisons. Keep `selectedProtocol` null.
- [x] Run the tests green and retain evidence.

## Task 4: Wire cheap controls and document one-time CI execution

**Files:** Modify `gradle/quality-gates.gradle` and `.gitattributes`; create `docs/jmh-precision-study.md`; link from `docs/performance-history.md`.

- [x] Add the cheap Python control task to normal checkout verification without adding benchmark execution by default.
- [x] Document exact matrix/budget, fixed ordering, raw retention and artifact replay, primary JMH sources, reproducible evidence semantics, no-winner status and the subsequent versioned authority/baseline requirement.
- [x] Document the additive one-time PR-only three-replica job integration, initially deferred until #985.
- [x] Complete independent harness/process review at `5a2d5c32f590f590c94d94ad3be86c9c0e4403b7`; close detached-process and adopted-child-error findings with red controls, 28 green tests and an actual Gradle help invocation through the final supervisor.

## Task 5: Integrate the authorized finite PR study

**Files:** Extend study policy, runner/provenance/report/controls and docs; append study jobs to `.github/workflows/gradle.yml`; add `MavenJmhPrecisionStudyWorkflowContractTest` with the existing pinned Jackson YAML test dependency.

- [x] Bind the original GitHub PR event, repository/branch/job and first attempt before any Java process starts; retain and revalidate launch identities. Real negative launch controls fail before implementation; 30 Python controls pass afterward.
- [x] Integrate main `85f6ce9301a7f3dcf3243937d5b463113c61846f` after #985 and the process review, preserving all five required authorities.
- [x] Parse the real workflow as YAML in contract tests; prove the absent study jobs fail, then add three bounded replicas and an always-retained report. Reject launch broadening, missing replicas, implicit fail-fast and discarded errors.
- [x] Run focused Maven workflow/authority/dependency-graph contracts: 11 tests pass. Existing workflow bytes remain an unchanged prefix; the release workflow, two-workflow policy and historical JMH source/policy/control files remain byte-identical to integrated main.
- [x] Complete independent review of the final launch/workflow slice at `303be2e43562d51b4bcee1db11a6fd215489aa56`: no open material findings; 30 Python controls, 11 Maven contracts and the red controls were independently inspected. This approves the technical launch contract, not a performance result or protocol adoption.
- [ ] Retain all three actual shared-runner replicas, replay twice, inspect the preregistered adoption conditions and report actual GitHub job costs. No winner or production migration may precede those observations.
