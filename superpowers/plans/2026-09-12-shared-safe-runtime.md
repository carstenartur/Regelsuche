# Shared SAFE Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Route explicit CLI and Workbench requests through one replayable DIRECT/SAFE runtime boundary.

**Architecture:** Expose the existing V3 direct stage without preparation. An application-layer adapter owns source-only configuration, inventory, work receipts and canonical export/replay for scalar and typed requests. CLI and HTTP decode bounded JSON and call that same adapter. An explicitly authorized V4 successor adds bounded preparation inside a concrete source occurrence while retaining unchanged V3 base evidence.

**Tech Stack:** Java 25, Maven, JUnit Jupiter, existing strict streaming JSON and canonical execution/representation artifacts.

**Spec:** `docs/superpowers/specs/2026-09-12-shared-safe-runtime-design.md`

## Global Constraints

- SAFE remains opt-in; existing default requests remain unchanged.
- No FINAL TEST or product-default decision is run.
- Historical V2 and ordinary V3 analyze/verify behavior and work identities remain unchanged.
- Only #968-admitted schemas may prepare; both profiles retain the same visible inventory.
- Typed relations, concrete downstream replay, learned authorization and complete primitive/theory provenance remain intact.
- Use Java 25 focused Maven tests; root owns Gradle qualification.

## Task 1: No-preparation occurrence authority

**Files:** V3 coordinator and its focused tests under `regelsuche-search/src/{main,test}/java/de/regelsuche/search/reachability/`.

**Interfaces:** Add `analyzeDirect(String, AssumptionSignature)` and `verifyDirect(Evaluation)`; existing `analyze`/`verify` keep their contracts.

- [x] Add a regression that asserts `analyzeDirect("4*x^2-y^2", assumptions)` does not emit the native exact prepared candidate or delegated V2 work.
- [x] Confirm the regression fails before the new entry point exists, then implement direct-only dispatch using the existing occurrence traversal.
- [x] Test nested guard positives/negatives through both direct-only and ordinary V3 methods.
- [x] Run existing V3 occurrence/work and V2 characterization tests and commit the verified boundary.

## Task 2: Shared product adapter and canonical evidence

**Files:** New application classes under `app/src/main/java/de/regelsuche/runtime/`; tests in the corresponding test package.

**Interfaces:** `SafeRuntimeAdapter.analyze(Request)` and `SafeRuntimeAdapter.replay(Map<String, ?>)` share a versioned request/artifact codec. Requests carry profile, source or typed matrix request, assumptions and explicit budgets; artifacts carry inventories, outcomes, work and recorded executions.

- [x] Add real-rule tests for direct parity and one native exact SAFE-only prepared result.
- [x] Add negative tests for missing occurrence guards, unsupported schemas, throwing executors and exhausted budgets.
- [x] Admit principals with `RewriteApplicabilitySchema.coverage`, preserve every original direct executor, and retain deterministic inventory fingerprints.
- [x] Capture full `RecordedExecution` provenance; recompute and compare complete artifacts on replay.
- [x] Delegate typed requests to existing MatrixPreparation and MatrixPreparationJson without flattening their relations.
- [x] Add profile/inventory/assumption/budget/provenance tamper rejection and learned-executor retention tests.
- [x] Run focused Maven tests and commit the adapter.

## Task 3: CLI, Workbench and product parity

**Files:** `CliRouter`, `WorkbenchRequestBodies`, `WebWorkbenchServer`, `web/index.html`, `web/app.js`, product CLI/HTTP tests and user documentation.

**Interfaces:** Explicit CLI runtime request/replay options and a nested runtime request or replay artifact on the existing Workbench search route call the same adapter factory.

- [x] Add a real CLI-to-HTTP artifact parity/replay regression before adding handlers.
- [x] Route both surfaces through the adapter; retain current behavior for ordinary requests.
- [x] Add opt-in profile/assumption controls and visible runtime evidence in the Workbench.
- [x] Retain product cases for scalar direct/SAFE parity, guarded controls, typed relations and concrete replay, status distinctions and tampered imports.
- [x] Run the focused Maven surface and adapter suites plus affected legacy tests.
- [x] Request independent review, fix substantive findings, commit and report acceptance coverage and remaining integration gates.

## Task 4: Explicit successor for guarded preparation inside a context

The product characterization exposed an acceptance gap: V3 cannot prepare
`1+((a/b)+0)*(c/d)` for `rational_multiply_fractions`, even when the explicit
support rule `ast_add_zero_right` and assumptions `b != 0`, `d != 0` are present.
The parent authorized a separate successor authority on 2026-09-13. Historical
V2 and V3 execution and certificates remain unchanged.

**Ownership:** The authority worker owns new search authority/replay classes
and their tests. The adapter worker owns application dispatch, request profile,
CLI/HTTP/browser cases and documentation. Coordinate Maven runs in this worktree.

- [x] Reproduce the missing principal through actual CLI and HTTP, including
      fresh artifact replay; keep DIRECT and historical V3 explicitly negative.
- [x] Implement versioned V4 occurrence preparation with bounded total work,
      occurrence-local guard bindings, complete lifted provenance and independent
      concrete replay. Retain underlying V3 evidence and all spent work.
- [x] Reject wrong domain assumptions and altered occurrence, profile, inventory,
      lineage and work evidence. Keep budget exhaustion and technical failures distinct.
- [x] Route only the new `SAFE_PREPARATION_V4` profile to the successor; retain
      `SAFE_PREPARATION_V3` in the request/replay API and keep SAFE opt-in.
- [x] Qualify nested prepared success and negative controls through CLI, HTTP
      and actual browser controls; rerun affected existing V2/V3 tests.
- [x] Commit the verified successor and obtain independent review before integration.

Independent review reproduced and closed plugin-context, raw-source-node,
negative verification-budget, local technical-status and contextual work
findings, including the late failure of a traversal with 20 principals. The
integrated main and derivative-domain-guard tree passed 266 focused Maven tests;
real browser flows and E2E compilation also passed. Complete current-head
Gradle, Maven/Product/Docker, SymPy, JMH and checkout-owned `ciCheck` remain
integration gates, as does the separate final product-default decision.
