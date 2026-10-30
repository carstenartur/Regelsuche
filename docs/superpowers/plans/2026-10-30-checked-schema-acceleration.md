# Checked-schema acceleration implementation plan

> For agentic workers: use superpowers:executing-plans task by task.

**Goal:** Remove unnecessary matcher calls and repeated occurrence-prefix copying.
**Architecture:** Add conservative bit-mask dispatch to the existing immutable checked-schema plan; reuse the existing cursor and all mathematical validation.
**Tech Stack:** Existing Java 25 modules and JUnit; no new runtime dependency.
**Spec:** `docs/superpowers/specs/2026-10-30-checked-schema-acceleration.md`.

## Constraints and review focus

Keep the default plan and frozen receipts unchanged. No loosened budgets, thresholds,
skipped existing tests, altered historical evidence or bypassed proof checks. Filter
only the original selected prefix. Preserve wildcards, sign-bit masks, every occurrence,
close/abandonment semantics, and visibility of new retained metadata. Native execution
qualification is not promoted by this change.

## Task 1 — immutable primitives

- [x] Write `CheckedSchemaAccelerationContract` before implementation and retain RED.
- [x] Implement `CheckedSchemaShapeIndex` and `SharedOccurrencePath` in the learning module.
- [x] Differentially test shapes/prefix bounds and persistent paths; provide a JUnit adapter.

## Task 2 — integrate with the existing plan and cursor

- [x] Add `withIndexedDispatch()` and `prepareIndexed(...)`, with distinct work/semantics revisions.
- [x] Keep default plan object ownership and default cursor accounting unchanged.
- [x] Select candidate masks in the existing cursor; keep every surviving exact check.
- [x] Share occurrence paths only in indexed mode; materialize within an owned paid frame.
- [x] Add actual learned/restored-model integration regressions and domain/cleanup controls.

## Task 3 — verification and publication

- [x] Run isolated executable contract checks and capture exact tested-source hashes.
- [x] Review the complete patch and check the pinned baseline file hashes.
- [ ] Publish one PR against main; run existing CI without modifying its gates.
- [ ] Report local versus hosted verification separately; do not merge without full acceptance.

## Execution notes

The container cannot resolve github.com, so a full clone/build is not available here.
Source files are read through the connected GitHub app, pinned to the exact main SHA.
Local checks compile the new dependency-light primitives against the unchanged
`RetainedGraph.View`/`Visitor` interface declarations. They are not a complete module,
Java-25, native-retention or lifecycle qualification; those require the real CI build.

Initial RED was an absent-API compiler failure, not a claimed pre-fix behavioral test.
Four post-implementation mutations (prefix refill, lost wildcard, lost bit 63, reversed
path) independently fail by assertion. Full integration tests are written but not
claimed executed locally. Independent review and hosted acceptance remain pending.
