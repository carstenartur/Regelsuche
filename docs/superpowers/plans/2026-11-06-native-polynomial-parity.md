# Native Polynomial Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make the native shared task solver reach at least the fixed SymPy portfolio's expression quality on the historical polynomial inputs.

**Architecture:** Add bounded native candidate procedures to the existing checked construction adapter and typed frontier. Expose source-only optimization and the same profile to primitive and learned solvers; retain independent replay and accounting.

**Tech Stack:** Java 25, Maven/JUnit, existing exact rational/polynomial algorithms, Python independent judge and pinned SymPy 1.14.0 for comparison only.

**Spec:** `docs/superpowers/specs/2026-11-06-native-polynomial-parity-design.md`

## Global Constraints

- No second search engine, external CAS production dependency, expected-answer input or case-specific mathematical rule.
- Preserve historical profile/protocol behavior; the new profile is opt-in.
- Exact polynomial source/intermediate checks, source-bound replay and one paid task budget.
- Preserve the source on objective ties; include failed attempts and acquisition/restoration in reported costs.
- Quality is per expression; logical work, wall time, CPU and RSS are distinct observations.

## Review Focus

- Variable denominators, malformed symbols and false supplied rules must not lose their domain restrictions.
- Reordered sums, negative coefficients, rational scalars and nested products must remain exact.
- Tiny budgets, failed factorization and repeated calls must retain costs without success authority.
- New candidate operations must preserve a better input/primitive result and validate fresh replay.
- Learning, persistence and comparison setup must use the same native capability set and report acquisition costs.

### Task 1: Integrate native candidate procedures

**Files:** new `PolynomialSimplificationPrograms.java` and `PolynomialSimplificationTest.java` in `regelsuche-learning`; modify `StructuralConstructionPrograms`, `StructuralConstructionProvider`, `TaskConstructionProfile`, `TypedLearnedMoveInventory`, `CheckedSchemaTaskSolver`.

**Interfaces:** `TypedLearnedMoveInventory.newTaskSolverWithProfile(TaskConstructionProfile)` and `newTaskSolverWithProfile(CheckedLearnedSchemaModel, TaskConstructionProfile)` prepare the existing solver; `CheckedSchemaTaskSolver.simplify(Expr, List<String>, MoveSearch.Budget)` returns the existing `TypedSourceOnlySearch.Result`. `POLYNOMIAL_SIMPLIFICATION_V1` attaches general candidate procedures with exact step checking. The distinct factory name preserves existing `newTaskSolver(null)` source compatibility.

- [x] Write literal regression cases for fraction arithmetic, near-miss expansion, four-term grouping, binomial cube, negative context and rational scaling; assert exact equivalence and arithmetic cost bounds.
- [x] Run the tests before implementation and retain the failing result.
- [x] Implement general candidates using existing arithmetic/normalization/native factorization; add the profile and source-only shared-frontier API.
- [x] Add domain, false-rule, tiny-budget, replay tampering, repeat-call and source-preservation guards.
- [x] Run focused and affected module tests, inspect output and commit the implementation.

### Task 2: Execute a source-only comparison

**Files:** new native polynomial worker/test, comparison protocol and runner in the existing `scripts/external_polynomial_comparison` package; reuse existing independent judge and measured process transport.

**Interfaces:** worker receives source-only JSONL requests and a fixed native baseline/learned configuration; runner retains every response, training/restoration, process costs, independent judgments and per-expression comparisons.

- [x] Freeze the new protocol, budgets, historical inputs and withheld variants before executing the new implementation.
- [x] Test response binding, invalid outputs, budget failures and incomplete matrix rejection using the existing judge.
- [x] Implement and execute native baseline/learned versus the paid pinned SymPy portfolio, with three rotated repetitions.
- [x] Independently verify retained evidence and require no worse output cost on every supported historical input; retain all variant failures if any.
- [x] Commit the executable comparison and factual report with exact limitations.

### Task 3: Qualify and publish reviewable work

**Files:** documentation and API example; existing build/CI paths only if the new checks require wiring.

- [x] Run the relevant full Java and Python suites plus repository gates supported by the environment.
- [x] Obtain a fresh whole-branch review, reproduce and fix substantive findings with regressions.
- [ ] Commit all work and publish one PR; inspect required hosted checks and address failures on the final head.
- [x] Report measured quality, resources, validation and any remaining limitations without claiming an unmeasured speed advantage.
