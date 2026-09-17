# Compiled AST continuations

This is the compiled-continuation part of #1010, built on #1012's typed
primitive transport. It is opt-in; the historical string compiler, interpreter,
search defaults and policy identities are not changed.

## Existing compiler, typed execution

```java
var compiled = new CompiledLinearRewriteEngine(existingProgram, 128);
var typed = compiled.compileAst();
var batch = typed.transformMeasured(sourceAst);
var candidate = batch.candidates().getFirst(); // caller-owned selection
List<Expr> completeStates = candidate.states();
var checked = typed.replay(sourceAst, candidate);
```

`existingProgram` can be the real `program()` produced by
`EvolutionRewriteProgramCompiler`. The same flat Source/Sequence plan is
compiled, not a replacement hand-coded tactic. Each source must use
`PreparedAstRewriteTransformationEngine`; unsupported sources are rejected
before any stage runs. Its original rule instances, per-step growth limit and
returned-candidate limit are retained by `astTransport()`.

`CompiledAstRewriteProgram`, revision
`regelsuche.compiled-linear-rewrite/ast-v1`, passes each actual producer AST to
the next source. It retains grouped ADD/MUL trees, ordered function arguments,
atomic exact rational numbers and scoped symbol identities. There is no
format/reparse boundary in this executor.

Every candidate contains the program ID, ordered source-node IDs and all
primitive steps, including their source/target ASTs, metadata, attribution and
side conditions. `states()` exposes the complete immutable state sequence.
`assumptions()` supplies the normalized union while per-step conditions remain
available. These IDs are not a persisted content hash or occurrence certificate.

## Ordering, bounds and replay

Typed execution uses deterministic native source emission order, not the old
sort on formatted strings. This deliberate difference is opt-in and separately
versioned. The old entry point remains an executable comparison control.

Only complete paths are returned. Different histories that converge on an
equal endpoint remain distinct: the intermediate states can be important to
shared binding checks. Exact duplicate full records are deduplicated in first
emission order. One through eight sources are supported, with a compiled stage
candidate bound from 1 through 128. An exceeded compiled candidate or AST
structural limit throws; it does not return a truncated or incomplete path.
Per-source candidate filtering retains the source's own existing limit.

Replay first checks the program ID, source-node sequence and exact input AST.
It then regenerates the entire bounded program and requires full candidate
record equality. Changed intermediate states, targets, rule metadata and side
conditions cannot be accepted just because a public record was constructed.
Replay returns the regeneration metrics separately. It establishes reproducible
execution relative to those rules, not the mathematical validity of an
arbitrary custom rule or discharge of retained assumptions.

## Work accounting

The existing mechanical ledger records one pipeline call, actual primitive
source invocations, emitted primitive candidates, path compositions and removed
full-record duplicates. `candidateWork` separately records emitted primitive
work. A failed tail still charges the sources already called and the candidates
already emitted; it returns no partial candidate.

For the three-source diagnostic example, three source invocations, three emitted
candidates and two compositions plus the pipeline call total nine mechanical
units. This does **not** include shared-binding validation, replay, exact audits,
AST matching/traversal internals, parsing elsewhere, hashing, allocation, equality
or numeric bit complexity. It is not comparable to the earlier complete-dispatch
costs. No elapsed-time, CPU, allocation or learned speedup is claimed. A global
search work allowance is not wired into this executor yet; source/stage/AST
limits are not arbitrary-rule CPU quotas.

## Verification

Three producer-preservation tests failed against a compiling scaffold that
used the historical text boundary: grouped addition, an atomic rational value
and ordered function arguments. All three pass with direct typed execution.
Ten boundary tests additionally cover convergent histories, failed tails, exact
mechanical counts, source growth/candidate limits, unsupported engines, changed
conditions/metadata, immutable paths, scoped aliases and legacy interpreter
parity. A targeted mutation that collapses histories by endpoint fails the
convergent-history test; removing the overlay restores all 17 new tests.

Four integration tests use the actual evolution compiler and inventoried
`difference-product`, `square-product`, `cancel-addend` sequence. Shared bindings
are learned from two actual compiled training runs. A grouped input
`((a+(b+c))+y)*((a+(b+c))-y)+y*y+107` reaches `(a+(b+c))^2+107` with every
intermediate AST retained. Rational and scoped inputs also transfer. Real
mathematically valid paths that rewrite a different bound residual are still
rejected by the unchanged binding model. Polynomial equivalence is checked
separately, never substituted for structural equality.

Local Java-25 verification compiled changed core/search and the existing
learning sources into fresh directories. A run of 1,165 available core/search
tests passed; 72 focused compiler, replay and binding tests also passed. The
sets overlap and must not be added. The 1,165-run used the recovered #1009
workspace plus this change and the #1012 assumption-review tests; it did not
include #1012's other 18 tests, which remain on the inherited PR base. A complete
local learning-module run reached its invocation timeout and is not counted as
successful. Current-head ordinary CI and review results belong in the PR
conversation; these local checks are not a full Maven/product qualification.

## Remaining #1010 work

General search states, live dispatcher observation collection and its automatic
choice of the typed continuation backend, persisted structural replay and
end-to-end budget accounting still need integration. The public typed compiler
and real model integration tests do not silently migrate those consumers.
#1010 remains open. No frozen study, proof requirement, acceptance threshold,
repository protection, dependency or default policy is changed.
