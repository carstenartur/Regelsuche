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

1. Commit the empty replay API and executable integration tests on the QA branch.
2. Run the tests with the repository's Java-25 Gradle setup and retain RED output.
3. Implement the adapter using the existing #1025 audit/domain helpers unchanged.
4. Repeat the focused tests, then the relevant full modules when available.
5. Remove the temporary QA workflow from the production PR. Full repository CI
   and dependency integration remain separate merge gates.

## Executed development evidence

The design was committed at `8760ed5b` before implementation. An explicitly empty
replay scaffold at `6a362b9c4a02262a01f3c2a77e6233518cb1c65e` was tested in run
**35442821895**: **13 tests, 5 failures, 0 errors, 0 skips**. The failures were the
missing positive TRAIN/application replay and missing structural guards, not a
compiler error or a failed dependency download. Raw artifact 10583634592 has
workflow-reported SHA-256
`8d44a29b26060f1ab223edd36de152d6d14d34b1a1da9bddb5a42b60bea13bd3`.

The implementation at `c4a1210cb061798a855c45b816373344e99bf5e4` was then tested in
run **35443027214**: **13 tests, 0 failures, 0 errors, 0 skips**. The Gradle command
completed successfully under Temurin **25.0.4+1**. Counts were read from the
workflow's explicit JUnit-XML summary, not inferred solely from its green status.
Raw artifact 10584164439 has workflow-reported SHA-256
`0986245a07a0c0bdf4a1196ce2ba38eeea06dac7d38d2971485b3bf2b9026e0e`.
These ZIP digests are retained identifiers, not a claim that this session locally
rehashed downloaded bytes.

The production PR retains exactly the tested Java blobs:

- adapter: `6f09f43eec6a81fb96db9603695e39e4ae5ad64f`;
- integration test: `fc5bf668661a96991e20fd70bc517fe86421ef93`;
- unchanged #1025 auditor: `03b9e1574e9582729660bb2ac62e111e8351e10d`.

Reproduce the integration from the repository root:

```sh
./gradlew --no-daemon --no-configuration-cache :app:test \
  --tests 'de.regelsuche.benchmark.TypedModPowTransferIntegrationTest'
```

A separate full learning/experiments-module run is recorded on QA commit
`6c809010e982582b3982bcb28fdbad0287bb871f`; that commit changes only the temporary
verification workflow. Its execution status is not implied by the 13-case result.
Full repository CI is still required after the dependent PRs are integrated.

## Boundaries

These are development integration tests, not the separately frozen #1026 study.
TRAIN witnesses come from the public #1025 TRAIN procedure, not from a supplied
TEST target. Renaming a witness does not count as learning a new mathematical
method or a new theorem. Reversed-factor syntax, learning a search policy,
artifact/corpus freeze and fair fixed-budget measurement remain distinct work.
The primitive closure and its optimum are not weakened to manufacture a gain.
No learning-superiority claim follows merely from a passing conditional replay.

The old domain checker accepts its existing variable/product integer fragment.
Unsupported constants or functions in the arithmetic premises are not silently
extended here. Scoped variables use their existing lossless name transport.
Supplied premises remain explicit assumptions: the adapter checks the conditional
law, not the truth of those premises in an external world or a concrete input.
