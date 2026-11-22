# Mathematical Certificate Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the six approved reusable mathematical capabilities, independent verification, typed SDK access and reproducible documentation.

**Architecture:** Existing exact Java types and a shared bounded-work helper support independent algorithm packages. Search proposes; a source-bound verifier checks. Consumer Java semantics remain at the existing SDK/consumer boundary.

**Tech Stack:** Java 25, existing Maven/Gradle multi-module project, JUnit, ExactRational and existing polynomial types; no new runtime dependency.

**Spec:** `docs/superpowers/specs/2026-11-22-mathematical-certificate-expansion.md`

## Global Constraints

- Java 25; mathematical implementations in `regelsuche-math-algorithms`; SDK delegates.
- Exact arithmetic and immutable typed inputs/certificates; no string-encoded internal programs.
- Source/assumption binding, cancellation, thread interruption, time, work, shape and 65,536-bit arithmetic limits; a resource stop gives no mathematical claim.
- Preserve existing APIs, independent verifiers and quality gates. Compile new code/tests with `-Xlint:all -Werror`.
- No external theorem novelty, optimality, universal Greedy deadline or unqualified Java refactoring claim.
- Core, SDK tests, executable examples and German research documentation accompany every feature.

## Review Focus

- Cancelled/oversized inputs cannot allocate an unbounded matrix/table before checking limits.
- A valid certificate for a different source or domain is rejected.
- Singular matrices, zero terms, repeated variables and empty constraint products have correct exact semantics.
- Search misses and exhausted budgets never become impossibility or zero-count claims.
- Verification checks the full coefficient/domain/branch/edge coverage without trusting the candidate generator.

### Task 0: Shared exact-work accounting

**Files:** create `regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/certificates/CertificateWork.java` and corresponding test.

**Interfaces:** `CertificateWork(IterationLimits, BooleanSupplier)`; `charge(long)`, `cells(long)`, `check(ExactRational)`, `check(BigInteger)`, `add/sub/mul/div(ExactRational,ExactRational)`, `used()`, `remainingLimits()`, and typed `Stop` with a reason string. Reject negative charges and sizes. `remainingLimits` never fabricates extra work; zero remaining stops.

- [ ] Write and run tests for exact-bound exhaustion, pre-cancel, negative charge, cell/bit bounds.
- [ ] Implement the small reusable helper; run focused tests and strict compiler checks.
- [ ] Commit helper and specification.

### Task 1: Ungleichungs- und Potentialsynthese

**Files:** create focused `positivity/SosSynthesis.java`, `positivity/SosVerifier.java`, needed typed support, `positivity/PolynomialPotential.java`; SDK `positivity/SosSynthesisSdk.java`; matching core/SDK tests; `docs/research/sos-synthesis.md`.

**Interfaces:** public typed Request/Certificate/Result records; `search(request, IterationLimits, BooleanSupplier)` and `verify(request, certificate, IterationLimits, BooleanSupplier)`. Reuse existing polynomial types and Gram matrix checker. Multivariate polynomials are supported within explicit basis and resource limits. Potential request supplies transition and invariant-domain assumptions; certificates contain both nonnegativity and decrease identities.

- [ ] Test an automatically found nontrivial multivariate SOS, a premise-dependent inequality, a potential decrease, and an unprovable bounded request; observe preimplementation failure.
- [ ] Implement deterministic bounded candidate synthesis and a separately coded exact coefficient/PSD verifier.
- [ ] Test source/assumption tampering, singular witnesses, incomplete bases, cancellation and budget exhaustion; SDK delegates without implementing mathematics.
- [ ] Document algorithm/search limits, source families 119/266, executable example and actual independent-reference results; commit.

### Task 2: General bounded bilinear program search

**Files:** create `algebra/BilinearProgramSearch.java`, `algebra/BilinearProgramVerifier.java`, focused support; SDK `algebra/BilinearProgramSdk.java`; matching tests; `docs/research/bilinear-program-search.md`.

**Interfaces:** rational input tensor and finite coefficient/form dictionary policy; U/V/W certificate; public `search` and `verify` with the shared budgets; typed cost summary and exact program evaluation. Joint outputs are solved against selected rank-one products, not through named-case templates.

- [ ] Test search discovering a three-product two-term convolution and a distinct multi-output form, a miss under two products, and exact evaluation; observe failure.
- [ ] Implement bounded rank-one candidate enumeration/subset search with exact output linear solving and independent coefficient-tensor verification.
- [ ] Test nontrivial rational weights, source/tensor/output tampering, shape/resource limits and SDK access; record costs including additions/constants/temporaries.
- [ ] Add runnable examples, source 107, comparisons and precise non-optimality scope; commit.

### Task 3: Domain-preserving rational noncommutative realization

