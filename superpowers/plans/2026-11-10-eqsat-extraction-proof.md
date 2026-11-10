# EqSat Extraction Proof Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Return independently checked exact input/output equality certificates for supported native EqSat rules, with explicit bounded failure and an opt-in proof-required search mode.

**Architecture:** A fresh graph records finite class anchors and every actual native ground rule instance. A separate bounded ground congruence producer constructs a topological equality DAG. A detached small checker validates exact endpoints and each inference against receiver-supplied rule definitions, without graph access.

**Tech Stack:** Existing Java 25, core exact AST/rationals/patterns, JUnit 6, existing Maven/Gradle canonical qualification. No new production dependency or module.

**Spec:** `docs/superpowers/specs/2026-11-10-eqsat-extraction-proof-design.md`

## Global Constraints

- Base is accepted main `d191fc8abb998c14ea7bc5965401eaf696e8fc2a`, whose full canonical CI `42825000269` is successful. Do not repeat that full baseline run.
- Exact native base `PatternRewriteRule` only: Placeholder, LiteralNumber, Operation, Function; reject subclasses, callbacks, LiteralVariable, AC/other profiles, non-preserving rules, duplicate IDs and unbound target placeholders before execution.
- Existing default saturation/search/assumption/matcher-limit behavior remains unchanged. New proof-required behavior is explicit opt-in.
- Certificate source/target endpoints use exact rational and lossless scoped-variable identity. No display-text reparse, label hash, live class ID or arbitrary raw union is a proof axiom.
- ProofLimits defaults are `50_000/100_000/500_000/1_000_000/4096/256/100_000/5_000_000L`, in the spec's field order; all fields are nonnegative. Keep the core4096-character rational envelope.
- Bound expanded occurrence count, unique terms, depth, references, characters, scalar bits and owned work before the corresponding recursive traversal/allocation. Capture/build and checker budgets are separate.
- Every new semantic native union is captured, independently of the256 local-witness cap. Rebuild equalities must be rederived by congruence, never trusted as axioms.
- Proof quota loss is terminal for that run's proof availability and must not interrupt pending graph repair. Unavailable proof returns the exact original input.
- Native matcher combinatorics and arbitrary cost callbacks are outside the owned proof-phase bounds; no whole-call bound or speedup claim.
- Keep existing quality thresholds, evidence contracts and canonical CI gates. Do not run or retry full CI until a reviewed coherent source head is ready.
- All implementation/docs stay in this Git-backed worktree. Temporary orchestration/evidence may use scratch; Python may orchestrate Java but performs no mathematics.

## Review Focus

1. A compact shared AST/pattern DAG denotes exponentially many expanded occurrences; reject it before recursive insertion/formatting or unsafe materialization. Tests belong to Tasks1 and2.
2. Repeated placeholders use different equivalent live child origins, and the concrete substituted LHS never existed as one original tree. Capture one binding term per placeholder and prove the congruence bridge. Tests belong to Tasks1 and2.
3. A proof quota expires between RHS insertion and a union or during pending repair; no incomplete premise set may later regain COMPLETE, and the graph must remain consistent. Tests belong to Task2.
4. A same-ID receiving rule, scoped symbol, rational, ordered child premise or transitive endpoint is substituted. The independent checker must reject the exact mismatch. Tests belong to Task1.
5. Existing default/custom-rule search unexpectedly loses results because proof mode is introduced. Preserve legacy constructors and callbacks; explicit proof-required mode alone fails closed. Tests belong to Task3.

## Ownership and sequencing

Task1 owns new `de.regelsuche.egraph.proof` production classes and their tests. Task2 owns existing `EGraph`, `EqualitySaturation`, `SaturationProvenance`, the package-private capture adapter and its integration tests. Task3 owns search strategy adoption and public documentation. Freeze the spec interfaces before parallel work; do not edit another owner's files without coordination. Task1 and Task2 may work concurrently against those interfaces, but final integration tests require both. Review the complete package before publication.

### Task1: Detached certificate kernel and ground congruence producer

