# Typed learning and dispatch

## Goal and scope

Connect the existing typed frontier to learned ordering and source-bound compiled
programs. Preserve producer ASTs, scope, exact leaves, all intermediate states,
primitive expansion and independent regeneration. This implements the next
integration slice of #1021 and the TRAIN-selection prerequisite for #874.

The historical String paths, frozen studies and production gates remain controls.
No universal superiority, wall-clock speedup or learned intermediate-goal discovery
is implied by an integration regression.

## Design

1. Keep `MoveSearch` as the sole scheduling engine and ledger. Permit only explicitly
   typed policies beside inventory order at the typed boundary. Expose decoded
   events so rejected and admitted intermediate states remain inspectable.
2. Extract structural context directly from `Expr`. Use a distinct typed context
   namespace when collecting TRAIN history and scoring later applications; never
   parse tagged JSON as an algebra expression or conflate historical features.
3. Adapt `CompiledAstRewriteProgram` to a typed provider. Retain every source ID and
   intermediate state in application identity and primitive provenance. Verify by
   regenerating the registered program and comparing the entire transformation.
   A decoded persisted history is only a proposal. Matching, program execution and
   regeneration share the existing search ledger, including atomic overruns.
4. Select among declared history weight profiles using actual bounded typed TRAIN
   runs. Prefer more solved training tasks, then less total charged work, then the
   declared profile order. Freeze the winning profile and all trial evidence.
   Record training cost separately; evaluation cannot update the frozen model.
5. Make this usable from the existing trace learner through `FrozenStrategy.typedMoves()`:
   compile admitted TRAIN traces to the existing program representation, retain
   their utility evidence, and register typed primitive/learned providers with one
   verifier dispatcher. Formation cost remains visible beside policy-training cost.

## Qualification

Run real primitive and compiled searches with grouped ADD/MUL, scoped symbols,
exact rational leaves and function arguments. Cover wrong source/occurrence,
forged metadata, missing assumptions, persisted proposal replay, TRAIN-only
updates, legacy-policy rejection and work exhaustion during regeneration.
Compare ranked and inventory-order runs on declared development examples; report
these as integration evidence, not an independently blinded performance study.

## Entry points

`TraceRewriteStrategyLearner.FrozenStrategy.typedMoves()` exposes the primitive
control, admitted learned programs and their source-bound verifier dispatcher.
Use `primitiveProviders()` for the control or `providers()` for the union in a
`TypedMoveSearch.Problem`. The caller supplies the same typed source, target and
budget to both. The underlying `MoveSearch` still rejects production execution.

`RuleHistoryMemory.observe(typedResult, TRAIN, savings)` collects the typed events;
`HistoryMovePolicy.typed(snapshot, weights)` consumes the frozen tables. The
historical string APIs retain their original context namespace and behavior.

`TypedPolicySelection.train(snapshot, tasks, profiles)` runs the declared profiles
under identical task budgets and retains all outcomes and charged work. Its
`FrozenPolicy.evaluate(problem)` accepts only frozen evaluation and excludes exact
TRAIN sources. It does not enforce mathematical family independence or account
for all CPU work. `formationWork()`, history `measuredWork()` and selected-model
`trainingWork()` remain separately visible; none is hidden in an application edge.

## Review corrections

Primitive replay dispatches to the exact registered single-rule transport that
generated the proposal. A regression with forty square products and a separate
zero-removal site prevents an unrelated rule from filling the verifier's cap.

Compiled candidate-limit exhaustion retains the already incurred mechanical and
primitive work. Live generation returns an incomplete empty batch; replay returns
an unsuccessful verification with its actual receipt. Both consume the existing
ledger. The regression imports a history from a larger envelope into a smaller
one and verifies the resulting inconclusive run and charged regeneration work.
Other invalid structural inputs still fail explicitly; no complete relation or
general CPU quota is claimed.

## Validation

On 2026-09-19, Java 25 Maven verification with
`mvn -B -ntp -pl regelsuche-learning -am test` passed 2,489 tests in 430 suites
across core, egraph, search, validation, math-algorithms, solver-ir and learning.
There were no failures, errors or skipped tests. The branch adds fourteen tests
covering typed history, policy selection, compiled replay and the actual learner.
Both material review findings were reproduced by failing regressions before repair.

The corresponding Gradle search and learning suites passed 1,266 tests in 237
suites, also with no failures, errors or skipped tests. The final run used a
fresh build after bytecode inspection identified a stale pre-fix class:

```sh
./gradlew :regelsuche-search:clean :regelsuche-learning:clean \
  :regelsuche-search:test :regelsuche-learning:test \
  --no-build-cache --no-configuration-cache -Dorg.gradle.vfs.watch=false
```

Full hosted product, quality and JMH qualification remains required before merge.
The root `aiKnowledgeCheck` attempt stopped because the extractor was disabled;
it is not recorded as a passed gate.

## Remaining research

Data-driven weight selection is a bounded policy selector, not a neural action
model. Learning new structural selectors, intermediate goals and obligation
topologies, broader cumulative curricula, and external matched-budget baselines
remain follow-on work under #874 and #235. Full product qualification remains a
separate CI gate.
