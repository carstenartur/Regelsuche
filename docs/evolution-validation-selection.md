# Frozen VALIDATION selection for evolutionary search

Issue #220 separates evolutionary learning into three information domains: TRAIN may mutate and
resume populations, VALIDATION may select one genome plus bounded search parameters, and FINAL TEST
may evaluate that already-frozen choice exactly once.

`EvolutionValidationSelection` implements the second boundary. The artifact binds the study plan,
split manifest, completed TRAIN run, `evaluationSplit=VALIDATION`, the ordered case matrix, every
candidate configuration and the deterministic result. Unknown fields, incomplete or reordered cases,
modified aggregates, substituted selections and prematurely advanced downstream states are rejected.

The implementation uses small domain types rather than one monolithic record:

- `EvolutionValidationSearchConfiguration` freezes the bounded search budget;
- `EvolutionValidationCaseEvidence` retains paired reachability, terminal and correctness evidence;
- `EvolutionValidationCandidate` independently recomputes candidate aggregates and eligibility;
- `EvolutionValidationSelection` orders eligible candidates and freezes the result.

## Reachability and correctness are different facts

A lost target is not called a mathematical correctness failure:

- `reachabilityRegression`: the baseline reached the target and the candidate did not;
- `correctnessFailure`: a reached candidate result was refuted;
- `correctnessRegression`: a confirmed baseline result became a refuted candidate result;
- `newlySolved`: a previously unreached target is now reached **and confirmed**.

Configurations with blockers, reachability regressions or refuted results are ineligible. Remaining
candidates are ordered by newly solved cases, reached cases, explored states, candidate evaluations
and finally configuration hash. When none is eligible, the artifact records
`NO_ELIGIBLE_CANDIDATE`; it never consults FINAL TEST for a replacement.

Correctness uses `CONFIRMED`, `REFUTED`, `INCONCLUSIVE` and `NOT_EVALUATED`. A non-reached target must
be `NOT_EVALUATED`; a reached target must carry correctness evidence. This prevents a refuted newly
reached result from improving the ranking while retaining inconclusive evidence for downstream proof.

FINAL TEST, proof, external novelty, promotion and public-evidence states remain `NOT_EVALUATED`.
The genome-only one-shot executor is documented in
[`evolution-final-test-once.md`](evolution-final-test-once.md).

## Combined genome and RewriteProgram integration

`EvolutionRewriteProgramValidationRunner` is the production library entry point for a completed
`ProtocolBoundRetainedEvolutionRewriteProgramPopulationRun`. It performs the following sequence:

1. `EvolutionRewriteProgramValidationPlan.create(...)` binds the exact study, split, retained TRAIN
   run, public VALIDATION commitment, every complete terminal genome/RewriteProgram and the full
   candidate × primitive-work-budget matrix. The matrix must fit the existing study evaluation
   budget. It cannot silently discard a terminal candidate.
2. `executeOnce(...)` reconstructs the plan against those external TRAIN roots and durably reserves
   the study before calling the lazy reveal loader or reading a private reveal path. The combined
   reveal-authorization overload requires the receipt created by the file store.
3. The native adapter compiles the actual genome and program, then uses the current measured
   BestFirst search, scorer, canonicalizer and exact rational-normal-form equivalence adapter.
   Baseline and candidate receive the same ordinary and genome rules; the candidate additionally
   receives its program. Primitive steps and total work follow the existing information-parity
   evaluator. Expected terminal annotations are never accepted as evaluator output.
4. Every configuration retains every paired case, including terminal reason, reached path, primitive
   depth, search and transformation work vectors, exact audit calls and mathematical correctness.
   Technical or incomplete observations retain known work and use a null complete total. They cannot
   become zero-cost fitness or win selection. A failed reveal retains a complete matrix of explicit
   unavailable observations without logging the private exception message.
5. Existing `EvolutionValidationCandidate` aggregates and eligibility checks are reused. Ranking
   uses the established newly-solved/reached/resource order, with the complete program-configuration
   hash as its final tie-break. Refuted or unsupported native results remain blockers. A failure
   never triggers a replacement candidate or a second evaluation.
