# Typed CAS Computation Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task by task, with an independent whole-change review.

**Goal:** Recognize and verify word/carry relationships in Regelsuche and export complete Java value tasks with explicit source effects from Sandbox.

**Architecture:** Retain JointComputationPlan/OptimizationRequest as the computation contract. Extend the existing SDK bit proof and candidate path; preserve Java effects in a distinct Sandbox task schedule. Future CAS adapters use the existing Regelsuche solver/proposal boundaries.

**Tech Stack:** Java 25, JDT, Regelsuche SDK, Maven/JUnit.

**Spec:** ../specs/2026-10-26-typed-cas-computation-design.md

## Global Constraints

- Java 25; Maven/JUnit are the executable authority.
- No new Python/workflow automation, duplicated algebra or unchecked CAS results.
- Existing proof, budgets, source identity and Apply/Undo checks remain authoritative.
- Main-based PRs; integrate prerequisites first; review slices near or below 1,500 text lines.

## Review Focus

- Wrong bit identity across shifts must not prove a false equality.
- Sign extension, char zero extension and masked negative/width shift distances.
- Deep/wide expressions, small budgets and cancellation fail closed.
- Aliases and reads after stores retain distinct source occurrences.
- A failing store preserves preceding local commits and hides following commits.

## Task 1: Demonstrate the missing numeric relationship

Files: SDK test `WordProjectionOptimizationTest.java`; Sandbox pinned Nat256 task comparison test.

- [x] Hand-build high/low reconstruction and require independent universal proof; observe failure before implementation.
- [x] Retain actual source occurrence traces and compare values with literal boundary expectations.
- [x] Record the real Nat256 block's extracted fragmentation versus complete value plan, without claiming a speedup.

## Task 2: Extend Regelsuche's existing bit reasoning

Files: `BitwiseProof.java`, `JavaBitwiseCandidates.java`, SDK tests and README.

- [x] Add typed per-bit projection through constant shifts/casts, with independent (atom, bit) variables and existing work/depth bounds.
- [x] Add generic bounded reconstruction proposals; retain independent checker and final cost selection.
- [x] Cover both word widths, sign/zero extension, shift wrapping, wrong masks, wrong bindings, cancellation and insufficient budgets.
- [x] Run complete SDK/dependency Maven verification and review the diff.

## Task 3: Export complete source tasks without weakening edit safety

Files in Sandbox: `JavaComputationExtractor.java`, new `JavaComputationTask.java`, task tests and architecture/QA documentation.

- [x] Add a bounded task extraction entry point reusing the existing decoder; ordinary extraction stays unchanged.
- [x] Retain all local value versions, every fresh read, low-word stores and later carries in an ordered source schedule.
- [x] Compare real pinned Nat256 add plans with manually assembled arithmetic, including alias/exception/local-commit observations.
- [ ] Prove cross-store task extraction grants no automatic edit permission and run the full headless mathematics suite.

## Task 4: Qualify and integrate

- [x] Diagnose complete-plan search separately from numeric verification; reproduce cold codec loss of DAG sharing.
- [x] Restore exact structural sharing in the common plan resolver, retaining visit/key work and all structural limits.
- [x] Add metered resolver traversal to the affine analysis, preserving shared budget and cancellation.

- [x] Independent whole-change code review; fix substantive findings with regressions.
- [ ] Publish main-based Regelsuche change; qualify its exact SDK source revision through the established staging workflow.
- [ ] Integrate exact staged artifact/receipt into Sandbox after prerequisites are integrated; run native CI and review comments.
- [ ] Document measured results and remaining conservative boundaries; no unsupported runtime claims.

## Execution ledger

- Architecture review completed before implementation. Existing SolverIr is an obligation contract, not a substitute for typed computations.
- Ruling: implement bit projection and source-task export now; reuse existing backend facilities and document the future provider bridge rather than introduce an unused competing CAS protocol.
- Ruling: source tasks are inspection/comparison inputs, not source edits. Cross-store automatic rewriting requires an independently verified effect schedule and is not enabled by enlarging a region.

