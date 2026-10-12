# Generic numeric computation domains implementation plan

> **For agentic workers:** Use test-driven implementation and independent review.
> Task briefs specify disjoint ownership where independent work is delegated.

**Goal:** Make the existing generic Java computation graph usable across numeric
domains with established premises, modular shifts, shared range analysis and
additional connected Java expressions.

**Architecture:** Preserve Expr/JointComputationPlan, the shared search engine,
source traces and independent verification. Extend semantic transport and the
SDK; Sandbox remains the Java adapter and consumes versioned SDK capabilities.

**Tech Stack:** Java 25, Maven/JUnit, Eclipse JDT, existing SDK packaging.

**Spec:** ../specs/2026-10-11-generic-numeric-domains.md

## Global Constraints

All constraints in the spec are binding. No new search engine, inferred caller
assumptions, benchmark-specific rules, relaxed proof gate or higher corpus budget.
Test commands use the available JDK 25 and Maven 3.9.16 with cached dependencies.

## Review Focus

- A conditional rational identity must not become a false Java division proof.
- A BigInteger shift must retain signed operands, int distances and size bounds.
- Range metadata must describe all original intermediates and the candidate.
- Repeated array reads must not be shared across a write or changed exception order.
- Evidence and budget limits must remain bound to the exact source and premises.

## Task 1: Conditional algebra premises

Files: JavaAlgebraCandidates.java, JavaCandidateGenerator.java, SemanticChecker.java,
SemanticAssumption.java and focused new premise/proof classes in the SDK package;
tests beside GeneralAlgebraSearchTest.java.

Produces: request-aware premise discharge for existing RewriteRule.assumptions,
with explicit known/unknown outcomes; no additions to caller facts by search.

- [ ] Add regressions for a conditional rule enabled by established facts, the
  same rule without facts, a false truncating-division rewrite, symbol/atom
  mapping, and cancellation/work limits; watch the positive test fail first.
- [ ] Implement premise transport and independent proof support for the admitted
  fragment. Unsupported predicates remain unknown and are not admitted.
- [ ] Run focused tests and the SDK suite, self-review and commit the task.

## Task 2: Modular transport of shifts

Files: ModularBridge.java and modular transport tests; modular domain classes
only where a generic mathematical transport rule is necessary.

Consumes: existing BigInteger value/range checks and typed Java expressions.
Produces: bounded nonnegative constant SHIFT_LEFT transport that round-trips
and participates in generation/independent verification with mixed outputs.

- [ ] Add tests for int-distance shifts within modular expressions, negative
  bases, distance zero, invalid/oversized/nonconstant distances, mixed outputs,
  false targets, budget/cancellation and unchanged modular normalization.
- [ ] Verify baseline failures, implement transport without losing source traces
  or widening the admitted input contract, then run focused and SDK tests.
- [ ] Self-review and commit the task.

## Task 3: Shared integral domain analysis

Files: new public SDK IntegralDomainAnalysis/interval result types and tests;
Sandbox IntegralMathematicalDomain.java and MathematicalJavadoc consumers.

Consumes: typed JointComputationPlan and SourceEvaluationTrace, source and target.
Produces: exact ranges or explicit unsupported results, portable outside Eclipse.

- [ ] Pin 2*x/2 -> x bounds, long bounds, negative coefficients, exact division,
  intermediate overflow, multiple outputs and unsupported nonlinear/bitwise cases.
- [ ] Move the existing proven affine analysis behind the SDK API without
  guessing ranges for unsupported forms; retain source/candidate distinction.
- [ ] Delegate Sandbox range decisions to this API and verify existing Javadoc
  and behavior fixtures, then commit this independently reviewable slice.

## Task 4: Connected Java computations and integration

Files: JavaComputationExtractor and necessary region/emitter support, adapter
tests and documentation, pinned SDK artifact/provenance files.

Consumes: the reviewed SDK from tasks 1-3 and existing helper support from #1694.

- [ ] Establish an explicit array-read admission contract from current region and
  trace APIs; add real Java tests for same/different arrays, repeated accesses,
  signed words/carries, null/bounds exceptions and writes splitting regions.
- [ ] Implement the represented source dependency extension and preserve every
  unsupported memory/control-flow boundary with a diagnostic.
- [ ] Package the exact SDK sources reproducibly, update Sandbox provenance,
  compile/execute the whole mathematics test suite and repository policy gate.
- [ ] Run the unchanged full corpus in strict and mathematical modes, record
  source/dependency hashes, compare accepted edits and classify remaining gaps.
- [ ] Independently review each final PR slice, publish and inspect required CI.
