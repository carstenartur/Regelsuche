# Finite iteration implementation plan

Goal: replace admitted Java accumulation loops using independently verified mathematical finite-sum/recurrence reduction.
Architecture: Regelsuche finite binder IR and telescoping proof; Sandbox source admission/replay and Java emission.
Spec: `docs/superpowers/specs/2026-10-31-finite-iterations-design.md`.
Execution: subagent-driven development, sequential implementers with task review, then whole-change review. The design and implementation were already authorized by the user.

## Global constraints

- Regelsuche owns sum rules and numerical proof; Sandbox owns Java source correctness.
- Explicit exact-integer / Java-int / Java-long semantics; no floating-point reassociation or mixed-width ring shortcut.
- Constant counted ranges in this release; symbolic bounds and infinite series are documented future work, not implemented claims.
- Bounded work, expression growth and cancellation; no proof by sampling or loop unrolling.
- Separate source evidence and numerical evidence; never manufacture an existing scalar proof for a loop.
- Maven/JUnit authority; no new Python automation or unrelated refactoring.
- Source-qualified SDK built from a committed revision; preserve ordinary current-main PR bases and review-size limits.

## Task 1: Regelsuche model, candidate search and independent proof

Worktree: `/workspace/scratch/fe317af5addb/regelsuche-sums`.
Read the spec above and the existing `Expr`, scoped `VariableExpr`/`SymbolId`, exact rational arithmetic and SDK budget/cancellation idioms.

Create focused classes in `regelsuche-core/src/main/java/de/regelsuche/ast/iteration/` for immutable `IndexRange`, `FiniteIteration` (sum and recurrence variants), and `IntegerArithmetic`. Bound variables must carry scoped identities; invalid/colliding binder identities and illegal scope references fail closed. Scalar children are existing `Expr`. Bounds are `BigInteger` constants, with explicit finite size limits.

Create focused classes in `regelsuche-optimization-sdk/src/main/java/de/regelsuche/sdk/optimization/iteration/` for `FiniteIterationOptimizer`, `FiniteIterationVerifier`, and bounded polynomial support. Public API must allow: construct a model; search for a candidate under work/cancellation budget; independently verify that candidate or a supplied emitted scalar expression against its certificate/source; inspect immutable source-bound proof, work and estimated original/result operation costs. Record final exact signatures in the report for the Java consumer. Keep result/certificate values immutable. Unsupported inputs return an explicit unavailable result or fail-closed documented exception, never a verified identity.

Use polynomial antidifference synthesis and a separate telescoping checker. Check `F(i+step)-F(i)=term`, endpoints and initial accumulator. A checker must not call synthesis, compare only hashes, or trust candidate-provided cost/work. Reuse bounded polynomial arithmetic internally if useful, but verify the mathematical relation independently. Support integer polynomial operations `+`, `-`, `*` (optionally bounded nonnegative constant powers) and free scalar variables. Normalize Java values modulo 2^32 / 2^64 only after validating the supported integer ring. Reject divisions, functions, nonlinear state updates and fractional source coefficients. Independent direct evaluation may exist for tests under an explicit small budget; never use it as proof.

TDD: add focused model and SDK tests first and record RED/GREEN commands. Cover 1..100=5050, constants, squares, shifted/descending/empty/singleton ranges, invariant parameters, arbitrary initial values, int/long modular overflow, scoped-variable collisions, malformed/fractional/non-polynomial terms, nonlinear recurrence, changed result/certificate/source/arithmetic, huge count with bounded proof, growth limits and cancellation. Include independent loop/reference arithmetic comparisons for small cases. Run focused new tests then full SDK module suite once with Maven reactor dependencies.

Do not edit Sandbox or SDK packaging in this task. Target about 1000-1300 changed text lines including tests. If the supported fragment requires significantly more, report the concrete design issue before expanding. Commit code/tests and update this spec with actual supported limits if necessary. Full report must include API signatures and meaningful test evidence. Do not dispatch subagents.

## Task 2: Qualify and integrate the SDK distribution

Controller-owned after Task 1 review: publish the Regelsuche feature branch and ordinary PR, using its exact committed source revision for the distribution. Extend the existing fresh SDK consumer JUnit/packaging fixture to exercise the new finite iteration API if not already covered. Run `mvn -Psdk-release -pl maven-build-contract -Dtest=MavenOptimizationSdkDistributionTest test`, using the Java25/Maven wrapper environment for child processes. Two clean builds/fresh consumers must pass and produce byte-identical artifacts. Copy the qualified jar, qualification and reproducibility receipts/source revision into Sandbox's existing SDK layout. Keep proof of exact artifact hashes. Do not invent or edit qualification results.

## Task 3: Sandbox admission, reduction, emission and evidence

Worktree: `/workspace/scratch/fe317af5addb/sandbox-sums`.
Read root AGENTS.md and `.github/copilot-instructions.md`, this spec, Task 1 public API report and existing JavaLoopInvariantOptimizer/Analysis, MathematicalAnalysis, MathReviewReport and JavaSourceRewritePlan patterns. The controller supplies the qualified SDK before compilation.

Add focused `JavaFiniteSumAnalysis`, `JavaFiniteSumOptimizer` and `FiniteSumEvidence` classes (names may be adapted to existing conventions). Preserve the current invariant-hoisting pass. Use the spec's exact conservative Java admission. Verify both accumulator and every intermediate operation's primitive width; reject unsafe mixed-width arithmetic. Bind all terms to source declaration identities. A long accumulator may use int counter leaves only when arithmetic operators are performed at long width. Empty loops retain initial value and do not introduce reads that original never executed.

Call the SDK for the mathematical result; no summation formula belongs in Sandbox. Emit a compact assignment with correctly typed signed integer literals and exact free-variable bindings. Reparse the whole generated source, decode the actual result, independently reverify it and replay original loop admission, termination and bindings. Check stale/tampered receipts and combined edits. Add a distinct finite-iteration evidence field to VerifiedRegion with compatibility constructors; update review reporting to recognize and validate its costs without fabricating scalar VerificationEvidence. Include evidence in normal report serialization.

TDD: new JavaFiniteSumTest using the existing analysis/compile/undo helpers. Verify the 1..100 acceptance example, descending/empty/singleton ranges, polynomial terms, free parameters, initial accumulator, modular overflow, multiple edits, selected-region behavior, cancellation and low budget. Negative cases include inclusive Integer.MAX_VALUE wrap, changing counter, unsafe long/int mixed arithmetic, side-effecting/volatile inputs, calls, arrays, break/return/throw, comments and nonlocal targets. Compile and execute generated/original code on meaningful edge inputs; assert the loop actually disappears and evidence independently validates. Tamper proof/source/emitted expression and require rejection. Run focused tests and the full headless mathematics suite once. Keep changed text below 1500 if practical, hard stop/split at 2000 per repository policy. Commit code/tests/docs. Do not dispatch subagents.

## Task 4: Final qualification and publication

Controller-owned: whole-change review across the two repositories and any required bounded fix/review wave. Run native Tycho build for changed bundles, exact SpotBugs checks, relevant policy checks and complete fresh SDK/consumer evidence. Reproduce the acceptance example and retain generated source/evidence. Rebase only if required by fresh main changes; keep PRs ordinary and source receipts exact. Publish Sandbox PR, inspect exact-head CI and resolve concrete failures. No merge. Final response in German states delivered capability, tests and explicit remaining limits with both PR links.