**Files:**
- Create: `regelsuche-egraph/src/main/java/de/regelsuche/egraph/proof/ExtractionCertificate.java`
- Create: `regelsuche-egraph/src/main/java/de/regelsuche/egraph/proof/ProofLimits.java`
- Create: `regelsuche-egraph/src/main/java/de/regelsuche/egraph/proof/ExtractionProofChecker.java`
- Create: `regelsuche-egraph/src/main/java/de/regelsuche/egraph/proof/ExtractionProofBuilder.java`
- Create: `regelsuche-egraph/src/main/java/de/regelsuche/egraph/proof/ProofOutcome.java`
- Internal helper files within that same package are allowed when they separate term validation/metering, congruence production and checking; record them in the implementation report.
- Test: `regelsuche-egraph/src/test/java/de/regelsuche/egraph/proof/ExtractionProofCheckerTest.java`
- Test: `regelsuche-egraph/src/test/java/de/regelsuche/egraph/proof/ExtractionProofBuilderTest.java`

**Interfaces:**
- Consumes: core `Expr`, `PatternExpr`, exact `PatternRewriteRule` data and `ExactRational`; no EGraph or search dependency.
- Produces: all exact public signatures and nested types under “Frozen public kernel interfaces” in the spec, including private-constructor checker acceptance tokens. Root integration consumes `addTerm`, `addExpression`, `materialize`, `prepareInstance`, `recordInstance`, `build` and `workUsed` exactly as specified.

- [x] **Step1: Write the missing-kernel tests and retain the actual initial failure.** `detachedCheckerAcceptsEveryInferenceKind` constructs one valid certificate exercising Reflexivity/Symmetry/Transitivity/Congruence/PatternInstance and requires ACCEPTED. `groundClosureProvesRepeatedBindingBridge` uses captured `a=b` plus `g(A,A)->h(A)` and checks `g(a,b)=h(a)` without a graph. Missing API compilation is a valid initial RED, but do not misdescribe it as a runtime regression.
- [x] **Step2: Implement immutable protocol and bounded term/rule inspection.** Use the spec's payload tags and backward indexes. Before core helpers can recurse or allocate large strings, validate source/rule depth, expanded counts, scalar bits and character bounds. Reject opaque BigInteger subclasses in source and pattern rationals before invoking scalar methods. Reject duplicate IDs/unsupported grammar/target-only placeholders explicitly. Preserve full source/target identities; no hash-only trust.
- [x] **Step3: Implement independent checking.** Compute step endpoints from prior checked nodes, compare exact expected source/output, and substitute explicit bindings independently of matching. Reject certificate mutation, forward/cyclic references, wrong congruence order/arity/payload, wrong transitive middle, same-ID rule substitution, wrong rational/scoped symbol, extra/missing bindings and malformed rule/term data. Only ACCEPTED constructs `CheckedCertificate`.
- [x] **Step4: Implement bounded congruence reconstruction.** Ground instances are the only axioms. Build a separate signature table and proof-bearing union forest over immutable term IDs, emitting only backward-reference proof nodes. Saturate congruences until stable or quota exhaustion, then explain the exact input/output. A metered full-scan fixed point is acceptable; do not inherit asymptotic claims from the cited paper. `NO_DERIVATION` is distinct from a resource limit.
- [x] **Step5: Add adversarial bounds and ownership tests.** `sharedDagExpansionIsRejectedBeforeMaterialization`, `rulePreflightPrecedesRecursivePatternHelpers`, `sameLabelDifferentScopeRejects`, `oneShortWorkBudgetIsLimitExceeded`, `exactWorkBudgetAccepts`, `oversizedCharacterReferenceAndRationalInputsReject`, `opaqueScalarIsRejectedBeforeCallbacks`, `moreThan256PremisesRemainAuthoritative`, and `completeOutcomeRequiresCheckerToken` pin the respective spec contracts. Constructor copies must remain immutable after caller-list/map mutation.
- [x] **Step6: Run focused current-source Java25/JUnit6 tests and inspect every failure.** Prefer `./mvnw -pl regelsuche-egraph -am -Dtest=ExtractionProofCheckerTest,ExtractionProofBuilderTest -Dsurefire.failIfNoSpecifiedTests=false test` when dependencies resolve. If the already known missing local Netty BOM blocks before compilation, do not retry it: use the existing controlled current-source Java25/JUnit6 qualification pattern, with all project classes removed from external dependency JARs. Retain exact commands, input tree/source hashes, compile output and test counts; never call that fallback a Maven run.
- [x] **Step7: Independently review this kernel, resolve concrete findings and commit the coherent implementation/tests.** Review especially bounds before traversal and that no checker call reaches graph algorithms, live class IDs or rule callbacks. Stop optional test expansion once the listed risks and existing relevant contracts are covered.

