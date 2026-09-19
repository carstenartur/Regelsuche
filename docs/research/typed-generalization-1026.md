# Typed source/target generalization prerequisite (#1026)

## Scope fixed before implementation

This is an opt-in syntax-learning prerequisite, not completion of the held-out
arithmetic transfer experiment. The existing PatternGeneralizer, its historical
normalization and the frozen #1025 experiment remain unchanged.

Inspection of PatternGeneralizer.generalize shows that it independently
normalizes each source and target. AstNormalizer alpha-renames variables in
first-occurrence order. Renamed but otherwise identical training examples can
therefore lose the very differences needed for abstraction. Independently
normalizing a reordered target also loses its correspondence with the source.
The tests will characterize the first observation before claiming it as measured.

Add a bounded TypedPatternGeneralizer that consumes actual Expr pairs, without
formatting, reparsing, arithmetic normalization or variable renaming. Reuse the
existing PatternExpr representation. Generalize both sides with a shared table
keyed by the complete vector of corresponding input subtrees. Identical vectors
receive the same placeholder. A target-only variable/abstracted subtree must be
rejected, not invented. Exact rational literals, scoped SymbolId values, grouping,
function names and argument order must survive reconstruction of every example.

The result is only a candidate hypothesis. Training assumptions are retained as
sample data, not silently generalized or certified. This API must neither create
a trusted Transformation nor auto-promote a rule. Subsequent arithmetic replay
must establish the concrete premises for every application.

## Verification

Use test-first behavior checks for reconstruction, shared placeholders, variable
reordering, scoped identity, exact rational/grouped ASTs, rejected target-only
holes, immutable retained examples, deterministic results and structural limits.
Include a characterization of the historical two-renamed-example behavior.
Run the entire learning-module test suite after focused tests pass.

## Scientific boundary

No #1026 held-out corpus is opened or evaluated in this prerequisite. A unit
fixture involving modular powers is not a held-out transfer result. The next
experiment must freeze its corpus and learned artifact separately.

The NO_LEARNING control must retain its full primitive inventory. A macro built
from that inventory cannot improve the optimum of the same completely exhausted
closure; useful learning would instead reduce search work under a declared budget
or transfer to a broader task family. Report optimum parity honestly rather than
weakening the primitive baseline or claiming a runtime gain from a proxy score.

## Executed evidence (19 September 2026)

The scope above was committed at `9f21330` before implementation. The first
behavioral run used an explicitly empty API scaffold, not a falsely described
legacy implementation. Run **35435913509**, source `800a371`, produced **10 tests,
8 failures, zero errors/skips**. The unchanged legacy no-abstraction
characterization and empty target-hole control passed; the eight new behavior
contracts failed before implementation.

Implemented source **805af019b269faf34a16d338623bd198922b49a4** was then run in
**35436020419**: **10/10 focused tests passed**, followed by **783/783 tests in the
complete learning module**, zero failures, errors or skipped tests in either run.
The downloaded XML contains 154 learning-module suites. Both Gradle executions
completed successfully. Counts and identities were checked directly in the ZIP,
not inferred solely from the workflow status.

The unchanged legacy characterization demonstrates that the two renamed ModPow
fixtures produce no legacy multi-example abstraction. The new typed path
reconstructs both source and target using four shared bindings, without any
ModPow-specific code. Separate arbitrary f/g syntax fixtures pin reordered and
repeated variables; they are explicitly NOT mathematical equivalence claims.

[Machine-readable evidence](typed-generalization-1026-evidence.json) retains the
source, test, run and artifact identities. Raw workflow ZIPs are identified there
and were downloaded for verification; they are not copied into this repository.
The temporary branch-only workflow is removed from the final diff. The Java
implementation and tests remain byte-identical to the verified source.

Reproduce from the repository root:

```sh
./gradlew --no-daemon --no-configuration-cache :regelsuche-learning:test \
  --tests 'de.regelsuche.mining.TypedPatternGeneralizerTest'
./gradlew --no-daemon --no-configuration-cache :regelsuche-learning:test
```

Full repository CI remains a separate merge gate. Automatic promotion, inferred
general premises, broader program-output matching and the separately frozen
#1026 learned-transfer experiment are NOT completed by this prerequisite.
