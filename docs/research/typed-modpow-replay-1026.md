# Conditional arithmetic replay of typed context proposals (#1026)

## Bounded integration design

Continue #1031/#1032 without introducing another generalizer, changing their
matching behavior or modifying the frozen #1025 implementation. Expose the
existing independent composition-difference audit and domain check through a
small public, bounded replay adapter in the experiments module. The adapter
must not depend on learning classes or trust a pattern's score or sample claims.

A successful result retains the exact source/target ASTs, the concrete premises
and the primitive factor order independently checked. No globally valid rule,
promotion receipt or complete search-cost statement is created. Missing premises,
changed unrelated outputs, a changed modulus or a non-composition must fail.
Preflight node/depth limits apply before the old recursive auditor is called.

Use app integration tests (where learning and experiments already meet) to form
an actual TypedPatternGeneralizer candidate from renamed observations derived
from the original target-blind TRAIN search. Independently replay the training
observations before formation. Apply with TypedOutputPattern to new names,
reordered outputs and an extra output; replay the whole resulting program and
retain the unchanged output object. Separate matching from mathematical approval
by showing that missing domain assumptions do not prevent a syntax proposal but
do prevent arithmetic acceptance.

## Execution sequence

1. Commit the empty replay API and executable integration tests on this branch.
2. Run the tests with the repository's Java-25 Gradle setup and retain RED output.
3. Implement the adapter using the existing #1025 audit/domain helpers unchanged.
4. Repeat the focused tests, then the relevant full modules when available.
5. Remove the temporary QA workflow before opening the production PR. Full
   repository CI and dependency integration remain separate merge gates.

## Boundaries

These are development integration tests, not the separately frozen #1026 study.
TRAIN witnesses come from the public #1025 TRAIN procedure, not from a supplied
TEST target. Renaming a witness does not count as learning a new mathematical
method or a new theorem. Reversed-factor syntax, learning a search policy,
artifact/corpus freeze and fair fixed-budget measurement remain distinct work.
The primitive closure and its optimum are not weakened to manufacture a gain.
No learning claim follows merely from a passing conditional replay.

The old domain checker accepts its existing variable/product integer fragment.
Unsupported constants or functions in the arithmetic premises are not silently
extended here. Scoped variables use their existing lossless name transport.