6. `selection.handoff()` retains the complete selected configuration and immutable selection evidence.
   FINAL TEST, proof, external novelty, promotion and public-evidence states are all `NOT_EVALUATED`.

The additive plan, selection and handoff use separate `evolution-rewrite-program-validation-*/v1`
identities. They do not reinterpret the genome-only v1 configuration hash. Two programs sharing one
genome remain different configurations; every budget dimension and both canonical payloads are bound.
No historical v1 canonical algorithm or frozen fixture is changed.

The caller must retain one authoritative `FileEvolutionRewriteProgramValidationAttemptStore`
directory for the study. Its reservation filename depends on the study and split, not the selected
program, TRAIN run or requested budget. This combined-program store requires POSIX custody and
successful file and directory force before issuing a reveal receipt or reporting a successful result
write. The directory force includes the ledger's ancestors through its filesystem root, covering
newly created directory links as well as the retained file entry. Unsupported storage fails closed;
a failed force leaves an already-created
attempt consumed. New ledger directories are created with `0700`; existing directories must not be
writable by group or other users, and symbolic links are rejected throughout the ledger path.
Reservation and selection files are created with `0600` before any bytes are written. The persisted
reservation must still be a regular `0600` file when read without following links for a selection
write. Downstream consumers use `readSelection(expectedPlan)`, which checks the private directory,
both private records and their exact expected plan binding. This is a shared authoritative-directory
guarantee, not protection against its operator
deleting the ledger. The genome-only v1 ledger and its existing durability contract are unchanged.
The selection contains revealed paths and is not automatically a public artifact.

For example, after obtaining the actual completed TRAIN run and frozen public commitment:

```java
var plan = EvolutionRewriteProgramValidationPlan.create(
    study, splitManifest, retainedTrainRun, validationCommitment, frozenBudgets);
var selection = new EvolutionRewriteProgramValidationRunner().executeOnce(
    plan, study, splitManifest, retainedTrainRun, privateRevealPath,
    new FileEvolutionRewriteProgramValidationAttemptStore(authoritativeLedgerDirectory));
if (selection.hasSelection()) {
    var unqualifiedEvidence = selection.handoff();
}
```

Strict codecs recompute nested candidate identities, paired aggregates, selection and content hashes;
unknown or missing fields are rejected. When consuming an exported selection, use
`fromCanonicalJson(json, independentlyRetainedPlan)` to anchor it to the expected plan. A content hash
alone is not an external execution attestation or proof of an independent preregistration timestamp.
Complete confirmed paths must retain one exact audit call per path edge. Complete paired observations
are checked against the same effective primitive/state budget and mechanical search allowance used
by the native evaluator, including its reserved audit allowance. The existing v1 `WORK_BUDGET` outcome
may retain the actual overrun from its last measured batch; this does not become a reached result.
These are consistency checks over retained observations, not a replay of every transformation or
an independent attestation that an imported, internally coherent execution actually occurred.
Actual reveal values and post-reveal evidence remain subject to the study's publication policy.

## Remaining #220 work

The additive combined-program FINAL TEST plan, reservation and native executor now carry this
complete selected identity through the existing one-shot study semantics. A separate assessment
consumes the durable final evidence and invokes the existing preflight, exact proof and deterministic
counterexample gates. The protocol and API are documented in
[`evolution-final-test-once.md`](evolution-final-test-once.md#combined-genome-and-rewriteprogram-adapter).
The separate [selected-program internal authorization adapter](evolution-selected-program-runtime-authorization.md)
consumes that actual combined evidence through the existing leaf proof/counterexample and program
compiler/replay services. Its opaque executor retains the full selected configuration and never
projects combined identities into genome-only authorization records or spends another final attempt.
External novelty, public promotion, public evidence and release claims remain unevaluated; their
absence does not add a new prerequisite to mathematically authorized internal execution.

After independent review and integrated verification, the preregistered protected study still needs its
authorized real held-out execution and actual qualification evidence. This slice neither performs
nor authorizes that study, the #533 freeze, policy tuning or a production promotion. Its end-to-end
tests use public synthetic cases only; passing them does not close #220 or establish study gains.
