# Work-replacing continuations and source-only quality control

This is a new opt-in search slice, not a reinterpretation of the frozen negative v1/v2 learning studies. It extends `MoveSearch` and its typed adapter; it adds neither a search engine nor a learner.

## Contract and APIs

`MoveSearch.search(problem, SearchContinuationContract.DECLARED_STATE_LOCAL)` and the corresponding `TypedMoveSearch` overload separate a continuation's expression, normalized assumptions and assessed capabilities from its resource label. A label dominates another only when search depth, primitive depth, exact-theory work and complexity debt are all no greater. Incomparable labels are retained. Only admitted states enter the index; a rejected cheap proposal cannot suppress a proved route. A newly admitted cheaper label also invalidates queued or suspended work belonging to the dominated label.

The caller must declare state locality for providers, mathematical admission, valuation and any objective. Previous-rule or depth-dependent eligibility, changing external inventories and non-monotone resource policies do not satisfy this contract. The declaration is not inferred from a learned-rule score and does not grant proof authority. `PATH_SENSITIVE` remains the default. Complete-reference mode rejects the opt-in contract rather than silently pruning its relation. A pruned run does not claim complete bounded closure.

`TypedSourceOnlySearch.searchUntil(problem, objective, maximumOutputScore, contract)` updates an incumbent while the same frontier runs and stops at the explicit quality threshold. No desired expression is supplied. Initial satisfaction needs no candidate generation, equal scores retain the first incumbent, and rejected proposals never supply an incumbent. `QUALITY_REACHED` is distinct from `TARGET_REACHED`: source-only `reached()` remains false. This is sufficient quality, not a claim of global optimality.

Online objective inspections and final path materialization are charged to search work. The wrapper independently replays the complete selected witness and adds that charge afterward. Callers must require `withinBudget()` as well as the requested output quality; a final replay overrun remains visible and is not a successful budget-respecting result. The old `search(problem, objective)` retains its post-search selection and accounting contract.

Witness prefixes are immutable linked paths. Appending a child no longer copies the complete ancestral list, and retained paths do not hold ancestor search nodes or their provider batches. A list is materialized when a witness is requested. Full attempted-edge evidence remains available.

## Development diagnostics

The initial focused local run used unchanged sources with Java 21 for the compilable search dependency closure. It ran 37 JUnit tests with zero failures, including the 21-test pristine baseline and the new dominance, Pareto-resource and quality tests. Behavioral RED runs first demonstrated both duplicate continuation expansion and unneeded provider calls after sufficient quality. Full project qualification still uses the repository's unchanged Java 25 CI.

| Diagnostic | Before | Opt-in behavior |
| --- | ---: | ---: |
| Synthetic graph: expanded states | 84 | 43 |
| Same graph: all charged search work | 1,167 | 772 |
| Actual `x + 0` rewrite with a synthetic costed late provider: charged work including selected replay | 190 | 23 |

The graph fixture retains the same reached expression set and pays all new index charges. The second fixture uses real typed matching and replay, but its late provider has an explicitly synthetic cost receipt. Neither table is a wall-time, memory, independently held-out or end-to-end learning result.

`CheckedSchemaWorkReplacementTest` separately exercises the actual learner, exact schema formation, serialized model restoration and the same primitive/schema inventory on compound substitutions. It records BASE quality-controlled work, learned quality-controlled work, continued learned-search work, and training/restore charges. Its fixed schema-first scheduling is not an oracle selecting a desired target and is not a newly learned utility policy. The integration test is included in normal Java 25 CI; no local Java 25 result is claimed by the initial focused run.

## Verification

```bash
./gradlew :regelsuche-search:test :regelsuche-learning:test --console=plain
```

The existing typed comparison authority already executes both complete module suites and retains their JUnit XML and diagnostic output. No workflow, quality floor, frozen result, case inventory or protected protocol was changed for this slice.

## Remaining acceptance work

A broad lifecycle advantage remains unproved. Lazy checked-schema generation, object-native frontier transport, stronger goal-conditioned learned selection, routine simplifier coverage and an independently registered equal-total-budget comparison are further work. These changes address two concrete reasons why a mathematically useful shortcut could fail to replace search; they do not by themselves establish superiority over another system.
