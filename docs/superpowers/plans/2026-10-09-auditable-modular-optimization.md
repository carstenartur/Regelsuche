# Auditable Modular Optimization Implementation Plan

> Execute with superpowers:executing-plans and test-driven-development.

**Goal:** Remove one unnecessary modular power and expose proof-bound explanations.
**Architecture:** Extend the existing modular domain, not the search core; use a
headless explanation facade over independently reverified existing plans.
**Tech Stack:** Java 25, existing Regelsuche IR, JUnit 6, Maven.
**Spec:** ../specs/2026-10-09-auditable-modular-optimization.md

## Constraints
No new runtime dependency for generated Java. Do not assume normalized bases.
Do not weaken safety profiles. Retain the original source trace. Explanations
are not proofs and never add assumptions or infer a benchmark speedup.

## Steps
- [x] Reproduce unit-power retention and missing contextual residue proof in JUnit.
- [x] Add contextual modular-product normalization and use-local candidates.
- [x] Verify negative/unreduced bases, exponent zero, modulus one, mismatched
  modulus and standalone-output negative controls (79 tests in local JUnit run).
- [x] Expose immutable source/candidate DAG explanations after independent reverify.
- [x] Integrate readable optional comments in Sandbox without changing proof authority.
- [x] Verify idempotence, cancellation, preserved comments, tampered candidate/receipt,
  and compiled modular results in the local JUnit and Sandbox Maven harnesses.
- [ ] Publish ordinary source changes and qualify them in the normal Maven reactor.
- [ ] Reproduce the full original constructor with derived field/helper-method facts.
- [ ] Qualify an independent SWT computation kernel; no result claimed yet.

## Execution record
Local Maven baseline is blocked by uncached BOM imports in the network-isolated
workspace. JUnit is run through the cached official launcher with the complete SDK
and modular-domain test sources. This is not a full Maven-reactor qualification.
