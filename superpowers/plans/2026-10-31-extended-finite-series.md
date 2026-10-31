# Extended finite-series implementation plan

**Goal:** Implement the user-approved broad finite-series support in Regelsuche and Sandbox, preserving strict Java semantics and replayable independent evidence.

**Spec:** `docs/superpowers/specs/2026-10-31-extended-finite-series.md`

**Architecture:** Keep the existing constant-range scalar fast path. Add compact symbolic-count polynomial and transition plans with bounded independent checkers. Java lowering consumes these plans, using exact arithmetic when a primitive closed form would overflow. Additional series providers share bounded polynomial foundations; Java adapters accept only source fragments they can re-admit and replay.

**Technology:** Java25, Maven/JUnit, Regelsuche scalar Expr/SymbolId and exact rationals, Eclipse JDT AST, existing Sandbox source-rewrite/evidence infrastructure.

## Global Constraints

- Regelsuche owns mathematics; Sandbox owns Java semantics and emission.
- Search is untrusted. Independent verification checks certificates and source identity without invoking search.
- Preserve exact/int/long semantics, empty-range behavior, evaluation order, bindings, comments and termination. Reject unsupported floating point/BigDecimal reassociation.
- Bound work, degree, terms, state dimension, expression size, coefficient bits and cancellation. Do not unroll a large source iteration count.
- Use Java25 and Maven/JUnit. No new Python automation or parallel semantic test framework.
- Work in isolated branches; no merge, release, stacked PR or edits to the recovered source workspace.
- Existing constant-range API and consumers remain compatible. Tests must exercise public behavior and compiled transformed Java, including negative and tampering cases.
- Keep each task's files separate when working concurrently; shared-file changes require explicit coordination.

## Task 1: Symbolic polynomial sums and composable finite families

**Workspace:** `/workspace/scratch/0cab49039492/regelsuche`.

**Own:** New polynomial/periodic/piecewise/nested/rational iteration APIs and tests under the SDK iteration package; bounded enhancements to `IterationPolynomial`. Do not change the old finite optimizer/verifier API or transition files owned by Task2. Do not edit Sandbox.

Implement a polynomial-sum model with symbolic start, constant nonzero stride, scoped index/free parameters, incoming initial expression and explicit arithmetic. Count is a nonnegative runtime BigInteger supplied to a verified compact plan. A Newton/binomial basis is recommended: sum a degree-d term using at most d+1 differences times binomial(count,j+1). Expose immutable plan coefficients and initial expression so Sandbox can emit exact BigInteger arithmetic without a runtime SDK dependency. Provide bounded evaluation with a parameter map.

Provide an independent certificate checker: reconstruct the index polynomial from the coefficients in the binomial basis, check the source/initial/scopes/arithmetic and integrality, and establish the sum for every nonnegative count. It must not call synthesis. Support integer-valued rational intermediate polynomials for composition only when integrality is proven, not by accepting arbitrary fractions into a modular ring. Separate index-degree from bounded coefficient-parameter complexity where practical.

Compose nested polynomial sums (at least rectangular and triangular bounds), periodic residue classes and piecewise polynomial ranges. Provide exact-rational telescoping for a documented rational-function fragment, with independent cross-multiplication identity and nonzero-denominator checks. Expose honest capability/limit documentation; never route Java integer division into rational semantics.

Test first: polynomial powers0..8, parameter products, shifts/strides, zero/negative supplied count, n=50000, huge counts, nested triangular sum, alternating periodic terms, interval partition, rational 1/(i*(i+1)), poles, coefficient/source/result tampering, unbound symbols, limits and cancellation. Compare bounded small cases with direct exact evaluation; large cases require compact work. Run scoped SDK reactor tests with `/workspace/scratch/0cab49039492/build-tools/mvn -pl regelsuche-optimization-sdk -am test -Dtest='FiniteIteration*Test,*Polynomial*Sum*Test,*Series*Test,*Telescop*Test' -Dsurefire.failIfNoSpecifiedTests=false`. Report actual commands and totals.

Before substantial implementation, write a concise public API contract to `.superpowers/sdd/extended-finite-series-polynomial-api.md` and send its path to the controller. Implement tests first and record red/green evidence. Commit only owned files and write the task report. Keep source under the provided workspace. Do not dispatch subagents.

## Task 2: Linear recurrences, geometric sums and products

**Workspace:** `/workspace/scratch/0cab49039492/regelsuche-linear` (isolated worktree).

**Own:** New `LinearIteration*`, `LinearTransition*`/`LinearRecurrence*` files and corresponding tests in SDK iteration package; no edits to `IterationPolynomial`, existing iteration APIs or Task1 files. Use a separate small bounded polynomial helper if necessary, or coordinate a shared helper explicitly.

Model up to eight scoped state variables with initial expressions, sequential or simultaneous polynomial updates that are affine in state, invariant parameter coefficients, exact/int/long arithmetic and a runtime nonnegative count. Normalize ordered updates to one transition; augment with a constant coordinate and, where needed, a bounded polynomial index-state basis. Expose immutable transition matrices/initial expressions so Java can lower a verified transition without a runtime SDK dependency. A smaller documented dimension is acceptable only with a recorded rationale.

