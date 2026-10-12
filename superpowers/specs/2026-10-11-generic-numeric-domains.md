# Generic numeric computation domains

## User-approved outcome

Sandbox translates original Java computations into the existing Regelsuche graph.
Regelsuche discovers and independently verifies cheaper computations. Numeric Java
types share this graph; arithmetic semantics and established value facts remain
explicit. New mathematical rules belong to Regelsuche and become available to
Sandbox through its pinned SDK update.

The user approved the architecture and the following implementation on 2026-10-11.
Continue implementation without another design or execution-method approval.

## Changes

1. Discharge conditional core algebra rules against established SDK facts and
   structurally identified typed subexpressions. Never turn proposed conditions
   into assumed facts. Independently prove the resulting Java computation.
2. Translate bounded constant BigInteger left shifts into the modular theory,
   retaining the primitive int distance and exact integer semantics. Support
   mixed graphs without dropping unrelated outputs. Keep original traces and
   all supported-range, modulus, exception and budget checks authoritative.
3. Expose the existing primitive mathematical-domain analysis through the SDK,
   with exact interval results and diagnostics, and make Sandbox consume it for
   range-extension decisions and Javadoc instead of owning mathematical algebra.
4. Extend the generic Java adapter for additional connected computations,
   beginning with explicit array-read dependencies and word/carry expressions.
   No library-name or target-formula recognizers. Preserve evaluation count,
   evaluation order, aliasing boundaries, exceptions and observable writes.
   General loop transformation is a subsequent capability; computations inside
   loop bodies remain eligible where their dependencies can be represented.

## Constraints

- Java 25, existing Maven/JUnit authorities and existing search framework.
- Source and candidate traces, evidence hashes, cancellation and work charging
  remain authoritative. No sample-based proof, guessed assumptions or increased
  benchmark budget to make counts improve.
- Safe unknown results retain the original code and a concrete diagnostic.
- BigInteger value semantics remain an explicit established/guarded contract.
- Keep primitive wrap, checked arithmetic and mathematical range extension
  distinguishable; an interval alone never determines the arithmetic law.
- Reuse the current Bouncy Castle 1.85.2 corpus and scanner: all numeric kinds,
  1,000,000 work units per file, 20,000 states, LOWER_ESTIMATED_RUNTIME, Java 17
  source compliance; run strict and mathematical modes separately.
- Report analyzed coverage, accepted changes, costs and actual runtime evidence
  separately. No claim of speedup from static costs or scan duration.
- Sandbox changes should remain independently reviewable slices near 1,500
  changed text lines. Do not add Python or test authorities to the repository.

## Verification

Each semantic extension has positive and negative Maven/JUnit regressions which
fail on the unchanged baseline for the intended reason. Exercise cancellation,
out-of-domain values, false candidates and mixed-width/primitive-distance cases.
Run the SDK module suite and relevant downstream tests; compile and execute real
original/generated Java for adapter changes. Retain exact source/dependency hashes
for the corpus comparison and independently review final changes before shipping.

## Integration

Regelsuche baseline: d7ec8b51d164dc58dbb3e9e328af8bb356425b93.
Sandbox baseline for helper support: PR #1694,
4779cd091614d771443a21168aec4c1eaddeced9; main is aca02bfb0509653ce5b5c9dedf7685e3f5698eb7.
Changes use isolated branches. Publish coherent PRs and inspect required checks.
