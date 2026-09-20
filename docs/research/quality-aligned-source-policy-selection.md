# Quality-aligned source policy selection

A shortcut is not correctly priced by exploring its entire remaining frontier when the intended request is to stop at sufficient verified quality. This change uses the **existing** `TypedSourcePolicySelection` trial loop and `TypedSourceOnlySearch` engine with an explicit quality goal for both TRAIN and frozen evaluation. It does not introduce another learner, search engine, domain-specific solver or desired output expression.

## API and execution contract

```java
var selected = TypedSourcePolicySelection.trainUntil(
    trainingTasks, profiles, objective, maximumOutputScore,
    SearchContinuationContract.PATH_SENSITIVE);
var result = selected.evaluate(unseenSourceOnlyProblem, objective);
```

Use the same objective semantics during training and evaluation. Choose `DECLARED_STATE_LOCAL` only when providers, verification, valuation and the objective satisfy its existing continuation-locality contract. The API does not infer that promise. Source-only FAST mode and distinct bounded TRAIN tasks are still required; frozen evaluation still excludes exact training sources and rejects complete-reference mode. Exact-source exclusion is not an independent mathematical-family holdout.

`QualityGoal` retains the maximum score and continuation contract. Every trial uses `searchUntil`, including its online objective inspections, path materialization and independent final replay. Frozen evaluation uses the same retained control. A core `QUALITY_REACHED` outcome **without** `withinBudget()` after final replay is not a successful quality observation.

No old behavior is silently replaced. `TypedPolicySelection.trainSourceOnly(...)`, the historical three-argument `Frozen` constructor, full-continuation execution and its v1 JSON remain available. The opt-in mode emits `regelsuche.typed-source-policy-selection/v2-quality` with an explicit `qualityGoal` object. Serialization remains an audit description, not an independent loader for arbitrary providers or objective callbacks; it grants no mathematical authority.

## Selection and accounting

Profiles are ordered by budget violations first, then unmet quality goals, summed positive remaining objective deficit, and total paid trial work. Equal keys retain declared profile order. A successful task cannot compensate for another task's budget violation. Once all goals are met, unnecessary extra simplification cannot outrank a cheaper sufficient result. If no profile can meet a goal, the deficit criterion preserves the best attainable output rather than simply choosing an empty cheap profile. Deficits use `BigInteger` because a difference between two valid long scores can overflow a long.

All trials, including unsuccessful and over-budget ones, remain in `Frozen.trials()` and `trainingWork()`. Rule formation, schema proof, restoration and the selector's own setup/serialization are additional lifecycle work; the search counters are not a full CPU-instruction, memory or elapsed-time measure. Existing wall/process-CPU/request-thread allocation diagnostics retain their original scope. No latency or memory win is implied by a logical-work reduction.

## Regression protocol

The RED checkpoint `0553ae7a1c80901d518e8783ffbc9240e32c8bcb` added seven tests and a compile-only `trainUntil` seam delegating to the unchanged old selector. Hosted Java 25 run `35514004646`, job `106086741406`, actually compiled and executed them. It reported 471 passing search tests and 912 learning tests with exactly four new assertion failures, no errors/skips:

- The selector chose slower immediate work because it charged the quick profile's unused later provider.
- It preferred unrequested extra simplification over cheaper sufficient quality.
- It did not retain quality-control metadata.
- The integration with actually learned and restored schemas selected BASE rather than the cheaper quality-controlled learned profile.

Artifact `10606640671` was downloaded and its SHA256 verified as `20361d7d6ebc32fd79beb21c6b6e3449a5dc9841dea8f1e7e596f93873cfa31a` before inspecting JUnit XML. The initial failure is a behavioral RED, not a compilation error. Two additional preservation guards cover mixed success/overrun profiles and exact v1 JSON; an extreme negative threshold checks deficit arithmetic.

`CheckedSchemaQualitySelectionTest` forms rules through the real learner, restores/reproves the schema model, prices BASE and LEARNED using the same online control, and evaluates an exact-source-disjoint substitution. It separately reports formation/restore, all selection trials and final evaluation work. This is public development integration, **not** a frozen independent performance benchmark and not a full policy persistence/restore test. The existing v1/v2 protocols and negative results are unchanged.

Further work remains in lazy schema generation, object-native state transport and an independently registered full-lifecycle/equal-total-budget comparison. A successful selection test alone does not establish that learning repays its acquisition cost.
