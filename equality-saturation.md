# Equality saturation: implemented contracts

The `EQUALITY_SATURATION` search profile stores equivalent terms in an
`EGraph`, applies admissible rewrite rules, rebuilds congruence closure,
and extracts an expression. Sharing equivalent terms reduces duplicate
path exploration; it does **not** remove combinatorial matching or memory
growth. No measured speedup or completeness guarantee follows from using
this representation.

## Extraction objectives

`EGraph.extract(root, costOfNode)` and `EqualitySaturation.saturate(...)`
accept **additive, non-negative per-node integer costs**. Each tree has
cost `nodeCost + sum(childCosts)`. The function must be pure and stable
throughout the run. Negative costs are rejected, since negative cycles
would invalidate finite extraction. `Integer.MAX_VALUE` denotes an
unavailable representative; costs at or above it cannot be extracted.
The result minimizes this additive objective over finite extractable
representatives in the graph, within those integer limits. Saturation validates
nonnegative node costs before rewriting but does not require a finite initial
root: rewrites may introduce an extractable alternative. Final extraction still
requires a finite root.

The search strategy dispatches explicitly:

| Requested `SearchProblem.costModel()` | Actual behavior |
| --- | --- |
| Absent, or `StructuralCostModel.OPERATOR_COUNT` | E-graph saturation, then exact operator-count extraction (operators/functions cost 1; numbers/variables cost 0). |
| `DEPTH`, `FACTORED_FORM`, `SYMMETRY`, teaching, numerical stability, didactic or any custom model | Best-first search using the requested model, with an explicit fallback report. |

A custom model sharing a built-in model's ID still takes the fallback.
The strategy does not infer additivity from an ID. Best-first fallback is
budgeted search, not an exact global minimizer. In particular, this API
cannot switch a saturated graph to arbitrary `TransformationGoal` models
and promise exact extraction without searching again.

`EqualitySaturationStrategy.lastExtractionSelection()` returns
`requestedObjective`, `effectiveObjective`, `strategy`, `fallback`, and
`reason`. Unsupported objectives do not cause an opaque exception or
silently substitute operator count. `lastStats()` is null after fallback
or an unparseable input. All previous-run diagnostics and provenance are
cleared before each search.

## Native, concrete and conditional rules

Only rules declaring `isEquivalencePreservingByConstruction()` may merge
classes. Empirical validation is insufficient for this admission decision.
Rule implementers remain responsible for the truth of that declaration.

* Exact, unmodified `PatternRewriteRule` instances use native e-matching
  and direct e-node RHS instantiation. Repeated placeholders compare
  e-class identity. The matcher uses symbol/arity indices and memoization.
* Pattern subclasses and custom rules use their actual `matches`,
  `assumptions` and `apply` callbacks. They are tried against one concrete
  representative per root e-node, with children chosen under uniform node
  cost 1 independently of the optimization objective. This finds alternatives
  hidden by extracting just one root and allows rewrites whose source is
  excluded by the requested result objective. It does **not** enumerate the
  Cartesian product of child alternatives. A class without a finite structural
  representative is an ordinary miss, not a rule callback failure.
  The public `EGraph.representatives(id, cost)` API still honors its explicitly
  supplied cost function; saturation uses uniform cost for applicability and
  the caller's objective only for initial validation and final extraction.
* Non-exact recognition profiles, including associative/commutative
  matching, take the concrete path so their recognition semantics are
  honored. Unmodified base `PatternRewriteRule` instances use the detailed
  matcher result and instantiate the target from its returned bindings.
  A bounded, inconclusive match is retained as incomplete; it is not an
  ordinary miss. The matcher keeps its existing limits, including eight
  commutative operands and 10,000 backtracking branches per attempt.
  The native e-matcher does not implement complete AC matching.
* Every concrete application with unresolved assumptions is rejected.
  Conditional cancellation such as `(x*y)/x -> y` cannot become an
  unconditional equality. A rule that discharges the condition itself,
  such as cancellation of a known nonzero number, can still apply.
  The saturation API does not carry conditional equalities or discharge
  assumptions using the caller's context.

A fix-point is therefore relative to these admitted rules and this
representative policy; it is not complete mathematical normalization.
Subclasses and custom rules retain their boolean callback contract. Limits
hidden inside those callbacks cannot be inferred from a `false` return.

## Budgets and graph validity

`Config(maxIterations, maxNodes, useDirtyWorklist)` retains its existing
constructors and defaults. The node budget is checked between rules,
concrete representatives and native applications, and immediately before
every new e-node is allocated. A single oversized RHS cannot overrun the
cap. Reused nodes do not allocate additional capacity, but reaching the
cap ends the pass conservatively before further applications.

If an RHS is interrupted, its already inserted subterms may remain as
valid disconnected nodes. The unfinished RHS is never unioned with its
source. Pending successful unions are rebuilt even when a pass stops.
The insertion limit is restored when the run exits, so callers can reuse
the graph. An input graph already at or above the cap returns
`NODE_BUDGET`; the runner does not delete pre-existing nodes.

This is a node/iteration bound, not a wall-clock, total-byte, or callback
execution bound. A custom callback cannot be preempted. Native matching
still materializes a rule's match list, and one match can require substantial
work before the next application check. Concrete representative extraction
and witness construction also allocate ASTs outside the e-node count.

## Diagnostics and provenance

`EqualitySaturation.Result` now includes `diagnostics()` and
`provenance()`; the previous two-argument constructor remains available.
The strategy exposes the same records through `lastDiagnostics()` and
`lastProvenance()`.

