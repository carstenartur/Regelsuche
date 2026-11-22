# Native Polynomial Maven Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run and verify the original 675-row native study through a required Maven job.

**Architecture:** Share #1340's source registration, resolved Maven runtime and fail-closed archive capture. Run the original Python judge and Java worker inside Testcontainers.

**Tech Stack:** Maven, JDK 25, Testcontainers, pinned Python 3.12.12/SymPy 1.14.0/mpmath 1.3.0.

**Spec:** `docs/superpowers/specs/2026-11-21-native-polynomial-maven-design.md`

## Global constraints

- The public 75-source, three-profile, three-repetition protocol is unchanged.
- Preserve the pilot, historic source proof, Typed and Schema contracts.
- Preserve the legacy and pilot CI dependencies and require the Native job.
- Full Docker execution and original-set rows require the hosted job.

## Task 1: Read-only registered source admission

**Files:** `scripts/external_polynomial_comparison/{registered_source,run_native,test_native}.py`;
`regelsuche-release/src/maven-test/java/de/regelsuche/release/PolynomialArchiveProvenance{Main,Test}.java`.

- [x] RED: configured archive admission rejects a missing registration and never falls back to ambient Git. Test source byte/mode tampering and unregistered implementation.
- [x] Route only native source provenance through `portable_source_provenance(repository)` and the Java checker. Keep Schema's existing caller on the existing source check.
- [x] GREEN: run Python controls and Maven tests for registration and provenance.

## Task 2: Pinned Native workflow

**Files:** `.github/workflows/gradle.yml`, `Dockerfile.external-polynomial`,
`config/quality/container-image-policy.json`, the Maven build-contract tests,
`regelsuche-release/pom.xml` and the Native container/archive tests.

- [x] RED: exercise malformed result member paths and missing native workflow/aggregate gates.
- [x] Stage tracked source and both ordered Maven classpaths. Run Native execute and independent verify, enforce 675 rows, bind reported source to the expected identity and retain evidence with the pilot's fail-closed archive helpers.
- [x] Add the pinned Native job and its aggregate dependencies. Record complete Search/Learning Surefire receipts. Keep existing jobs required.
- [x] GREEN: run focused Maven tests for archive policy and the workflow contract. Check receipt extraction and mutations that remove either aggregate binding.


- [ ] Run the pinned study and retain all 675 rows in hosted CI.

Local checks passed in the 15-module Maven reactor with the Native profile
selected through the test phase. Workflow and image contracts passed 33 tests.
Release controls passed 11 tests with one existing opt-in case skipped.
The Python Native, Schema and registration suites passed 29 controls. The
missing-registration and missing-aggregate cases reproduced before the fixes.