### Task2: Fresh EqSat capture and bounded extraction

**Files:**
- Modify: `regelsuche-egraph/src/main/java/de/regelsuche/egraph/EGraph.java`
- Modify: `regelsuche-egraph/src/main/java/de/regelsuche/egraph/EqualitySaturation.java`
- Modify: `regelsuche-egraph/src/main/java/de/regelsuche/egraph/SaturationProvenance.java`
- Create: `regelsuche-egraph/src/main/java/de/regelsuche/egraph/EGraphProofCapture.java`
- Test: `regelsuche-egraph/src/test/java/de/regelsuche/egraph/EqualitySaturationExtractionProofTest.java`
- Extend only when needed: existing EGraph/contract/soundness tests for the same concrete risk.

**Interfaces:**
- Consumes: Task1's `ExtractionProofBuilder`, certificate terms, limits, checker and outcomes.
- Produces: `EqualitySaturation.ProofOptions`, `ProvedResult` and `saturateWithProof(Expr,ToIntFunction<ENode>,ProofOptions)` exactly as specified; `SaturationProvenance.proofOutcome()` plus accepted-only `hasExtractionProof()`; preserved old constructors.
- Internal boundaries: `EGraphProofCapture` owns finite anchors and terminal capture status; EGraph supplies a fresh proof-aware allocation hook and bounded reconstruction using the same extraction choices. No public proof-on-existing-graph or raw-union certificate API is added.

- [x] **Step1: Write the fresh-run integration RED.** `childRewriteProducesDetachedCongruenceCertificate` requires a complete accepted certificate for a changed parent using only a child rewrite. `nativeRepeatedPlaceholderNeedsCongruenceBridge` requires the `g(a,b)`/`g(A,A)` case with one frozen term per placeholder. `extractionCombinesIndependentChildImprovements` requires an exact certified output never added as a whole tree.
- [x] **Step2: Add finite class anchors without repair axioms.** On each new class allocation, retain the exact symbol and earlier canonical child anchors through Task1's term builder. Canonical survivor lookup uses its existing anchor. Leave ordinary graph behavior untouched and never mutate live matching to insert an explanatory LHS. Make owned capture exhaustion terminal and non-throwing through graph repair.
- [x] **Step3: Capture each actual semantic native union.** Freeze the binding-to-anchor map once, prepare the exact ground instance, use the existing RHS applier, and record the instance for the actual union. Proof mode bypasses `nativeWitness`: local AST witnesses are empty, nativeWitnessExtractions is0, and actual omitted unions are counted as dropped witnesses. Configuration preflight prevents unsupported rules from entering this fresh proof path; the legacy path preserves all current callback, witness-accounting, assumption and matcher-limit behavior.
- [x] **Step4: Implement bounded fresh input/output handling.** Validate input expanded occurrences/depth before recursive graph insertion; enforce the graph insertion cap from the first node. Reconstruct the selected extraction iteratively using the same choice table with explicit cycle/depth/expanded-size guards. Bound diagnostic/search formatting with the existing `ExpressionFormatter.formatMeasured` before appends. Never call the legacy recursive extractor/unbounded formatter after the owned bounds have failed. Build, independently check and publish only accepted output; otherwise return the original and an explicit outcome. Include an actual diagnostic Result only when it was assembled safely, with no fabricated fixpoint/statistics. Enforce checker-token output object identity in both public composite result constructors and invocation input/output identity in the fresh runner.
- [x] **Step5: Add failure-boundary regressions.** Cover both repair collision forms, hash-cons reuse, reversed equality traversal, >256 essential unions, capture exhaustion around union/RHS/repair, builder/replay exhaustion, input/rule shared DAGs, scoped symbols, huge rationals, repeated-long-label formatting, zero-cost live cycles, tied choices, unavailable objective costs and initially unavailable root. `proofModeNeverInvokesLegacyNativeWitness` pins the bounded witness policy and0 extraction attempts. `checkedTokenCannotBeAttachedToDifferentOutput` rejects a valid `a=b` token attached to output `c`. Check final graph consistency where the internal test seam exposes it, and no restoration to COMPLETE after capture loss.
- [x] **Step6: Run focused current-source integration and existing EqSat tests.** Maven target when available: `./mvnw -pl regelsuche-egraph -am -Dtest=EqualitySaturationExtractionProofTest,EGraphTest,ScopedSymbolEGraphTest,EqualitySaturationContractTest,EqualitySaturationSoundnessTest,EqualitySaturationScalabilityTest,EqualitySaturationMatchLimitTest -Dsurefire.failIfNoSpecifiedTests=false test`. Use the controlled Java25/JUnit6 fallback only under the same recorded dependency boundary as Task1; do not rerun an unavailable Maven resolution or full canonical CI for an intermediate head.
- [x] **Step7: Review the native-union capture invariant and legacy diff, fix concrete findings, and commit.** No uninstrumented semantic native union may feed an accepted certificate. No repair union or final e-class membership can serve as an axiom. Independent kernel acceptance remains mandatory even when production appears correct.

