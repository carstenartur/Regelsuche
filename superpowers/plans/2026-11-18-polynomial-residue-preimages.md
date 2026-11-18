# Polynomial residue preimages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** Solve and verify bounded integer-polynomial congruences in the core with thin SDK access.

**Architecture:** Normalize BigInteger coefficients to an int residue ring. Enumerate a finite complete preimage with bounded exact Horner arithmetic. Consumers assign mathematical meaning to these residue sets.

**Tech Stack:** Java 25, Maven, JUnit 6.

**Spec:** docs/superpowers/specs/2026-11-18-polynomial-residue-preimages-design.md

## Global Constraints

- All arithmetic belongs in regelsuche-math-algorithms; SDK only adapts OptimizationBudget and CancellationToken.
- Positive int modulus, signed BigInteger coefficients and target, ascending coefficient order.
- COMPLETE is a complete sorted set; UNAVAILABLE has no partial Solution.
- Apply IterationLimits work, state, time and MAX_RUNTIME_BITS limits.
- Preserve existing public APIs and typed callers.

## Review Focus

- Repeated roots and composite rings cannot be delegated to a square-free prime-only operation.
- Empty root set must differ from unavailable.
- long Horner arithmetic must remain correct near Integer.MAX_VALUE.
- Omitted, duplicated, out-of-range roots and changed sources must fail verification.
- Cancellation or a tight budget must not expose partial enumerations.

## Task 1: Core and thin SDK

**Files:** Create modular/PolynomialCongruence.java and its JUnit test in the math
module; create sdk/optimization/modular/PolynomialCongruenceSdk.java and a
consumer-level SDK test. Update docs/research/strategies.md,
docs/research/polynomial-congruences.md and the SDK README.

**Interfaces:**
- Request(List<BigInteger> coefficientsAscending, int modulus, BigInteger residue).
- Solution(Request source, List<Integer> residues).
- Status { COMPLETE, UNAVAILABLE }.
- Result(Status status, Optional<Solution> solution, String reason, long work).
- Verification(boolean valid, String reason, long work).
- static solve(Request, IterationLimits, BooleanSupplier) -> Result.
- static verify(Request, Solution, IterationLimits, BooleanSupplier) -> Verification.
- SDK solve/verify replace the last two core arguments by OptimizationBudget and CancellationToken.

- [x] Write independent expected-root tests first: x²=1 mod8 -> [1,3,5,7],
  (x-1)²=0 mod5 -> [1], x²=-1 mod3 -> [], zero polynomial mod1 -> [0].
  Compare seeded small requests against BigInteger explicit sums of powers.
  Add state/work/cancellation and forged-solution controls.
- [x] Observe the missing-feature failure, then implement the bounded core and SDK.
- [x] Run core and SDK module tests with upstream dependencies, with compiler warnings visible.
  Use Maven's test selector and failIfNoSpecifiedTests=false for dependency modules.
- [x] Update strategy hypothesis, implementation, result and limitation with maintained code links.
- [x] Commit and report exact commands, test counts, hashes and remaining concerns.

Validation command: mvn -B -ntp -pl regelsuche-math-algorithms,regelsuche-optimization-sdk -am test
Expected: selected reactor tests pass; final whole repository gates run in PR CI.
