# Observed cache history implementation plan

**Goal:** Prevent foreign cache lineage from becoming a canonical observed candidate freeze for #748.

**Architecture:** The existing typed measured candidate and cache-run history validate each observed cache row at the durable measurement-batch boundary.

**Tech stack:** Java 25, Gradle, Maven, JUnit; existing polynomial experiment contracts.

**Spec:** [Observed cache history](../specs/2026-11-20-observed-cache-history-design.md).

## Global constraints

Preserve v1 receipts, study pins, schema identities, frozen budgets, cache capacity and profile capability/status. Keep protected qualification inputs sealed for study purposes. The existing module suite can read historical corpus resources for compatibility checks. Evidence from this task is limited to the run-lineage contract. Reuse the existing typed AST/evidence/history architecture. Work only in this isolated branch with local commits. #749 and its automation remain outside this work.

## Review focus

Check canonical order and per-run isolation; rejection before durable freeze; legacy compatibility; authentic positive evidence and negative controls; documentation claims and source anchors. Tests must exercise behavior through the public boundary and distinguish genuine local replay evidence from valid whole-run lineage.

### Task 1: Enforce observed cache lineage before freezing

- [x] Read root `AGENTS.md`, relevant research-index entries, the spec, and the existing measurement batch/history/freeze code.
- [x] Add a regression through `PolynomialTheoryUtilityCandidateMeasurementBatch.create` or `PolynomialTheoryUtilityCandidateFreeze.create` using the existing real externally seeded replay. Run it and record the expected RED failure before editing production code.
- [x] In `PolynomialTheoryUtilityCandidateMeasurementBatch`, validate observed cache rows in the existing canonical result order with one fresh `PolynomialTheoryUtilityCacheRunHistory` per `runId`, pairing each result with its typed measurement. Preserve the v1 path.
- [x] Replace the globally invalid observed positive freeze fixture with valid native measured evidence. Keep interrupted-replay serialization/work-partition assertions at their valid row-local boundary. Add only meaningful missing run-isolation/FIFO/replay controls, respecting the frozen budget constraints.
- [x] Run GREEN focused tests and the full affected module suite. Record exact commands, counts, skips and logs. Run the repository's applicable formatting/static/coverage checks and report any blockers.
- [x] Update `docs/polynomial-theory-utility-active-adapters.md` and the research strategy index with the bounded finding and unchanged study blockers. Regenerate current source anchors.
- [x] Self-review, commit the implementation and docs, and write the report with commits, changed files, RED/GREEN evidence and remaining limits.