### Task3: Explicit search mode, documentation and final qualification

**Files:**
- Modify: `regelsuche-search/src/main/java/de/regelsuche/search/strategy/EqualitySaturationStrategy.java`
- Modify: `regelsuche-search/src/test/java/de/regelsuche/search/strategy/EqualitySaturationStrategyTest.java`
- Modify: `docs/equality-saturation.md`
- Create: `docs/eqsat-extraction-certificates.md`
- Update this plan's checkbox state and design only when implementation evidence justifies it.

**Interfaces:**
- Consumes: Task2's additive fresh API/outcomes and Task1's independent checker for public examples.
- Produces: explicit `(Config, ProofOptions)` strategy constructor and `lastProofOutcome()`. Legacy constructors remain legacy. Checked evidence is available through provenance; synthetic path flags remain false.

- [x] **Step1: Write explicit-mode regressions.** `proofRequiredStrategyExposesCheckedOutput` requires accepted exact input/output evidence; `proofRequiredStrategyKeepsRootWhenUnavailable` covers unsupported rule and proof quota; `proofRequiredUnsupportedObjectiveKeepsRoot` requires UNSUPPORTED_OBJECTIVE. Existing custom/default callback and BestFirst fallback tests remain unchanged and passing.
- [x] **Step2: Implement explicit opt-in dispatch.** Use the fresh proof API only when selected. On unavailable proof return the original SearchState and preserve actual diagnostics/outcome. Do not promote the sentinel edge to an executable or construction-proven primitive. Preserve the existing default objective dispatch and callback routes.
- [x] **Step3: Document the public direct API and receiving-side check.** Include one small exact native example, caller-supplied trusted-rule requirement, checked-token boundary, distinct capture/build/replay work, typed unavailable outcomes, diagnostic-vs-returned result distinction, input/output/expanded-DAG bounds, and the unchanged native matcher/callback limits. State which old local-witness API remains unproved. No speedup, complete-normalizer, optimality or formal-axiom proof claim.
- [x] **Step4: Qualify the complete current source.** Run the new kernel/integration/strategy suites and the existing relevant EqSat/strategy contracts together using the real Java25/JUnit6 classpath. Inspect final source diff and clean state. Independent whole-branch review checks all three tasks against the spec before publication.
- [ ] **Step5: Publish one coherent reviewed PR and await its one automatic canonical run.** Verify the public Git tree equals the reviewed local tree; use expected-head lease and no force. Preserve historical failures honestly. Correct any actual new failure with a source change and one new automatic run; do not dispatch redundant CI or weaken thresholds.
- [ ] **Step6: Mark review-ready only after exact-head canonical success and report integration state accurately.** Follow actual repository review requirements. No review bypass, settings change, fabricated approval, unrelated release or outreach is part of this plan.

## Self-review

The three tasks cover the supported proof slice: immutable protocol, independent validation, bounded ground consequence construction, actual native union capture, finite anchors, bounded extraction, fail-closed fresh API, additive search adoption and truthful documentation. All five review-focus risks map to explicit tests above. Kernel/producer signatures are defined once in the spec and referenced unchanged here. Conditional contexts, native AC and arbitrary callback replay are deliberately deferred; no task implies they are delivered. The plan records checkable deliverables rather than implementation bodies or a new full-CI gate per task.