Diagnostics distinguish ordinary misses, incomplete owned matches, unresolved
conditions, rejected non-equivalence rules and runtime failures. Stages identify
eligibility, extraction, matching, assumptions, application or insertion.
Aggregate outcome counts are retained for the whole run. At most 128
nontrivial events are retained; each contains rule ID, source class when
available, outcome, stage, exception type and a message truncated to 512
characters. Ordinary misses increment counts without filling the event
buffer. `droppedEvents()` makes truncation visible. Runtime exceptions from
rule callbacks are observable and do not masquerade as non-applicability.

`MATCH_INCONCLUSIVE` records an owned matcher limit at stage `MATCHES`, with
the original limit code in `message` and an empty `exceptionType`. Examples
include `COMMUTATIVE_OPERAND_LIMIT` and `COMMUTATIVE_BACKTRACKING_LIMIT`;
algebraic matcher limits use the same outcome. These expected incomplete
attempts are not runtime failures. Other representatives and rules continue
to run, so useful rewrites can still be admitted. The aggregate incomplete
count survives event truncation and later passes, even if a dirty worklist
does not revisit the original representative.

At a pass with no further progress, stop reasons have this priority:

| Observed during the run | Stop reason | `saturated` |
| --- | --- | --- |
| At least one rule runtime failure | `RULE_FAILURES` | `false` |
| No runtime failure, but at least one owned inconclusive match | `MATCHER_LIMIT` | `false` |
| Neither of the above | `FIX_POINT` | `true` |

Node and iteration budget stop reasons keep their precedence when reached;
matching and failure diagnostics remain available alongside them. A later
successful rewrite does not establish that every earlier incomplete match
was resolved. The new outcome and reason are additive enum values; diagnostic
event and result record components and compatibility constructors are unchanged.

Provenance retains at most 256 concrete `(ruleId, before, after)` local
witnesses, with `droppedWitnesses()` accounting for overflow. `Witness.replay`
checks the supplied rule's ID, construction guarantee, applicability,
empty assumptions and exact output. Native witnesses instantiate source
and target from the same concrete placeholder bindings. Those bindings use
finite syntax under uniform structural cost, independently of the requested
optimization objective. Witnesses are built only for new unions; unavailable
explanations and a full witness buffer do not prevent admitted rewrites.
`nativeWitnessExtractions()` counts actual native binding extraction attempts
(including failures), making this work observable without timing thresholds.
Replaying a witness trusts the supplied rule implementation; it is not an independent
proof of that rule's mathematics.

These witnesses do **not** connect the original input to the extracted
expression through every union and congruence step. Consequently, in the legacy API
`hasExtractionProof()` is false and `explanationStatus()` explicitly says
that a congruence/extraction proof is unavailable. Search states keep the
`equality-saturation` provenance marker for compatibility, but its synthetic
path edge is not marked construction-proven or executable. Consumers must
not treat it as a replayable rule derivation. The graph's admissible merges
and a fully replayable extraction proof are distinct guarantees.

## Metrics and verification

`SaturationStats` reports class/node counts, direct rewrite merges, passes,
rule application counts, stop reason, extracted form and matcher work
(`classesScanned`, `nodesScanned`, `candidateClassesSkipped`,
`matchesFound`, cache hits/misses). Direct rewrite merges do not count
additional congruence unions. Dirty worklists reduce repeated scans;
full-scan mode remains available for comparison.

Benchmark reports do not divide unions by applications to infer savings.
Those counters do not measure duplicated work avoided against a comparable
baseline. New reports and the workbench show savings as **not measured**;
JSON keeps `saturationSavings: null` and adds `saturationSavingsStatus:
"UNAVAILABLE"` plus an explanatory reason. The legacy Java primitive
constructor/accessor remains compatible but returns `NaN` and is deprecated;
Jackson and explicit JSON exporters expose null, never that sentinel.
Raw graph size and rule-application counters remain available. Historical
report files are unchanged.

Focused regression coverage lives in `EqualitySaturationMatchLimitTest`,
`EqualitySaturationContractTest`, `EqualitySaturationSoundnessTest`, `EqualitySaturationScalabilityTest`
and `EqualitySaturationStrategyTest`. Run the affected Maven reactor with:

```sh
./mvnw -pl regelsuche-egraph,regelsuche-search -am test
```

Historical benchmarks remain unchanged. Matcher/index microbenchmarks
and complete workloads answer different performance questions; no new
performance result is asserted by this contract repair.

## Explicit extraction certificates

`EqualitySaturation.saturateWithProof(input, costOfNode, ProofOptions)` owns a
fresh graph and can return independently checked input/output equality evidence.
`new EqualitySaturationStrategy(config, proofOptions)` opts search into this
proof-required mode; existing constructors retain all behavior above, including
BestFirst fallback. Explicit proof mode supports operator count only. Unsupported
objectives return the original state and `UNSUPPORTED_OBJECTIVE`.

`lastProofOutcome()` reports the actual outcome, including search symbol/render
work in capture work. `lastExtractionSelection().reason()` explains why proof
mode retains the original state when evidence or text is unavailable. A complete result exposes its
checker token through provenance. Unsupported rules, proof quotas, replay or
extraction failure preserve the original state. Output strings are constructed
with bounded `formatMeasured`; unrepresentable symbols or rendering limits also
preserve the root. An accepted diagnostic AST certificate can remain available
when search text cannot be returned. The synthetic edge remains non-executable
and non-construction-proven even with complete evidence.

See [Bounded extraction certificates](eqsat-extraction-certificates.md) for the
supported exact rule subset, receiving-side trust boundary, limits and example.
