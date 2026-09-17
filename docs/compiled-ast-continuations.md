# Compiled AST continuations

This is the compiled-continuation slice of #1010, built on the typed primitive
transport from #1012. It is opt-in: historical string execution, search defaults,
policy identities, proof requirements and acceptance thresholds are unchanged.

## Existing compiler, typed execution

```java
var typed = new CompiledLinearRewriteEngine(existingProgram, 128).compileAst();
var batch = typed.transformMeasured(sourceAst);
var candidate = batch.candidates().getFirst(); // caller-owned selection
List<Expr> states = candidate.states();
var checked = typed.replay(sourceAst, candidate);
```

`existingProgram` may be the real flat Source/Sequence plan produced by
`EvolutionRewriteProgramCompiler`; this is not a replacement tactic. Every
source must use `PreparedAstRewriteTransformationEngine`, otherwise compilation
fails before execution. Its rule instances and growth/candidate limits are
retained by `astTransport()`.

`CompiledAstRewriteProgram` (`regelsuche.compiled-linear-rewrite/ast-v1`)
passes producer ASTs directly between sources. Grouped ADD/MUL trees, ordered
function arguments, atomic exact rationals and scoped symbol IDs therefore stay
intact. Candidates retain the program ID, source-node IDs and every primitive
step with source/target AST, metadata, attribution and side conditions.
`states()` returns the immutable state sequence; `assumptions()` is the
normalized union while per-step assumptions remain available.

## Ordering, bounds and replay

Typed execution uses deterministic native source emission order, separately
versioned from the historical formatted-string ordering. Different histories
with the same endpoint remain distinct; only identical full records are
removed, preserving first emission order. The pipeline supports one through
eight sources and 1–128 stage candidates. Source limits remain in force. An AST
or compiled-stage overflow throws instead of returning an incomplete path.

Replay checks program ID, source-node sequence and exact input AST, regenerates
the bounded program, then requires full candidate equality. Changed
intermediate ASTs, targets, metadata or per-step assumptions are rejected.
Replay establishes reproducible execution under the supplied rules; it neither
proves arbitrary rules nor discharges their assumptions.

## Work accounting

The mechanical ledger records the pipeline call, primitive source calls,
emitted primitive candidates, compositions and exact duplicate removals;
`candidateWork` retains emitted primitive work. Failed tails still charge work
already performed and expose no partial candidate. The ledger deliberately does
not claim AST traversal/matcher, allocation, equality, numeric bit, CPU or
elapsed-time costs. Global search-budget integration remains future work.

## Verification boundary

Regression tests cover the original formatter losses (grouping, rational leaves
and function arguments), convergent histories, failed tails, limits, unsupported
sources, metadata/assumption changes, immutable paths, scoped IDs and legacy
control behavior. Integration tests use the actual evolution compiler and the
inventoried difference-product/square-product/cancel-addend sequence; learned
bindings accept the retained grouped/rational/scoped trajectory and reject a
valid primitive path acting on the wrong bound residual. Exact polynomial
comparison is an independent check, never a substitute for structural equality.

Detailed commit-specific test counts, CI runs and mutation probes are kept in
PR #1017 rather than duplicated here; this document states the stable contract.

## Remaining #1010 work

General typed search states, live dispatcher observation/backend selection,
persisted replay and end-to-end work budgets remain separate integration work.
The typed compiler does not silently migrate those consumers. #1010 stays open.
