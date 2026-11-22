# Typed Polynomial Maven Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the existing Typed comparison, independent verification and audit in a required Maven job.

**Architecture:** Share the Native study's container transport. Verify the existing source witness against the clean registered checkout and confirm the Typed ancestor within the worker.

**Tech Stack:** Maven, JDK 25, Testcontainers, pinned Python/SymPy/mpmath.

**Spec:** `docs/superpowers/specs/2026-11-21-typed-polynomial-maven-design.md`

## Constraints

- Keep the frozen 729-row matrix, worker, audit, judgment, and result schemas.
- Preserve the exact `PolynomialSourceProof` verifier and Native study behavior.
- Add the Typed job alongside the existing CI gates.
- The pinned Docker run requires the hosted job.

## Task 1: Portable Typed ancestry

**Files:** `scripts/external_polynomial_comparison/{run_typed,test_typed_run}.py`.

- [x] Add a source-admission test showing a configured worker rejects a missing or unconnected Typed ancestry witness before output.
- [x] Bind the verified portable source identity to `require_ancestor` and the exact Typed freeze. Preserve the existing Git fallback for ordinary checkouts.
- [x] Recheck the registered identity after the portable execution. Check existing Typed and archive-provenance controls.

## Task 2: Stage, run and audit Typed

**Files:** Native and Typed container ITs, shared runner and archive controls under
`regelsuche-release/src/maven-test/java/de/regelsuche/release`, `regelsuche-release/pom.xml`,
`.github/workflows/gradle.yml`, and the Maven workflow contract.

- [x] Add controls for required Typed workflow/aggregate bindings and the audit receipt.
- [x] Reuse the Native container setup, ordered classpath and bounded extraction. Copy the existing source witness only for Typed. Retain the external audit output and validate its schema, row count and revision.
- [x] Add the separate Failsafe profile and complete CI aggregate binding. Retain raw archive, diagnostics and affected Maven module receipts.
- [x] Run local focused Maven and Python controls.

- [ ] Run all 729 rows and the original audit in the pinned hosted worker.

The missing-registration and missing-aggregate cases reproduced before the
changes. A separate local Python run passed 52 controls. The Typed-profile
Maven run passed 33 build-contract tests and 11 release controls, including
the new audit check. Its existing opt-in source registration was skipped.
The unchanged classpath relocation fixture timed out while launching its
nested Maven process. It passed on the Native base in the previous run.
