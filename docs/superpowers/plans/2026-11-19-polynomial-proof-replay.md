# Polynomial proof replay implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Independently replay bounded native polynomial equality proofs on the existing retained Z3 boundary.

**Architecture:** Keep `Z3EqualityProofReplay.verifies(Obligation, String)` and
`RetainedAttempt.replaysEqualityProof(Obligation)`. Separate bounded native wire
syntax, typed real/Boolean values and exact rewrite authorization from the proof
step/dependency checker. Reuse the existing polynomial backend.

**Tech Stack:** Java 25, existing Solver IR and exact polynomial normal forms,
JUnit, existing Gradle/Maven checked-proof CI.

**Spec:** [Design](../specs/2026-11-19-polynomial-proof-replay-design.md)

## Global constraints

- No changes to external confirmation or capability semantics.
- No ignored premises, invented rewrite authority or approximate arithmetic.
- Existing 128 KiB / 8,192-node / depth-128 wire bounds remain.
- Explicit finite expansion/leaf limits; unsupported input returns false.
- Only positive integer power exponents 1–20; native exponent zero is rejected
  without assuming `0^0 = 1`.
- Conservative pre-normalization coefficient admission: at most 4,096 bits
  each for coefficient numerators and a common denominator, including
  coefficients embedded in nonconstant terms.
- Premise-bearing congruence proofs must cover changed, not-yet-covered
  argument positions. Dependency-free coercion reflexivity remains allowed;
  the check makes no general premise-minimality claim.
- No additional host runtime or third-party dependency.
- Keep #1066 open; optional replay does not change external status/capabilities.

## Review focus

- A condition-dependent native rewrite must not become an unconditional fact.
- A nonidentical polynomial can still equal zero at particular valuations.
- Shared let terms must not cause exponential expansion before admission.
- A multi-binding let cannot refer to its sibling's newly introduced value.
- An unrelated valid antecedent must not manufacture premise usage.
- Unchanged or already covered congruence positions cannot carry extra premise
  dependencies merely to satisfy the all-premises check.
- Zero powers must not import a convention absent from the native Z3 term.
- Constant coefficients in variable-containing expressions must receive the
  same conservative growth admission as purely constant expressions.

## Task 1: Exact compound-term replay

**Files:** `Z3EqualityProofReplay.java`, focused package-local support classes in
`regelsuche-solver-portfolio/.../portfolio/`, new `Z3EqualityProofReplayTest.java`
and captured test resources.

**Interfaces:** Consume existing typed `SolverIr` and polynomial backend; keep
the public boolean replay API unchanged.

- [x] Add captured distributivity and explicit congruence tests; observe RED.
- [x] Implement bounded term/formula parsing, parallel bindings, congruence,
  reflexivity, exact rewrite and modus ponens with real dependency propagation.
- [x] Add semantic counterexamples and admission controls for each review focus.
- [x] Run the focused tests and existing Z3 tests; confirm GREEN.

### Follow-up from the final review of `34447245`

The preceding checkmarks describe verification before these three additional
findings. The corrections below were verified separately: the four new raw
regressions first failed in a 17-test run, and all 17 raw tests passed after
the fixes.

- [x] Reject exponent zero and retain positive exponent-boundary controls.
- [x] Bound coefficient numerator/common-denominator estimates to 4,096 bits
  each before normalization, including coefficients in nonconstant terms.
- [x] Reject premise-bearing congruence proofs supported only by unchanged or
  already covered positions; retain dependency-free coercion reflexivity.
- [x] Observe the relevant regressions and passing corrected tests, including
  genuine native objects, and record the actual result without reusing the
  earlier green count.

Corrected local verification on 2026-11-19: the complete Gradle
`solver-portfolio` suite reported 84 tests, with 79 executed successfully and
five optional `CheckedLeanRealTest` tests skipped; there were no failures or
errors. The raw/native Z3 subset passed all 44 tests without skips in both
Gradle and Maven, and all eight Maven reactor modules succeeded. The combined
pinned `aiKnowledgeCheck` passed, including eight coverage controls and
17 complexity hotspots. The retained local logs are
`issue1066-review-regressions-red.log`, `issue1066-review-verification.log`
and `issue1066-review-maven-tests.log`. Skipped Lean controls and pending
publication/CI/merge gates are not counted as successful execution.

## Task 2: Genuine execution, documentation and integration

**Files:** `Z3SmtSolverBackendTest.java`, retained-attempt Javadoc,
`docs/proof-bridge.md`, `docs/research/strategies.md`.

**Interfaces:** Generate objects through `executeWithEvidence`; verify via the
existing retained-attempt method, without manufacturing stronger solver status.

- [x] Add fresh Z3 polynomial positives and unchanged native conditional
  rejection controls; run with a real provisioned Z3 executable.
- [x] Run the complete solver-portfolio suite, affected Maven tests and the
  pinned repository quality gate; inspect failures and actual test counts.
- [x] Document supported rules, bounds, exact examples and remaining gaps.
- [x] Obtain an independent whole-branch review and fix material findings with
  observed regression failures and passing affected suites.
- [ ] Publish a focused PR, await all applicable CI, integrate current main and
  SHA-bind the merge. Update #1066 truthfully without closing unfinished work.
