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
