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
The next #220 slice must reserve the preregistered study before exposing any FINAL TEST case, consume
the frozen configuration without replacement or retry, and hand immutable evidence to the existing
qualification gates.