Create candidates for division-free logarithmic powering. Independently check that the transition represents the original updates, and check any supplied powering certificate/result; never invoke candidate search or trust informational costs. Evaluation uses bounded repeated squaring and normalizes modular values at every multiplication; exact growth must reject before exceeding limits. Preserve all outputs and update order.

Cover scalar affine updates s=a*s+b, geometric accumulation, constant-factor products, coupled two-state recurrences (e.g. Fibonacci) and polynomial forcing by state augmentation. Tests must include zero count, a=0/1/-1, modular overflow, huge counts, ordered-update distinctions, malicious matrix/result/source changes, invalid scope/dimension/nonlinearity, work limits and cancellation. Run scoped Maven SDK reactor tests with the shared build-tools/mvn wrapper in this worktree. Commit owned files and report red/green commands/totals.

Before substantial implementation, write the public API contract to `.superpowers/sdd/extended-finite-series-linear-api.md` and send its path to the controller. Do not dispatch subagents.

## Task 3: Broaden the existing Java constant-range adapter

**Workspace:** `/workspace/scratch/0cab49039492/sandbox`.

**Own:** Existing `JavaFiniteSumAnalysis`, `JavaFiniteSumOptimizer`, `FiniteSumEvidence`, `JavaFiniteSumTest`, new closely related tests, and the finite-sum/invariant pass ordering in `MathematicalAnalysis` (only that change). Coordinate before other shared evidence/report edits. No SDK changes; use the currently bundled API.

Fix competition between loop-invariant hoisting and complete finite reduction so a profitable complete reduction is not blocked by an overlapping hoist. Add constant nonunit strides (+=, -=, equivalent assignments), int and long counters where no wrap is proven, immutable local temporaries via binding-aware substitution, ordered multiple accumulator updates where independently decomposable, comment retention, suitable canonical while/do loops with correct final live counter value, and sequential constant-range IntStream/LongStream sum forms when semantics match. Implement as shared admission components where useful, not unrelated formula catalogs.

Do not weaken the same-width ring gate or accept hidden field/call/array effects. Counter final updates must stay representable. Comment text must survive. Zero-trip loops must not introduce reads. Preserve independent numerical/source replay and combined edits. Runtime symbolic bounds, general linear updates and BigInteger integration are Task4; avoid conflicts by keeping their new adapter files separate.

Write failing JUnit regressions before implementation and run the standalone production mathematics suite using `/workspace/scratch/0cab49039492/build-tools/mvn -f qa/math-optimization/pom.xml -Dtest='JavaFiniteSum*Test,JavaLoopInvariant*Test,MathReviewReportTest' test`. Test compiled original/transformed sources over boundary inputs, source/evidence tampering, selection and cancellation. Run the full standalone suite before reporting. Commit owned changes and report any uncovered safe cases without silently dropping scope. Do not dispatch subagents.

## Task 4: Connect symbolic/linear plans to Java and qualify the SDK

**Workspace:** both primary repositories, after Tasks1-3 interface agreement.

Add runtime-bound Java finite-reduction admission and emission using Task1/2 public plans; support int/long counters, nonunit strides, nested polynomial reductions, independent/coupled state outputs, exact BigInteger accumulators and safe sequential range streams. Keep cost estimates honest: BigInteger allocation and logarithmic powering are not free; use profitable constant cases or explicit runtime thresholds with original-loop fallback. Preserve comments and original behavior for empty or unprovable/nonterminating ranges using guards. Add exact binding checks for JDK BigInteger calls; do not assume arbitrary subclass methods are pure.

Integrate source-bound evidence, reports and combined-edit replay. Re-decode actual emitted operations or compare a fully bound, deterministically reconstructed checked template plus untouched declaration bindings; source hashes alone do not prove equivalence. Confirm all source/helper variable identities and generated types. Regression-test n=50000, negative n, runtime start/end, extrema, nested triangular, BigInteger, geometric/affine/products, multi-state ordering, mixed-width safety, fallback cases, malicious edits and budget/cancellation.

Merge the independent linear worktree commits locally into the primary feature branch. Build and qualify the SDK from an exact source revision, update bundled jar and truthful provenance, run a fresh consumer, and run full scoped SDK and Sandbox mathematics suites. Use the existing Maven distribution contract for reproducibility where the environment supports it; report exact infrastructure blockers without mislabeling stale qualification as fresh.

## Task 5: Review, capability documentation and durable delivery

Task reviews check both spec compliance and code quality. Resolve material findings, then perform one whole-branch review focused on cross-provider dispatch, modular integrality, runtime termination/empty cases, binding replay and budget accounting. Record explicit supported fragments and rejection reasons in both repositories, with executable example/test references.

Commit and persist coherent feature branches, update/create ordinary draft PRs against main with substantive repository-size exceptions if needed, and inspect exact-head CI when available. Keep broad integration PRs draft/reference; no merge or release. Final German report must distinguish implemented and tested families, remaining semantic exclusions, local verification versus remote CI, and link reviewable changes.
