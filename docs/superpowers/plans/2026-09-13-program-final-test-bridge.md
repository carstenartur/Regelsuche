# Combined-program FINAL TEST and qualification implementation plan

> **For agentic workers:** Execute inline with superpowers:executing-plans. Do not delegate.

**Goal:** Complete the ordinary combined-program FINAL TEST adapter and deterministic native qualification orchestration without authorizing a protected study or production promotion.

**Architecture:** A separate versioned final plan binds the complete frozen VALIDATION handoff and the exact committed final case surface. A private durable reservation precedes the lazy loader and shares the existing genome-only final ledger's study identity and filenames. The current native paired evaluator produces complete immutable evidence; a separate assessment calls the existing preflight, exact polynomial proof and deterministic counterexample consumers. Missing external gates remain unevaluated.

**Tech Stack:** Java 25, strict Jackson record codecs, existing learning/search/math-algorithms adapters, JUnit 6 and focused offline Maven.

**Spec:** This bounded design extends `docs/evolution-validation-selection.md`, `docs/evolution-final-test-once.md` and `docs/evolution-rewrite-program-held-out-reveal.md`.

## Global constraints

- New worktree based on `f20d40a70c6245c18081e3831812c69086bced5d`; preserve the separate VALIDATION review tree.
- No existing v1 hash algorithm or frozen artifact bytes change. Never substitute a combined configuration hash for a genome hash.
- Tests use temporary public fixtures under explicit `synthetic_` study IDs. No real heldouts, flagship freeze, protected FINAL TEST execution, publishing, Gradle, API writes or child agents.
- Preserve exactly-once behavior across parallel callers, process crash, restart and result-write failure.
- Final execution and qualification assessment are separate calls. No automatic deployment or production authorization.
- Missing or unverified proof, external novelty, promotion and public-evidence gates remain `NOT_EVALUATED`.

## Task 1: Complete selected identity and one-shot native execution

**Files:** Add `EvolutionRewriteProgramFinalTestPlan`, `EvolutionRewriteProgramFinalTestEvaluation`, `FileEvolutionRewriteProgramFinalTestAttemptStore` and `EvolutionRewriteProgramFinalTestRunner`; extend the reveal-authorization overloads. Add `EvolutionRewriteProgramFinalTestRunnerTest` and a synthetic subprocess helper.

**Interfaces:** `Plan.create(study, manifest, retainedTrain, validationHandoff, finalCommitment)` freezes one configuration. `Runner.executeOnce(plan, study, manifest, retainedTrain, validationStore, loader, finalStore)` verifies the persisted selection, reserves, reveals lazily and evaluates once. `finalStore.readEvaluation(expectedPlan)` imports only the exact reserved identity.

- [x] Write red controls for reservation-before-reveal, complete selected program/budget identity and native paired work.
- [x] Implement versioned final artifacts, shared study reservation and the real evaluator adapter.
- [x] Exercise parallel callers, an abruptly terminated reservation process, restart and failed result persistence; retain all failure rows and unknown work.
- [x] Verify imports reject substituted selection/configuration, endpoint, status and work evidence; preserve the reviewed complete combined-selection checks.

## Task 2: Connect native qualification consumers

**Files:** Add `EvolutionRewriteProgramQualificationService` and a versioned immutable assessment, with focused tests.

**Interfaces:** `assess(expectedPlan, finalStore, repositoryRevision)` consumes the durable final evidence and calls existing native preflight, polynomial identity and deterministic counterexample consumers for every executing genome gene, also marking exactly which genes the selected program references. It retains their concrete evidence and outcomes without creating production rule/program authorization. `verifyAssessment(...)` anchors imported evidence to the actual durable final record and externally expected revision and independently reconstructs the native mathematical gates.

- [x] Write real positive and refuted-gene controls plus missing-gate and tamper controls.
- [x] Implement immutable full-identity handoff and deterministic reconstruction of every claimed native gate outcome.
- [x] Keep external novelty, production promotion and public-evidence statuses unevaluated; no authority supplier or production promotion call is added.
- [x] Document the ordinary path, remaining implementation/external requirements and exact command; run inherited and new focused tests before committing.

## Verified implementation result

The independent VALIDATION review fix `7288f917fd2c2141b32b7c3c827dd82a484e5b2e` is included.
Its complete audit and effective-budget checks are shared by the new FINAL TEST evidence importer.
The shared `requireBudget` method's visibility changes only within the package; its algorithm and
historical `WORK_BUDGET` representation remain unchanged.

Focused offline Maven passed **110 tests in 17 suites**: the 75 inherited controls after that review
fix, 15 new final/assessment controls, and 20 existing learned-rule/program authorization/promotion
controls. The learning module's generated classes were archived before this run so the final
verification used a fresh compile; source/class timestamps and JUnit XML counts were checked.
This is local Maven evidence, not full CI or protected-study execution.

Two retained behavior REDs preceded the fixes: an orphaned VALIDATION selection initially allowed
the final reveal, and a rehashed FINAL TEST success initially exceeded the selected primitive limit.
Both are now rejected. The initial missing-API compilation REDs are separately identified and are
not presented as behavioral controls. A later test expected an argument exception where the earlier
durable-reservation check correctly raised an I/O exception; the assertion was corrected without
changing production behavior.

The final production entry points do not synthesize old genome-only authorization records. A
combined-program runtime authorization/replay adapter remains ordinary implementation work if
production authorization is required. Actual preregistered held-out execution, independent external
review/publication evidence and any eventual promotion decision remain separate concrete gates.

## Review and study boundary

Independent review found two public behavior failures: a writable VALIDATION ledger could reach
the FINAL loader, and a low-level FINAL reservation could issue reveal authority after both
VALIDATION records had been removed. Both original assertion failures are retained as controls.
The execution-capable reservation now calls the separately reviewed private `readSelection` API;
its non-deserializable receipt alone permits reveal and result writing. Bare reservations continue
to consume the same v1 attempt identity without granting either capability. The directory force
barrier includes ancestors to persist newly created ledger links.

Focused offline Maven passed **42 tests in four suites**, including six independent review controls
and the existing FINAL, native assessment and VALIDATION runner controls. The real JDK ZIP-FS
control confirms no receipt/reveal on missing no-follow directory-force capability after file
creation; it is not a power-loss simulation. The existing subprocess crash and concurrency controls
also remain green. Artifact schemas, canonical material and protected resources are unchanged.

Independent review and integrated CI remain required. A real preregistered study needs a later concrete execution authorization and its actual evidence. Content addressing and synthetic controls do not establish independent preregistration timestamps, external novelty or production readiness.
