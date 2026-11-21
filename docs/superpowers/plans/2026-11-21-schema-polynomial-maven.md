# Schema Polynomial Maven Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement the tasks.

**Goal:** Execute and independently verify the frozen 492-row checked-schema
study in a required Maven job.

**Architecture:** Extend the shared frozen-study container; add a Schema-only
portable source admission operation using the existing strict Java proof.

**Spec:** `docs/superpowers/specs/2026-11-21-schema-polynomial-maven-design.md`

## Task 1: Portable Schema source admission

- [x] Demonstrate missing/staged receipts, incorrect proof fields, and source
  changes are rejected, with no ambient Git fallback.
- [x] Add the read-only Java `schema-freeze` operation, verifying actual source
  registration before the unmodified ancestry and historical blob verifier.
- [x] Add a Schema-only Python pre/post admission wrapper. Preserve the generic
  Git helper, protocol, source-local freeze metadata and study accounting.

## Task 2: Required container execution

- [x] Extend the shared runner with Schema, retain the original proof and check
  extracted metadata against the admitted revision, tree, freeze and hash.
- [x] Add the Failsafe profile, required workflow job and aggregate contracts.
- [x] Run focused Python and Maven controls with bounded JVMs. Independently
  review the source and historical protocol admission, and evidence transport.
- [ ] Publish the reviewed branch and qualify it against accepted main.
- [ ] Execute all 36 sequences / 492 rows in the pinned hosted worker, retaining
  the original verification and complete evidence. This requires Docker CI.

## Constraints

`PolynomialSourceProof`, the three original freezes, registered protocol blob,
seed objects, study worker and independent judge remain unchanged. A configured
portable worker fails if either source receipt is missing or invalid. This work
does not authorize the separate #748 600-row qualification or private #750 data.

## Local verification

The configured-worker fallback, unavailable Java operation, missing mandatory
Schema job, and broken staged receipt were reproduced before their respective
corrections. The focused Python run passed 61 controls. The Maven run passed
22 frozen-environment contracts and 12 source/metadata/archive controls; the
existing opt-in source-registration capture remained skipped. Independent
review of the implementation found no Critical or Important issues. These
checks do not replace the full pinned Docker study or product-wide CI.
