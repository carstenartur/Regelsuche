# One-shot FINAL TEST execution for evolutionary search

The FINAL TEST lifecycle consumes the frozen `EvolutionValidationSelection` without reopening model
selection. `EvolutionFinalTestRunner` creates and durably writes an
`EvolutionFinalTestReservation` before the evaluator receives the first final case.

## Study-scoped reservation

The file identity contains only the preregistered study-plan and split-manifest hashes. It deliberately
does not contain either the VALIDATION selection hash or FINAL TEST suite hash. After any observation,
a caller therefore cannot obtain a fresh attempt by replacing the selected configuration or suite.
The reservation itself still binds the exact selection, suite, genome and configuration hashes.

`FileEvolutionFinalTestAttemptStore` uses `CREATE_NEW` and forces reservation bytes to disk. A second
process, restart, evaluator exception, crash after reservation or failed result write cannot retry.
A crash may leave a consumed reservation without a result; this is preferable to silently spending
holdout information twice.

## Complete paired evidence

Every ordered final case retains baseline and selected measurements: terminal reason, path depth,
explored states, candidate evaluations, correctness status and optional result-artifact hash. Evaluator
exceptions become explicit failed measurements and never omit a case or trigger a retry.

Reachability regressions, refuted results and confirmed-to-refuted correctness regressions remain
separate. `newlySolved` requires a newly reached and confirmed result. Aggregates and execution outcome
are recomputed from the complete ordered matrix.

The consumed attempt is marked `COMPLETED` even with failures or quality blockers; the outcome exposes
those facts. Proof, external novelty, promotion and public-evidence states stay `NOT_EVALUATED` for a
subsequent immutable qualification handoff.

## Additional fail-closed invariants

The evaluator receives an immutable context containing the suite's baseline-profile hash, the frozen
selected genome hash, and the selected bounded search configuration. A reached result must retain a
content hash for its result artifact. Technical evaluator failures remain technical failures; they are
not counted as newly solved cases, reachability regressions, or mathematical correctness regressions.
Before accepting an evaluation, the durable store compares every reservation-bound identity field,
not only the reservation content hash. File contents are forced to storage and the parent directory is
forced where the platform supports directory channels.

## Local reproduction

The combined-program library path is described below. The original genome-only v1 contracts,
canonical algorithms and frozen artifacts retain their existing identities.

The focused contract can be run from an ordinary checkout without GitHub Actions:

```bash
./gradlew --no-daemon :regelsuche-learning:test \
  --tests 'de.regelsuche.evolution.EvolutionFinalTest*'
```

The repository-wide release-equivalent verification remains `./gradlew --no-daemon ciCheck`.

## Combined genome and RewriteProgram adapter

`EvolutionRewriteProgramFinalTestPlan.create(study, manifest, retainedTrain, validationHandoff,
finalCommitment)` binds the complete durable VALIDATION selection, selected genome, selected
RewriteProgram, every search-work limit and the exact public FINAL TEST commitment/case surface.
Its separate `evolution-rewrite-program-final-test-*/v1` artifact family never puts a program or
combined configuration identity in an old genome-only field.

`EvolutionRewriteProgramFinalTestRunner.executeOnce(...)` first reconstructs those external roots
and verifies both the actual private VALIDATION reservation and its immutable selection. It then
reserves the study, obtains a non-deserializable receipt, constructs the existing stage-specific
reveal authorization, and only then invokes the lazy loader or reads the private reveal path.
The loaded bundle must match the entire final commitment before the evaluator sees a case.

`FileEvolutionRewriteProgramFinalTestAttemptStore` uses **the same** study-plan/split run identity
and `<run>.reservation.json` filename as `FileEvolutionFinalTestAttemptStore`. All adapters and
processes must share this authoritative directory. Changing the program, budget, suite or adapter
does not create a second attempt. Exclusive creation and mandatory file/directory force precede the
winning receipt. Only that store instance's execution-capable receipt can write the one immutable evaluation.
The execution-capable `reserve(plan, validationStore)` overload verifies the private VALIDATION
directory, its reserved plan and the exact persisted selection before exclusive FINAL creation.
The low-level `reserve(plan)` overload only consumes the attempt: its receipt cannot authorize a
reveal or write an evaluation. These capability distinctions are private runtime state, not new
artifact fields or a different run identity.
Restart, a process crash after reservation, reveal failure and result-write failure all consume the
attempt; there is no resume/replacement operation. A reservation without a complete result remains
consumed and unqualified.

