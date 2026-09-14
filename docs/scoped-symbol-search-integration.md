# Scoped symbols: search integration and acceptance boundary

## Objectives

Regelsuche does not have to beat a conventional algebra system on every task.
Elapsed time, search work, reachable results and retained derivations are separate
objectives. Exact algorithms remain available alongside exploratory search and
learned transformations. A larger search or a richer result must not be made to
look faster by dropping evidence, checks or supported moves.

For learned rules the relevant claim is conditional: on independently selected
related tasks, useful validated knowledge may reduce search work or reach results
that the same search budget could not previously reach. Learning, validation,
matching and replay costs belong in the accounting. A short macro invocation is
not automatically a short primitive derivation, a shortest path or a novel theorem.

## Implemented prerequisite after #1005

`SymbolId` transport strings are identifiers, not the mathematical complexity of
variables. Two defects previously affected their use in search:

* Quadratic input preparation inserted multiplication inside a digit-to-letter
  transition in an identifier, including `x2y` and a scoped UUID. It now consumes
  whole identifier tokens before inserting coefficient shorthand multiplication.
  `2x2y` still becomes `2*x2y`; `x2y` remains one variable.
* Text-based scoring counted UUID and ordinal characters, and the symmetry
  uniformity heuristic compared their physical lengths. Scoped variable tokens
  now contribute one unit to the relevant heuristic components. Ordinary names,
  numbers and function-name roles retain their existing text costs. Malformed
  reserved variable IDs are rejected rather than discounted.

`ExpressionScorer.SCOPED_SYMBOL_METRIC_REVISION` identifies this contract as
`regelsuche.scoped-symbol-text-cost/v1`. The legacy `stringLength` score component
is therefore an identity-independent heuristic length for scoped variables, not
serialized character/byte size or memory use. Existing scores for ordinary inputs
remain characterized. No historical benchmark data or replay artifact is rewritten
or retrospectively re-scored, and no benchmark acceptance baseline is advanced.

Pattern recognition still uses the complete identities: `(a+3)*(a-3)` must not
receive the same pattern bonus as `(a+3)*(b-3)` when `a` and `b` are distinct IDs.
The AST, source occurrence ranges, value keys and symbolic document codec are not
changed. Equal heuristic cost is not mathematical equality or proof.

## Verification of this prerequisite

The first 17 behavior cases ran against unchanged production: 16 failed and one
passed. Fresh supplementary compilation then passed 84 JUnit cases across the new
quadratic/scoring tests and existing symbol, codec, e-graph, didactic and goal-cost
tests. Of these, 27 cases are new. None of these 84 cases was skipped.

Five temporary incorrect variants were rejected by executable JUnit cases:
identifier splitting (10 failures), counting physical transport size (9), ordinal
width in symmetry (1), discounting malformed reserved IDs (4), and merging distinct
IDs for pattern bonuses (1). All changed production files were restored before a
fresh successful 84-case run. These controls are not independent review approval.

Local execution used OpenJDK 21.0.11 and the actual retained dependencies from
CI artifact 10322233313 (run 34769665294, SHA-256
`e5d162d5a04a79c65649917f3483856d727a7e81f2cd4d3050d005501e736bd3`).
The reconstructed original core and e-graph trees match main
`c4772cb86b30659f350936d23da1cc04af4d470c`; the search source tree matches
`0c852e4afc634ca0529a5e267232a65ba5432e72` on that main revision.

This was a focused source compilation, not a full Java 25 Gradle/Maven/Docker
build. Attempts to extend the local run to the full runtime/service path failed
at compilation on unchanged Java 25 `ScopedValue` API usage and missing local
GraalVM dependencies. Those attempts are not successful tests. No committed JDK
downgrade, stub, test exclusion or relaxed CI gate is added. Full exact-head CI
and review are required before merging.

## Remaining ordered implementation work

1. Qualify #1004's current source and resolve its review findings. Its domain
   validation, plugin recovery and release verifier are separate from learning.
   The main JMH failure was an INCONCLUSIVE measurement, not a missing benchmark
   list. Retained precision-study input replay and separately versioned adoption
   remain governed by #981; do not rerun until green or raise a threshold.
2. After the prerequisite above is qualified, extend the shared structured
   CLI/HTTP boundary with explicit symbol documents. Source text, symbol bindings,
   display labels and exact numeric occurrence evidence must remain distinct.
   Requests, results and replay must bind the same symbol IDs. Keep old protocol
   versions explicit rather than silently reinterpreting retained evidence.
3. Make learned dispatch distinguish matching occurrences and bindings across
   steps instead of summarizing context only by rule IDs. Preserve primitive
   alternatives, budget charges and explicit incomplete outcomes. A proposed
   learned path must replay with its actual bindings; a heuristic is not a proof.
4. Compare frozen implementations on an independently fixed public development
   corpus with the same admissible rules, domains, output requirements and budgets.
   Record no-knowledge and learned-knowledge runs, native/exact baselines and
   appropriate SymPy operations separately. Report solved cases, verified replay,
   work, elapsed time, allocations and learning/validation cost without collapsing
   them into a single winner. Include negative transfer and amortization results.

The prerequisite in this change does not implement steps 2 or 3 or execute step 4.
There is no protected FINAL TEST, default-policy promotion, speedup claim or
universal search-completeness claim in this change.


## Producer-bound scoring provenance

`ExpressionScore.scoringRevision` records the producer contract when the score is
created. The built-in scorer uses `regelsuche.expression-score/v2`, covering both
whole-identifier quadratic recognition and scoped-symbol heuristic costs. The
legacy numeric constructor deliberately records `unspecified`; historical or
custom numbers are not relabelled as current built-in scores.

Telemetry retains that contract, trajectory v3 carries it through split copies
and JSONL, and both built-in trainers preflight TRAIN records before consuming
score deltas. The experience repository preflights complete batches before
mutation. Old schemas and unknown/custom score contracts are not admitted to the
built-in training contract. Named custom scores remain usable in ordinary search
and export; this change does not discard their numerical values. Policy feature
versions distinguish old models from models learned with the new semantics.

State/work replay formats are v2. Artifact size/digest, schema, producer revision
and nested score revisions are checked before a supplied source reconstruction
function executes. Explicit overloads allow a caller to agree a named custom
contract; the default contract is the current built-in scorer. Serialized data
never becomes proof authority. Import/export retain each score's own revision;
missing historical metadata stays `unspecified`. Old artifacts and frozen study
inputs are not rewritten, nor are performance thresholds or work budgets changed.