**Files:** create focused `noncommutative/RationalFormula.java`, `noncommutative/RationalFormulaRealization.java`, `noncommutative/RationalFormulaVerifier.java` plus support; SDK `noncommutative/RationalFormulaSdk.java`; tests; `docs/research/rational-formula-realization.md`.

**Interfaces:** immutable rational formula tree, affine pencils and selectors, occurrence-bound inverse obligations; public `realize`, independent `verify`, and exact `evaluate` under rational matrix assignments with explicit defined/undefined/resource statuses.

- [ ] Test nested inverse plus identity rejecting singular input, `0*inverse(X)` preserving its domain, and ordered noncommuting products; observe failure.
- [ ] Implement the family-116 block recursions and every original inverse occurrence; keep division-free API unchanged.
- [ ] Independently validate all blocks/selectors/occurrence coverage and compare concrete matrix evaluation to a direct recursive interpreter; test tampering and budgets.
- [ ] Add SDK delegation, runnable example, pinned source and clear distinction from a universal identity decider; commit.

### Task 4: Dynamic evaluation over square-free quotient algebras

**Files:** create focused `polynomial/DynamicAlgebraEvaluation.java`, `polynomial/DynamicAlgebraVerifier.java` and support; SDK `polynomial/DynamicAlgebraSdk.java`; tests; `docs/research/dynamic-algebra-evaluation.md`.

**Interfaces:** prime-field polynomial modulus and immutable typed expression, full terminal branches with modulus/definedness/value, independently checked full-coverage certificate, public evaluate/verify with budgets.

- [ ] Test inverse of T-1 modulo T^2-1 over F5 yields one undefined and one defined branch; nested expressions carry both branches and compare all explicit roots; observe failure.
- [ ] Generalize existing exact gcd/Bézout/remainder operations into a source-bound branching evaluator, with square-free and domain validation.
- [ ] Verify factor product and pairwise coprimality, values and inverse identities independently; reject dropped/duplicated branches, changed source and false inverses.
- [ ] Add SDK, runnable example, source142 lemma and scope; commit.

### Task 5: Exact local-constraint counting

**Files:** create `combinatorics/FiniteConstraintCounter.java`, `combinatorics/FiniteConstraintVerifier.java`, support; SDK `combinatorics/FiniteConstraintSdk.java`; tests; `docs/research/finite-constraint-counting.md`.

**Interfaces:** finite Boolean variable count and ordered local factors with nonnegative BigInteger tables; conditioning; exact elimination trace, total weight and optional witness; public count/verify with budgets.

- [ ] Test the two exclusions (a,b) and (b,c) give 5 models; conditioned b=1 gives1, b=0 gives4; compare all small random inputs against independent exhaustive enumeration; observe failure.
- [ ] Implement variable elimination including free variables, constants and disconnected components; reconstruct a witness by source-bound conditional counts.
- [ ] Independently replay traces and reject changed factors, missing eliminations and false totals; test width, cancellation and zero/positive distinctions.
- [ ] Add SDK, example, generating-count distribution where feasible, source378 and scope; commit.

### Task 6: Cyclic energy certificates

**Files:** create `combinatorics/EnergyGame.java`, `combinatorics/EnergyGameVerifier.java`, support; SDK `combinatorics/EnergyGameSdk.java`; tests; `docs/research/energy-game-certificates.md`.

**Interfaces:** graph with Min/Max ownership, labeled directed integer edges, start vertex; bounded credit synthesis and immutable strategy/certificate; public solve/verify with budgets.

- [ ] Test the two-state game a->b=-2, a->a=-1, b->a=3, b->b=0 yields credits(2,0), Max selects a->b; test losing selfloop; observe failure.
- [ ] Implement bounded monotone credit lifting with saturation, preserve exact winning-region/strategy source binding and explicit no-claim statuses on exhaustion.
- [ ] Verify every selected Max and every Min edge locally and require closure of the certified region; test parallel edges, omitted adverse edges, cycles and BigInteger weights.
- [ ] Add SDK, example, source104, independent small-game reference comparisons and exact guarantee; commit.

### Task 7: Documentation, integration and review

**Files:** update `docs/research/strategies.md`, `docs/research/openai-math-transfer-audit.md`, `docs/mathematical-capabilities.md`, SDK README and relevant demo/entry pages. Add one executable Java example per capability beside the existing SDK examples.

- [ ] Integrate reviewed task commits, resolve interface/build conflicts without weakening checks.
- [ ] Run focused core/SDK tests and affected regression suites, strict warning compile, documentation link checks and applicable repository quality gates. Report any unavailable canonical build separately from independently run tests.
- [ ] Obtain independent task/final code review; fix correctness and resource issues with regression tests.
- [ ] Publish small coherent PR groups, verify exact-head CI and integrate under the standing user authorization where gates permit. Preserve honest source/release/consumer availability distinctions.
