# Quality-aligned source policy selection implementation plan

> Execute inline, using the existing selector and frontier. The user has approved the work-replacement design and requires suitable PRs to be merged first.

Goal: Price learned rule profiles using the same paid online quality termination at training and evaluation, rather than attributing unused full-frontier work to a shortcut.

Architecture: Add `TypedSourcePolicySelection.trainUntil(tasks, profiles, objective, maximumOutputScore, contract)` to the existing selector. Reuse validation and the trial loop. A frozen quality goal retains threshold/continuation contract; old callers and v1 JSON keep their existing behavior.

Constraints: Java 25, no dependencies, no new search/learner, no workflow/gate/baseline/frozen-study changes, always retain all trial and final replay costs, explicit state-local contract only, no mathematical authority from a score or a digest. The caller uses the same objective semantics in training and evaluation.

Files:
- Modify `regelsuche-learning/src/main/java/de/regelsuche/inventory/TypedSourcePolicySelection.java` only for production.
- Add `regelsuche-learning/src/test/java/de/regelsuche/inventory/TypedSourcePolicyQualitySelectionTest.java` (six selector contracts).
- Add `regelsuche-learning/src/test/java/de/regelsuche/evolution/CheckedSchemaQualitySelectionTest.java` (real trained/restored model integration).
- Add documentation `docs/research/quality-aligned-source-policy-selection.md`.

Review focus: final replay overrun is not success; unattainable thresholds still favor better attainable output; overshooting a sufficient threshold does not justify excess work; training-source exclusion and complete-reference protection persist; legacy serialized observations remain unchanged.

1. Publish the already-written seven tests on a separate draft branch with a compile-only `trainUntil` seam delegating to old `train`. It is deliberately not merge-qualified. The foundation #1047 is the first merge dependency; the draft isolates follow-up test execution while that CI runs.
2. Run the normal hosted Java-25 job and inspect actual JUnit failures: selection must still pay unused work under the old delegate; quality metadata is absent. Retain the RED receipt; no compile-error red is sufficient.
3. Change the one selector class: overload the existing trial loop with optional goal, use `searchUntil` for all new-mode trials and frozen evaluation; retain the original `search` for old mode.
4. Rank goal-mode profiles by budget violations, quality misses, summed positive remaining objective deficit, then total paid work and stable declaration order. Use exact BigInteger deficits to avoid overflow at long objective extremes. Keep the old comparison order unchanged for legacy calls. Add preservation guards for mixed success/overrun and exact legacy JSON.
5. Verify new and existing module tests plus unchanged frozen comparisons. Inspect both synthetic-contract and actual learned-rule diagnostics. Record setup/restore and all trial work separately; do not claim lifecycle performance acceptance.
6. Review the complete delta and current CI before any further merge. Retain incomplete work as an explicitly scoped open PR rather than bypassing a gate.
