# Recurrence knowledge integration

**Goal:** Acquire and reuse independently checked recurrence laws through CumulativeTaskKnowledge, including its immutable generations, costs and snapshots.
**Architecture:** A bounded typed recurrence catalog is an additional payload of the existing knowledge state. The existing recurrence synthesizer/verifier execute every mathematical operation. Expression tasks, schema learning and v1/v2 byte encoding remain unchanged.
**Spec:** User-approved continuation of the recurrence implementation and historical-learning design; public details in docs/linear-recurrence-discovery-domain.md.
**Approaches:** A separate persistent learner would duplicate lifecycle management; converting induction laws to unconditional expression rewrites would be unsound. Extend the existing state with a typed, source-bound catalog instead.

## Constraints and review focus
- Reuse requests ask for **a** nonconstant law within the degree bound, not the complete nullspace.
- Caller transition and new initial state are verified on every use; stored metadata is not proof.
- Unknown transitions fall back within the same request budget. Completed failures stay paid.
- Frozen parent lessons, separate initial-state validation, no query-side learning.
- Bounded catalog and strict v3 snapshots; preserve old payloads and costs when either domain learns.
- No workflow/threshold/baseline changes and no new parallel search kernel.
- Fix the existing review gap: execute save and restore JVMs from a JUnit test.

## Implementation steps
- [x] Add failing integration contracts for acquisition, reuse, two generations, schema preservation and persistence.
- [x] Implement the bounded recurrence catalog and source-bound execution on existing algorithms.
- [x] Wire typed solve/learn operations into CumulativeTaskKnowledge and v3 snapshots.
- [x] Validate candidates on separate initial states, retain rejected/acquisition-limited receipts.
- [x] Add tamper, budget, old snapshot and real subprocess contracts.
- [x] Execute a mixed task stream (baseline/frozen/cumulative), including all declared acquisition and restoration work.
- [x] Re-run existing expression-learning regressions and check the final diff; publish on existing PR #1134.

## Local verification boundaries
Java 21 is present; full project targets Java 25. Attempt normal builds first. Any separate direct compatibility harness must be clearly labelled and must not change published production source. No independent reviewer tool is available here; the GitHub review remains the independent gate.

**Publication gate:** Direct tests completed; hosted Java-25 CI and independent review remain required.
**Diagnostic ruling:** The first direct expression regression lacked service-provider resources and also failed on the unchanged predecessor. Restoring the actual seven-module registrations reproduced the frozen costs; no production test or gate was relaxed.
