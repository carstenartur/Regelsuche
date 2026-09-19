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

## Qualification

Run real primitive and compiled searches with grouped ADD/MUL, scoped symbols,
exact rational leaves and function arguments. Cover wrong source/occurrence,
forged metadata, missing assumptions, persisted proposal replay, TRAIN-only
updates, legacy-policy rejection and work exhaustion during regeneration.
Compare ranked and inventory-order runs on declared development examples; report
these as integration evidence, not an independently blinded performance study.

## Remaining research

Data-driven weight selection is a bounded policy selector, not a neural action
model. Learning new structural selectors, intermediate goals and obligation
topologies, broader cumulative curricula, and external matched-budget baselines
remain follow-on work under #874 and #235. Full product qualification remains a
separate CI gate.
