# Learned knowledge efficiency v2

## Objective and boundary

Implement the 2026-09-20 user commission in the existing Regelsuche learner,
typed move frontier, staged picker and evaluation machinery. A learned rule must
save actual total work or increase verified result quality at a shared budget.
Threefold end-to-end improvement is an aspiration, not a predeclared outcome.
No second search engine, benchmark-specific hidden target, weakened gate, or
modification of frozen experiments is allowed. Java 25 and existing dependencies
remain unchanged. Public development cases are never called independent transfer.

## Baseline audit

* Main: `81ea9ac7`; #1025 merged as `4ac122f3` after its domain-premise correction.
* #1042: `7c204e1c`, source-only evaluation is connected; learned query work is
  12,533 versus 8,950 BASE per 24 tasks at identical quality, plus training.
* #1043: `29db5761`, deterministic candidate reuse is bounded and opt-in;
  four documented integration fixtures show negative net logical-work savings.
* #1039 and #1041 supply premise replay hardening and ordered rule-shape indexing.
  Reuse these changes. Their own complete CI passed.
* #1042 full CI has a genuine workflow-count integration violation. Fold the
  dedicated comparison into the existing CI, preserving its evidence and gates.
  The separate JMH result is inconclusive; preserve the threshold and investigate.

## Design

1. **Schema formation and authority.** Retain `TraceRewriteStrategyLearner` as
   the producer of successful paths. Extract corresponding changed subtrees
   from actual selected paths, then use `TypedPatternGeneralizer`. A syntactic
   hypothesis is not authority. A bounded trusted checker proves the symbolic
   polynomial statement with distinct indeterminates and explicit supported
   scalar domain; unsupported hypotheses remain traces. Immutable checked
   schemas carry statement, assumptions, checker revision and inventory binding.
   Loading rechecks semantics, never merely a digest. Applications match repeated
   variables consistently, check domain/premises and construct the target directly.
   Independent application verification checks the occurrence, substitution and
   target without rerunning discovery or enumerating primitive paths.
2. **Selection and utility.** An indexed learned provider selects a bounded
   relevant set using structural features before expensive matching. Training
   compares source-only continuation quality and complete measured effort against
   the same improved primitive foundation. Negative/failed attempts are retained.
   A conservative utility estimate drives the existing staged picker. Heuristic
   suppression is only an explicit budgeted mode; complete reference mode must
   retain alternatives. Primitive opportunities and bounded exploration survive.
3. **State and reuse.** Preserve assumptions, previous rule, depth, capabilities
   and remaining resources in state identity. Measure serialization and repeated
   decoding before adding local bounded reuse. Never cache proof authorization.
4. **Joint plans.** Add immutable typed shared computation representation within
   Regelsuche, using the existing AST/frontier. Structural scoring deduplicates
   shared operations during search and accounts for output bindings, liveness and
   retained storage. A domain supplies modular power/product semantics and premises.
   Search laws are generic and receive no desired optimized expression. Prepare a
   verified immutable plan once and execute it with input-domain checks each time.
   Execution backends remain optional interfaces.
5. **Evaluation.** Extend the existing external polynomial comparison package
   with a separate versioned protocol. Keep primitive, fixed expert and learned
   profiles on the same foundation. Report wall time, process CPU, allocations,
   memory, logical work, output quality and independent audit separately. Pay
   formation, selection, load/recheck, setup and execution costs. Persist then
   restore in a fresh process. Actually run sequences of different lengths.

## Evaluation registration before v2 results

Development/regression: the existing polynomial cancellation, Pocklington and
affine modular-power examples. New evaluation family: nested polynomial
substitution into telescoping differences of squares, with unrelated near misses
and already reduced controls. These are a declared family-transfer probe, not a
blinded external corpus. Freeze concrete source bytes before the first v2 run;
retain all runs and losses. Do not consult or change protected FINAL TEST assets.
The strong fixed external control remains the paid SymPy simplify/factor/cancel
portfolio, and modular execution also compares direct BigInteger evaluation.

## Delivery and acceptance

Make separable commits/PRs for integration, schema/selection and shared plans,
with a combined integration branch for end-to-end evidence. Tests cover false
bindings, missing premises, stale semantic versions, tampering, budgets, state
identity and reuse boundaries. Completion of an API or tests alone is insufficient.
If timing improvement is absent, report the remaining measured bottleneck and
mark the performance acceptance unmet rather than changing comparisons.
