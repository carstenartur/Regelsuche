# Typed AST transport for rewrite execution

`AstRewriteTransport` is the explicit typed primitive boundary introduced for
#1010. It preserves producer `Expr` values instead of formatting and reparsing
them. The historical string execution remains available as an independent
control; typed execution is opt-in.

## Primitive transport

```java
var transport = new AstRewriteTransport(rules, maximumGrowth, maximumCandidates);
var candidates = transport.generate(sourceAst);
var chosen = candidates.getFirst();
Expr checked = transport.replay(sourceAst, List.of(chosen));
```

A step retains the exact source and target AST, rule ID, rewrite kind,
growth/cost metadata, equivalence-by-construction flag, side conditions and
attribution. Ordered ADD/MUL grouping, function arguments, exact rational
`NumberExpr` leaves and scoped symbol IDs therefore survive. Structural equality
is not replaced with formatted-text equality.

Generation reuses the prepared engine traversal and its actual rule instances.
Original per-step growth and candidate limits remain active. Unsupported or
oversized structures fail explicitly; no partial trace is returned.

Replay regenerates each primitive under the receiving engine's rules and bounds
and requires the complete retained record. Equivalent but structurally different
sources or targets, changed metadata and changed per-step assumptions are
rejected. Replay establishes reproducibility under the supplied rules; it does
not prove arbitrary custom rules or discharge retained assumptions.

## Compiled linear continuations

The same typed boundary is available for an existing flat Source/Sequence
program:

```java
var typed = new CompiledLinearRewriteEngine(existingProgram, 128).compileAst();
var batch = typed.transformMeasured(sourceAst);
var candidate = batch.candidates().getFirst();
List<Expr> states = candidate.states();
var replay = typed.replay(sourceAst, candidate);
```

`existingProgram` may be the real plan emitted by
`EvolutionRewriteProgramCompiler`; no replacement tactic is introduced. All
sources must use `PreparedAstRewriteTransformationEngine`, otherwise compilation
fails before any source executes. The producer AST from one source is passed
directly to the next.

A compiled candidate retains program ID, ordered source-node IDs and every
primitive step. Different histories that converge on the same endpoint remain
distinct because intermediate states can matter to learned binding checks; only
identical full records are deduplicated. Replay checks the exact input and
program/source identities, regenerates the complete bounded program and requires
full candidate equality.

Typed compiled execution is separately versioned as
`regelsuche.compiled-linear-rewrite/ast-v1`. Native source emission order is
used; the historical formatted-string compiler and its ordering remain unchanged.
The compiled pipeline supports one through eight prepared sources and a 1–128
candidate bound. Per-source limits remain in force; stage overflow throws rather
than truncating a path.

## Work and authority boundary

The compiled mechanical ledger records the pipeline call, actual source calls,
emitted primitive candidates, compositions and exact duplicate removals;
`candidateWork` retains emitted primitive work. It does not claim AST traversal,
matcher, allocation, equality, numeric bit, CPU or elapsed-time cost. Global
search-budget integration remains separate work.

Typed generation and replay are execution evidence, not proof authority.
Existing mathematical audits, assumption handling and learned-policy admission
remain independent. Publicly constructible records do not authorize a rewrite.

## Verification contract

Regression coverage exercises grouping, rational leaves, function arguments,
scoped identity, nonempty side conditions, metadata tampering, candidate/growth
bounds, unsupported compiled sources, failed tails, convergent histories,
immutable paths and unchanged legacy execution. The learning integration uses
the actual evolution compiler and its inventoried difference-product,
square-product and cancel-addend sequence. Correct grouped/rational/scoped
trajectories satisfy the shared learned binding while mathematically valid paths
at the wrong residual remain rejected.

Commit-specific CI counts and mutation experiments live in the corresponding PR
conversation rather than this stable contract.

## Remaining #1010 work

General search states, live dispatcher observation/backend selection, persisted
structural replay and end-to-end work budgets still require typed integration.
No default search path, saved policy identity, frozen study, proof requirement or
acceptance threshold is changed by this API.
