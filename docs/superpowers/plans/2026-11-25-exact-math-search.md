# Automatic exact mathematics implementation plan

> **For agentic workers:** Use the parallel-agents workflow for independent
> components and test-driven development for each behavior. Root integrates,
> independently reviews and verifies the resulting changes.

**Goal:** Turn the accepted mathematical follow-ups into general bounded search
capabilities and one separately checked Primachsen research consumer.

**Architecture:** Extend the existing exact arithmetic, certificate types and
independent verifiers. The SDK delegates. Domain-specific chronology remains in
the separate consumer repository.

**Tech Stack:** Java 25, existing Maven modules, Python 3 for research tooling.

**Spec:** `docs/superpowers/specs/2026-11-25-exact-math-search-design.md`.

## Global constraints

Typed immutable inputs, independent exact checks, bounded work/memory/time and
explicit cancellation. Test code is subject to strict compiler warnings.
No universal complexity, speedup or Primachsen deadline claims without proof.
No changes to shared build or strategy files by parallel component owners;
the integration owner handles those files after component verification.

## Review focus

1. LP lineality/degeneracy, free-variable signs and infeasible dual/primal cases.
2. Large prime status, field identity, multiplicities and characteristic two.
3. Nested budgets and allocation/bit limits, including final cancellation.
4. Formula inversions at rejected centers and retained domain conditions.
5. Chronological certificates using future points or unproved prefix facts.

## Task 1: Bounded LP search and affine implication

Owner: LP component. Existing interfaces are `LinearProgramCertificates`,
`LinearProgramVerifier`, `FarkasSparsifier` and `LinearProgramSdk`.

- [x] Add behavior tests for infeasible, optimal, unbounded, degenerate and
  lineality cases, affine implication and unsuccessful bounded search; run RED.
- [x] Implement a native bounded exact `LinearProgramSearch` and an affine
  implication consumer in `math/algorithms/linalg`; return existing checked
  certificate forms with total work and clear incomplete status.
- [x] Expose only delegation through the linalg SDK; run targeted tests GREEN.
- [x] Document algorithms, constraints, examples and independent verification
  in `docs/research/linear-program-search.md`.

## Task 2: Large-prime polynomial factorization

Owner: prime-field component. Reuse the generic polynomial representation and
existing multiplicity and Rabin-checking mathematics without changing small
field behavior.

- [x] Test moduli beyond int, source/domain mismatch, complete factorization,
  repeated factors, characteristic two and budget exhaustion; run RED.
- [x] Add a typed large-prime coefficient domain with explicit primality status.
- [x] Add an independent complete-result verifier with its own policy.
- [x] Implement square-free decomposition, DDF and bounded randomized EDF;
  invoke independent verification before returning a complete answer.
- [x] Add thin SDK entry points and external-reference/independent examples;
  run tests GREEN and document `docs/research/large-prime-factorization.md`.

## Task 3: Automatic operator residual search

Owner: integration. Reuse `OperatorResidualPlans` and `ExactMatrixExpression`.

- [x] Test automatic discovery from source, dense/sparse/diagonal baselines,
  full-rank losing cases, exact replay and bounded acquisition; run RED.
- [x] Implement `OperatorResidualSearch` with source-derived simple predictors,
  exact residual rank factorization and checked plans.
- [x] Provide reusable typed execution and a thin SDK; account for search,
  verification, application work and the selected direct baseline.
- [x] Run representative repeated-use observations, report setup and runtime
  separately, and update the operator research page after tests pass.

## Task 4: Automatic scalar centers for rational formulas

Owner: integration. Reuse `RationalFormulaComparison` and its independent
verifier; no general universal hitting set is introduced.

- [x] Test a center requiring search, a nonidentity, all-scalar-singular
  commutator inverse, malformed requests and exhausted/cancelled search; RED.
- [x] Implement `RationalFormulaCenterSearch` over a finite rational grid with
  one overall budget and all original inversion checks.
- [x] Add the thin SDK entry point, domain-aware usage example and GREEN tests.
- [x] Update `docs/research/rational-formula-realization.md` with measured work.

## Task 5: Chronological implication research in Primachsen

Owner: Primachsen component, separate repository and commit/PR.

- [x] Read existing canonical-bootstrap and unresolved-class evidence; select
  an unresolved model/lemma instead of re-reporting offset 86 or T719.
- [x] Add tests which reject future and unproved witnesses; run RED.
- [x] Implement a general consumer for compact earlier-condition implications
  with independent checking; run tests GREEN.
- [x] Execute a bounded reproducible experiment and record success or the
  precise remaining obstruction without inferring a uniform deadline.

## Integration and delivery

- [x] Review component diffs and source binding, with a fresh independent
  review for each mathematical package after implementation.
- [x] Run the combined affected module suites and consumer verification;
  compile all changed Java sources with `-Xlint:all -Werror`.
- [x] Update strategy entries (hypothesis, implementation, result, consequence),
  regenerate their line links, and update capability/release documentation.
- [ ] Publish the verified commits as coherent PRs on the current main bases,
  inspect CI and resolve failures caused by the changes. External CI status
  is tracked on the PRs; all local implementation and review gates above passed.

## Execution decisions

The user's direct implementation instruction authorizes proceeding with the
accepted recommendation. Existing clean task checkouts are reused on new
feature branches to avoid redundant repository copies. Component owners edit
disjoint file sets; Maven access to shared targets is serialized with flock.

## Local verification outcome

The combined Regelsuche reactor recorded 3,507 tests with no failures/errors
and one existing optional export skip. All 35 changed Java sources and tests
passed strict javac. The SDK package build passed. The Primachsen Java
consumer passed all 72 tests against this source; its complete Python suite
recorded 190 tests with one optional OEIS skip and no failures/errors, plus
12 native verifier behavior controls. Four independent component reviews
closed all findings, including allocation accounting, dense-conversion
checkpoints and oversized chronology evidence.