This additive store requires a POSIX filesystem, owner-only `0600` artifact files and a directory
that other users cannot write. It creates new ledger directories as `0700`, rejects symbolic links
throughout its paths and opens imported files with `NOFOLLOW_LINKS`. Unsupported custody or failed
directory force fails closed. The force barrier includes the ledger directory and every ancestor
through the filesystem root, so newly created ledger-directory links are also forced before a
receipt is issued. Filesystems must support this entire barrier. Deployment must retain one protected
authoritative directory and its backups; this is not a distributed ledger, deletion-proof operator
boundary or a guarantee against storage hardware that ignores force. The selection and final paths
are private study material.

The adapter uses the same current native compiler, measured ordinary-plus-genome baseline,
ordinary-plus-genome-plus-program candidate and exact rational path audit as the combined VALIDATION
adapter. It evaluates one unchanged selected configuration. Every committed final case retains both
sides, concrete paths, primitive depth, terminal outcome, mathematical audit, search/transformation
work vectors and audit calls. Unknown work stays null; failure and incomplete rows remain present.
Unknown observations cannot count as new solves, reachability losses or mathematical regressions.
The final summary is independently recomputed from those rows. Imports bind path endpoints and
complete audit coverage and use the same effective selected budget as execution. Historical
`WORK_BUDGET` batch overruns remain representable without becoming a successful reached result.

`readEvaluation(independentlyRetainedFinalPlan)` cross-checks the actual reservation and full
expected identity. A content hash and internally consistent rows alone do not attest that an external
execution happened; custody of the real execution ledger and the externally frozen roots remains
necessary. No caller-supplied measured-output acceptor exists in the production runner.

## Explicit downstream native assessment

After actual final execution, a separate `EvolutionRewriteProgramQualificationService.assess(...)`
call consumes that durable evidence. It does not reopen a reveal or rerun private final searches.
It calls the existing genome preflight, exact polynomial identity verifier and the unchanged fixed
counterexample gate from `LearnedPatternRuleAuthorizationService`. Every flat genome gene can execute
in the paired evaluator, so all are checked; the assessment also marks exactly which genes the
selected program references. Conditional genes remain unsupported by the existing assumption-free
promotion policy. A failed or incomplete final evaluation leaves all unexecuted native gates
`NOT_EVALUATED`.

The immutable versioned assessment retains the full selected/final evidence, exact proof observations
and counterexample artifacts, including their real fixed budgets and negative outcomes. Construction
and import independently recompute the native gene mathematics and gate results. These bounded
post-study mathematical checks are separate from retained final-search work. A fully rehashed false
proof, missing gene or invented summary cannot become a passed native gate. Consumers use
`verifyAssessment(json, expectedFinalPlan, authoritativeFinalStore, expectedRepositoryRevision)` to
anchor an imported handoff to the actual durable final evidence and externally expected revision.

For example, library code with already frozen roots can prepare the explicit calls:

```java
var finalPlan = EvolutionRewriteProgramFinalTestPlan.create(
    study, splitManifest, retainedTrainRun, selectedValidation.handoff(), finalCommitment);
var finalStore = new FileEvolutionRewriteProgramFinalTestAttemptStore(authoritativeFinalLedger);
var finalEvidence = new EvolutionRewriteProgramFinalTestRunner().executeOnce(
    finalPlan, study, splitManifest, retainedTrainRun, validationStore, privateFinalReveal, finalStore);
var assessment = new EvolutionRewriteProgramQualificationService().assess(
    finalPlan, finalStore, independentlyExpectedRepositoryRevision);
```

This is an API example, not authorization to execute a protected study. Both final execution and
native assessment leave external novelty, production promotion and public-evidence status
`NOT_EVALUATED`. A passed native assessment does not create a learned-rule receipt, register a
program, authorize deployment or establish external novelty.

## Remaining integration and study requirements

The complete combined-program FINAL TEST adapter, native mathematical assessment and explicit
[selected-program internal authorization adapter](evolution-selected-program-runtime-authorization.md)
now exist as ordinary production library code. The separate authorization calls preserve the complete
selected configuration through actual leaf proof/counterexample authority and native program replay.
They consume the same durable combined final result; they neither require a second genome-only final
attempt nor project combined identities into old genome-only authorization records. The assessment
alone still creates no executable authority.

Independent code review and full integrated CI remain required. Actual study claims additionally
require the preregistered roots and private custody, later concrete authorization to execute the real
held-out protocol, the one real resulting evidence set, and actual external-review/publication gates.
Public synthetic controls do not prove held-out gains, independent preregistration timestamps,
external novelty or production readiness; they do not close #220.

The focused adapter/assessment controls use only temporary `synthetic_` studies and public formulas:

```bash
mvn -o -pl regelsuche-learning -am -Dmaven.compiler.useIncrementalCompilation=false \
  -Dtest=EvolutionRewriteProgramFinalTestRunnerTest,EvolutionRewriteProgramQualificationServiceTest,EvolutionRewriteProgramFinalTestReviewTest,EvolutionRewriteProgramValidationRunnerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
