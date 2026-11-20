# Observed cache history at the freeze boundary

## Problem and acceptance

Issue #748 requires evidence to belong to the measured run. The existing row-level contracts accept a genuine cache replay whose insertion happened outside that run. `PolynomialTheoryUtilityCacheRunHistory` rejects that replay when used directly, but the public observed measurement-batch/freeze path currently does not apply that history check.

Accept an observed measurement batch only if each cache run starts empty and every hit follows a matching retained insertion in that same run, in the existing canonical row order. Reuse the existing typed history validator. Validate at the ordinary batch boundary so the subsequent canonical freeze cannot emit accepted evidence for an invalid history.

## Boundaries

- Preserve the historical v1 receipt path, schemas, hashes, study pins, work budgets, cache capacity, profile capabilities and qualification status.
- Give each observed cache `runId` its own fresh history; never share history across runs, checkpoints or profiles.
- Keep genuine row-level interrupted-replay and work-partition serialization coverage. Replace the invalid globally accepted positive fixture with an authentic valid run.
- Leave protected qualification inputs sealed for study purposes and skip manual inspection of their contents. Existing compatibility tests may read historical corpus resources on the test classpath. This correctness fix supplies evidence about run lineage. Qualification, utility, and performance require their own study evidence.
- Update the active-adapter documentation and research index with hypothesis, implementation, finding and consequence separated.

## Verification

A real externally seeded cache replay must fail through the normal measured-batch/freeze entry point. Observe that regression failing before production changes. Valid native measured rows must still freeze. Retain meaningful history controls for insertion/replay, interrupted replay, run reset and FIFO behavior without inventing impossible insertions at the frozen study budgets. Run the affected full module tests and the repository quality checks required by the changed files.
