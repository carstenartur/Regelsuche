# Optional algebraic recognition for learned output patterns (#1026)

## Design

Close the known development gap for reversed product factors without inventing
another learner or silently treating every multiplication as commutative.
Retain exact recognition as the default. Add an explicit RecognitionProfile
constructor to TypedOutputPattern, limited to the existing structural
associative/commutative ADD/MUL recognition (no algebraic binding inference or
external equivalence exploration). Use the existing ExprMatcher implementation.

Applications remain untrusted proposals. Retain the selected recognition profile
and matcher trace in each application; these are not proof receipts. Keep the
four-argument Application constructor for exact structural callers. Only the
exact profile requires literal source reconstruction; non-exact recognition is
explicitly visible and must pass the existing independent arithmetic replay.

Always preserve the original Expr object for an unchanged template output slot,
not merely for unmatched output slots. Preflight the instantiated source and
target before allocating either tree. Retain occurrence/depth bounds and bounded
assignment attempts, with incomplete results whenever enumeration is truncated.
Do not sort program outputs, change the learned candidate, normalize training
examples, modify the primitive arithmetic auditor or relax promotion rules.

## Test-first execution

A branch-only QA workflow first provides an explicitly nonfunctional constructor
scaffold over the unchanged production matcher. The new integration tests must
fail on the missing algebraic behavior. Then install the implementation and run
both the focused integration and the complete learning/experiments modules.
Remove the temporary workflow from the production PR and preserve exact run and
source identities. The ongoing merge-only #1034 is not changed by this follow-up.

Use witnesses selected by the existing target-blind TRAIN search. Generalize
once per fixture, then apply to renamed operands, both product orders, reordered
and extra outputs. Preserve negative shared-binding and missing-domain controls;
verify every positive application independently with ModPowCompositionReplay.
These are development cases, not a new frozen held-out study or a speedup claim.
