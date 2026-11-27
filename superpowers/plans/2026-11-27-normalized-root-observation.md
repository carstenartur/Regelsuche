# Normalized Root Observation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Independently verify exact normalized observations of source-bound quadratic-root programs whose raw outputs may differ by joint sign.

**Architecture:** Reuse the existing root proof and add a finite parity-selector certificate in the mathematical core. The SDK only translates budgets and cancellation. Sandbox remains responsible for deriving the actual normalizer and its observable-use contract from Java source.

**Tech Stack:** Java 25, Maven, JUnit; existing exact arithmetic and certificate budgets.

**Spec:** `docs/superpowers/specs/2026-11-27-normalized-root-observation-design.md`

## Global Constraints

- General mathematical algorithms belong in Regelsuche core; SDK is a thin adapter.
- No Bouncy Castle class/prime recognizers or supplied optimized fixture bodies.
- Existing EXACT_PAIR and UP_TO_JOINT_SIGN raw API behavior stays unchanged.
- Java 25 `-Xlint:all -Werror` applies to changed production and test files.
- Share work, cells, time and cancellation across nested proof replay; no certificate release after exhaustion.
- Source identities, modulus, selector and original program are checked against independently supplied expectations.
- Maven/JUnit is the executable test authority. Update strategy-index links after source edits.

## Review Focus

- A zero or axis coordinate must not invalidate a valid selector or admit an invalid one.
- Source/modulus/selector substitution must be rejected even when each separate artifact looks valid.
- Mutable or subclassed numeric objects must not execute untrusted arithmetic during verification.
- A late cancellation or one-unit-short budget must not publish a valid result.
- A normalized proof must never silently weaken an exact raw observation or claim Java context facts.

### Task 1: Source-bound normalized quadratic-root certificates

**Files:**
- Create: `regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/modular/QuadraticRootNormalization.java`
- Create: `regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/modular/QuadraticRootNormalizationTest.java`
- Modify: `regelsuche-optimization-sdk/src/main/java/de/regelsuche/sdk/optimization/power/QuadraticRootSdk.java`
- Create: `regelsuche-optimization-sdk/src/test/java/de/regelsuche/sdk/optimization/power/QuadraticRootNormalizationSdkTest.java`
- Modify: affected Maven strict-source execution includes, `docs/research/strategies.md`, `docs/research/quadratic-root-programs.md`, SDK README and capability entry where applicable.

**Interfaces:**
- Consumes `QuadraticRootContract`, `QuadraticRootSearch.Certificate`, existing independent field/program verification and `CertificateWork`.
- Produces `QuadraticRootNormalization.ParitySelector(int mask)` with `realFirst()` and `imaginaryFirst()` factories.
- Produces `QuadraticRootNormalization.Contract(String sourceIdentity, BigInteger modulus, ParitySelector selector)`.
- Produces `QuadraticRootNormalization.Certificate(QuadraticRootSearch.Certificate root, Contract normalizer)`.
- Produces `public static QuadraticRootVerifier.Result verifyNormalized(QuadraticRootContract expectedRoot, Contract expectedNormalizer, Certificate supplied, IterationLimits limits, BooleanSupplier cancellation)`.
- SDK `QuadraticRootSdk.verifyNormalized` takes the same first three parameters plus `OptimizationBudget` and `CancellationToken`.

- [ ] Write independent tests first: enumerate all 512 selectors modulo 3 and assert the 32 valid selectors; concrete normalized old/new roots over p=3,7,11,19 and both target bits; null/axis and either coordinate priority.
- [ ] Observe the intended failing test for the missing API/behavior before implementation; record the command and failure.
- [ ] Implement bounded shape/source checks, authenticate true BigInteger modulus values before their methods, check both complete expected contracts and shared modulus, replay the root proof, validate the eight nonzero selector cells, and perform a final cancellation check.
- [ ] Add tamper, malicious BigInteger subclass, source/modulus mismatch and exact budget/cancellation cases. Demonstrate raw EXACT_PAIR still rejects a jointly negated-only certificate.
- [ ] Add the SDK consumer test exercising a real proof and same outcome/work count as the core.
- [ ] Run focused tests and strict changed-source/test compilation, then affected core/SDK reactor verification. Preserve existing optional skips explicitly.
- [ ] Update documentation with hypotheses, implementation, finite findings and limits; regenerate strategy-index anchors by the repository's documented method.
- [ ] Self-review and commit. Report exact commit, commands/results, test counts, changed files and any remaining limitations to the controller for independent review.
