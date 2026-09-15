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

## Producer-bound scoring in persisted data

The persisted scoring contract is `ScoreRevision.CURRENT`, currently
`regelsuche.expression-score/v2`. It covers both whole-identifier quadratic
recognition and identity-independent scoped-variable costs. The narrower
`SCOPED_SYMBOL_METRIC_REVISION` source constant is not a persistence admission
check by itself.

`ExpressionScorer` attaches the contract when it produces an `ExpressionScore`.
Telemetry, trajectory collection, dataset splits and experience records retain
that producer identity. Fresh trajectory records use
`regelsuche.search-trajectory/v3`; their JSON schema requires `scoringRevision`.
The trainers and experience repository reject records that do not satisfy the
current scoring contract. Policy feature-schema identities change with these
score semantics as well.

Score-bearing search-state and work-search replay envelopes use their v2 schemas
and check their schema and expected scoring identity before invoking replay.
Custom scorers need an explicitly matching replay identity; the built-in identity
must not be inferred from arbitrary numbers or added retrospectively on export.

Transformation export/import preserves the actual revision, numeric components
and retained derivation data. Missing historical revisions remain `unspecified`;
exporting such scores does not re-score them or relabel them as current. A JSON
number, boolean, array, object or explicit null is not a valid revision string and is rejected on
import instead of being converted into one. Old trajectory records keep their
old schema when exported and are not silently admitted to current training.

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

### PR #1006 review follow-up verification

The follow-up patch was applied to the source archive retained by PR #1006 CI
run `34773747976`, repository-verification artifact `10323371799`. The unmodified
archive subtrees for `app`, core, search, e-graph, discovery, learning, Gradle,
scripts, configuration and documentation match the corresponding Git trees at
PR head `ae1f5c1670aa0c8e51a5223bb4812cea0c00f5a1`.

Four new malformed-revision import cases failed before the type check and passed
after it. The nine export/import provenance cases also cover built-in, custom,
obsolete and unspecified identities, historical numbers and preserved path data.
The expanded supplementary Java 21 run passed 198 cases with no failures or
skips. It compiles selected tests and their real source dependencies; it is not a
complete Java 25 build or integration qualification. The fresh integration
compilation still stops at three unchanged Java 25 `ScopedValue` call sites.
The updated trainer assertion for freshly produced v3 records therefore remains
unexecuted locally, as do the integration-level provenance cases.

Replaying the retained PR JMH measurements through the unchanged regression
verifier reproduces `INCONCLUSIVE`: `preparedTargetedSearch` has a point estimate
of 0.806647309 ms/op, a score error of 6.820478288 ms/op and an allowed maximum of
0.729862817 ms/op. This is neither a demonstrated regression nor passing evidence.
The retained main report has the same inconclusive benchmark; that does not
establish the cause or qualify the follow-up patch. The verifier self-tests pass.
No fresh JMH measurement, precision-study retry, baseline adjustment or threshold
relaxation is included. Exact-head Java 25 CI, admissible performance evidence and
review are still required before merge.

## Remaining ordered implementation work

1. #1004 and the separate cache-quota correction #1007 are merged. Complete
   exact-head qualification of the scoring integration in this PR. Retained
   precision-study input replay and separately versioned adoption remain governed
   by #981; do not rerun until green or raise a threshold.
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


## Persisted native and historical score boundaries

Native current-state observations require a textual current scoring revision
before artifact admission. All states and retained events in an artifact must
agree on that revision; the event producer is copied from the actual SearchEvent,
not inferred from its numeric score. Missing or obsolete v2 revisions are not
executable evidence.

Historical state-v1 native artifacts without score-revision fields remain
canonical read-only observations through both the artifact store and browser.
Their absent event metadata stays absent when serialized; they are never labelled
as current and `Artifact.replay()` rejects them before starting a new search.
Mixed legacy/current traces are rejected. An incomplete pre-release v2 trace
without its required event provenance is not silently promoted to the corrected
current format.

Trajectory-v1 JSON omits both scoringRevision and the v2-only transformation
descriptor. Trajectory-v2 omits scoringRevision. Neither legacy format may encode
an asserted current score producer or enter current-model training. Ordinary
policy feature identities bind the scoring revision, as descriptor policies do.

Autopilot mining recomputes its current objective from each retained expression
rather than comparing an unversioned snapshot total with a new root score.
Snapshot numbers, paths and content commitments themselves remain unchanged.
This is current mining over retained observations, not a claim to reproduce an
old scoring algorithm or its historical decisions.
