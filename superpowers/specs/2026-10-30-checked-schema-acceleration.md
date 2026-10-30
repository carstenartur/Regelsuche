# Checked-schema dispatch and occurrence-path acceleration

Continue the existing work-replacement effort from main `e3575455e207959d296b8264d793f3a8bfd73f2a`.
The immediate deliverable removes avoidable work before the existing exact matcher and
checked application. It does not add another search kernel or mathematical authority.

## Choice and boundaries

A new learner or more rules would not remove the per-application overhead. Caching
successful proofs would require a separate, larger semantic contract. This increment
therefore uses a conservative, ordered shape index and persistent binary occurrence
paths. It is explicitly opt-in so frozen legacy work receipts remain reproducible.

The shape filter only rejects syntactically impossible root/child shapes. It does not
prove a match, identify equal variables, or validate a theorem. Surviving candidates
still use the existing matcher, domain checks, application, admission and final replay.
The original ordered schema prefix is selected BEFORE filtering: an optimization must
never pull lower-ranked rules into a capped prefix. Preparation, queries and path
materialization remain charged. Existing incomplete-native-qualification flags remain.

The same immutable prepared plan can be shared across cursors; each cursor owns its
pending occurrences, bindings and prepaid application. No process-global mutable cache.
Paths share their prefix during traversal and are materialized only for a matched
application. Different occurrence positions must remain distinct even for aliased ASTs.

## Evidence required

Differential filter checks against a deliberately simple linear reference, including
wildcards, all prefix bounds, empty indices and bit 63; immutable path correctness,
shared-prefix ownership and invalid inputs; real learned/restored-model differential
checks through the old and indexed cursors; unchanged verification and abandonment.
Distinguish candidate-count/path-copy reductions from full lifecycle or wall-clock wins.
