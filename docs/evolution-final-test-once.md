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
