# Complete linear certificate verifier implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans. Continue authorized implementation and verification without approval pauses between reversible tasks.

**Goal:** Verify complete rational affine solutions through retained typed row operations without a second solve.

**Architecture:** Add an immutable proof carrier and independent bounded checker in the existing linear algebra package. Capture proof during the existing solver execution through additive wrappers; preserve legacy records and full audit.

**Tech Stack:** Java 25, existing exact Rational/AST types, Maven and JUnit.

**Spec:** `docs/superpowers/specs/2026-11-10-linear-certificate-verifier-design.md`.

## Global Constraints

- Java 25 in existing math modules; no new ULR module or Python mathematics.
- Existing Result/Step equality, v1 JSON and historical work ledgers remain compatible.
- Source binding, rank, nullspace independence, complete free-parameter coverage and source-bound inconsistency are mandatory.
- Do not call a solver, bridge rank elimination or the producer's full verifier inside the new checker.
- Charge and bound certificate export and verification separately; never verify on exhaustion.
- Keep the full audit as qualification oracle; no default learning-policy or historical protocol change.
- No performance, novelty or general learning-benefit claim without measured evidence.

## Review Focus

1. Residual-valid but incomplete affine families must fail completeness checking.
2. Cancelled variables and constant contradictory rows must remain source-bound.
3. DIRECT's valid coefficient-RREF inconsistent form must not be rejected for lacking augmented RREF.
4. Interleaved block row maps must cover every row once; operations use global columns.
5. Budget exhaustion and evidence-export failure must be explicit and preserve legacy construction results.

## Task 1: Export, verify and qualify complete typed evidence

**Files:** existing linalg package in `regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/linalg/`; corresponding test package; focused public documentation.

Create `ExactLinearSolutionCertificate`, `ExactLinearSolutionVerifier` and, if useful for keeping responsibilities clear, `ExactAffineSourceVerifier`. Modify `DirectScalarEliminationSolver` and `LinearRepresentationPlanner` only to retain typed proof during the existing execution. Add focused certificate, verifier, planner integration and qualification tests. A separate learning-adoption change is not part of this task.

**Interfaces:**

- Immutable `ExactLinearSolutionCertificate(String schema, List<Part> parts)` with `Part(List<Integer> sourceRows, List<ExactRrefReduction.RowOperation> operations)`.
- `ExactLinearSolutionVerifier.verify(List<String> source, ExactLinearSolutionConsequence consequence, ExactLinearSolutionCertificate certificate, RepresentationBridge.Budget budget)` returning status, deterministic stage work, detail and derived ranks only on a valid result.
- Additive `LinearRepresentationPlanner.solveCertified` and `executeCertified` returning legacy Result, optional certificate and separately charged certificate work. Use one producer execution; no string parsing or second solve to obtain proof.

- [ ] Write failing behavioral tests for a valid typed proof and residual-only attacks: `x+y=1` falsely UNIQUE, missing/duplicate basis, changed RHS, omitted cancelled coordinate and false contradiction.
- [ ] Capture typed DIRECT operations and MATRIX row operations through optional planner evidence collection. Keep existing legacy results/work equal for the same inputs.
- [ ] Independently normalize the bounded source, validate row partition and typed operations, replay full augmented rows, derive coefficient/augmented rank, and validate canonical complete consequence plus original residuals.
- [ ] Exercise all routes, interleaved blocks, multiple contradictory rows, rectangular/rank-zero cases, malformed row maps and operations, domain boundaries, zero/exact/one-short budgets and every verification stage.
- [ ] Differentially qualify deterministic small rational systems against unchanged full audit. Report actual construction/export/checker/oracle costs and outcomes separately; do not count a dot product as one primitive.
- [ ] Run focused Maven tests: `./mvnw -pl regelsuche-math-algorithms -am -Dtest=ExactLinearSolutionCertificateTest,ExactLinearSolutionVerifierTest,LinearRepresentationPlannerTest,DirectScalarCertificateIdentityTest,ExactRrefCertificateIdentityTest -Dsurefire.failIfNoSpecifiedTests=false test`, adjusting only to actual new test names. If Maven dependency resolution is unavailable, retain the failure and use actual current-source/JUnit supplemental execution; canonical CI remains required.
- [ ] Commit code, tests and concise documentation, obtain an independent scoped review, fix material findings, then qualify the current exact head through existing CI. Preserve existing test assertions, baselines and policies.