- SDK RED: the original eight word-projection tests produced four failures before implementation.
- SDK GREEN: eleven word-projection tests and 196 total SDK tests pass; the full seven-module Maven verification reactor passes.
- Independent implementation review found no soundness blocker. Its budget/cancellation, shifted-bit bound, generator-variant and stale-revision test gaps were closed and re-reviewed.
- Nat256.add comparison: the pinned source yields 25 ordinary edit regions versus one complete inspection task (26 outputs, 16 reads, eight stores); independent SDK-plan verification and alias/exception replay pass. Sandbox qualification and exact SDK staging remain separate follow-up gates.
- Complete-plan search found a second concrete issue: cold codec transport inflated 87 prepared nodes to 1,753 separate tree occurrences and exceeded the preparation cap. A core regression failed before structural interning; transport, carry-search and affine budget/cancellation regressions then passed. The first qualified SDK revision is superseded by this follow-up and must not be presented as the final consumer artifact.
- Final follow-up verification: all seven dependency/SDK modules pass, including 199 SDK tests. The fixed final-preparation regression allows 3,400 units after measured resolver accounting (3,385 consumed; one extra preparation costs 103), and the historical 1,092-unit request still exhausts its allowance. The CI complexity finding in bit projection was resolved by separating cast, Boolean and shift projection helpers, without a policy exception.
- Consumer follow-up exposed duplicate interning: preparation now uses canonical resolver identities, retaining visits and explicit type/edge checks. It also exposed a charge for the final Candidate constructor on paths that never construct a Candidate. That charge now occurs only on the actual candidate path; independent final proof and constructor preparation remain fresh.
- Intermediate budget follow-up: all seven modules passed with 201 SDK tests. A new non-improvement regression failed at 341 units before removing an unused 15-unit constructor reservation. No production request budget was raised.
- The full pinned Nat256 JDT task and independent manual plan both reach search. The manual plan retains the actual int-to-long initializer conversion so the original evaluation trace remains complete. Both report an incomplete search relation, not an unsupported task or a proved runtime improvement. Source-task/late-preparation focused checks pass; final consumer-wide and corpus validation remain separate gates.
- Final shared-work verification: all seven modules pass with 201 SDK tests. Initial envelope/schedule construction share one local resolution; capture retains the already bounded producer states, while external replay remains fresh. The tight regression consumes 3,084 units with an 84-unit extra preparation and a 3,100-unit allowance. Independent code and architecture reviews found no blocker. Targeted consumer diagnostics retain both established Hawk fragments without changing the production file budget or allocation; exact-artifact qualification remains mandatory.
- Consumer-wide validation identified two further small Hawk sharing candidates lost at capture (2,352 versus 2,351 units; 2,340 versus 2,296). Retaining the original bounded source envelope in the search result removes the repeated construction; an isolated diagnostic restores all 18 original spans without changing allocation. A generic shared-shift regression fails at 2,244 units before reuse and passes under 2,200 afterward. External replay remains fresh.
- Larger preparation-retry fixtures exposed an overly conservative consumer debit. Initial-validation preparation failures now report earlier trace work plus a safe failed-preparation bound; legacy/unrelated Unsupported outcomes retain unknown work (-1). Full reactor verification covered 205 SDK tests with one new fixture threshold correction; all seven focused accounting/capture tests then passed. The late-trace regression independently validates its completed trace prefix instead of assuming an absolute work total. Exact SDK qualification and complete consumer/corpus reruns remain required.
- Complete consumer scans exposed the remaining NTRU Boolean proof regression: unnecessary projected subterm attempts preceded a cheap complete opaque-word identity. The existing opaque proof now runs first at Boolean roots; a folded-word selection regression previously exhausted 70,000 verification units and now passes. Checker revision v11 rejects v10 receipts; projection and independent replay remain available.
- Completed search-limit outcomes may consume less than the outer request allowance. The SDK exposes this through `BudgetExceeded.hasCompleteWorkAccounting()`; partial checker stops and unknown/legacy counters remain conservative. The search/checker handoff unit is explicitly included in the aggregate. Architecture review confirmed this narrow contract; no generator memo or production budget increase was adopted.
