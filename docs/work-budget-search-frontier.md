# Work-budget search with primitive and verified theory steps

`WorkBudgetBestFirstSearchStrategy` extends the existing deterministic,
syntax-targeted best-first frontier. It replaces the narrower
`PrimitiveWorkBestFirstSearchStrategy` name and migrates its callers; there is no
second copy of the search algorithm or compatibility wrapper. Both historical
primitive evaluations and mixed programs use its queue, pruning, state and result.

## Execution and budgets

```java
var problem = new WorkBudgetBestFirstSearchStrategy.Problem(
    input, target, new SearchExpansionSource.Program(program),
    scorer, canonicalizer,
    new WorkBudgetBestFirstSearchStrategy.Budget(
        primitiveSteps, theoryWorkUnits, exploredStates,
        candidatesPerState, expandingSteps, totalWorkUnits));
var result = new WorkBudgetBestFirstSearchStrategy().search(problem);
```

The program uses ordinary `RewriteProgram.Source` nodes. A source can contain
ordinary rules, a verified exact-theory engine, or a nested program. Expansion
calls the existing `executeWithWorkBudget` interpreter and retains its complete
`RewriteExecution`, including source observations that were filtered, deduplicated,
pruned or rejected by a path budget. No unobserved mathematical ledger or public
hash-only evidence record is accepted through a new batch adapter.

Each state keeps the actual `Transformation` list, nested canonical provenance,
intermediate edge expressions, primitive lineage and `ExecutionWork`. Construction
checks continuity and agreement of the retained path, assumptions, application
identities and work. A composed edge retains all its internal steps; edge depth
never substitutes for primitive or theory work.

| Limit / counter | Meaning |
| --- | --- |
| `maxPrimitiveSteps` | Maximum actual primitive rewrites on one retained path |
| `maxExactTheoryWorkUnits` | Maximum evidence-bound exact-theory work on that path |
| `ExecutionWork.exactTheorySteps` | Separate theory-step count; not fictitious primitive depth |
| `maxWorkUnits` | Global observed candidate work and mechanical search work, plus the existing primitive path-audit reserve |
| `maxExploredStates`, `maxCandidatesPerState`, `maxExpandingSteps` | Independent outer search limits |

Expansion receives the original path allowance minus the state's actual work in
both dimensions. An exhausted primitive allowance still permits a theory step
when theory authority remains. Returning to an earlier expression cannot restore
either allowance. Both the interpreter and frontier validate path admission.

Mixed state identity retains the full typed path. Equal expressions with different
evidence, remaining work, assumptions or application history are not silently
merged. This deliberately conservative identity does not claim a dominance
relation or a more efficient search. Repeated application checks use typed
provenance, so a primitive textual key cannot impersonate an exact-theory step.

## Global work revision

`SearchExpansionSource.Measured` explicitly selects the frozen mechanical v1
contract used by existing information-parity evaluations. Its budget must grant
zero theory authority. `Budget.primitive(...)` retains that profile's validation
and primitive path-audit reserve. Its arithmetic and benchmark thresholds are not
retargeted to the new work formula.

`SearchExpansionSource.Program` selects `regelsuche.search-work/v2`:

```
charged search work = transformation mechanical v1 work
                    + outer search administration
                    + all observed candidate primitive rewrites
                    + all observed candidate exact-theory work

available search work = maxWorkUnits - maxPrimitiveSteps
```

The theory-step count is not added again. Mathematical sums use checked
arithmetic. Source work is charged before sorting or enqueuing any candidate,
including work attached to proposals later discarded. Candidate formation is an
atomic observation: an over-budget expansion records its full attempted work and
admits no edge; the limit is not a wall-clock interruption inside a source call.

V2 also charges an admission check and reserves the enqueue unit before inserting
a state. A rejected enqueue does not increment `enqueuedStates`. A later state
visit remains a separately charged operation. The existing reserve of one unit
per permitted primitive step remains visible for later primitive path auditing;
the search does not claim that such an audit has already occurred.

`PATH_WORK_BUDGET` reports blocked path work; `WORK_BUDGET` reports global work
exhaustion. Program pruning remains visible as incomplete expansion.
`expansionsComplete()` describes the retained expansion results, not mathematical
closure of the whole search space. A valid reached path can coexist with
incomplete alternatives. No failure status is evidence of impossibility.

## Observation and replay

`Result.toCanonicalJson()` produces `regelsuche.work-search-replay/v1`. It binds
the input, target, every budget, work revision, states and scores, candidate
decisions, expansion completeness, all work dimensions and source observations.
Paths retain canonical typed provenance with the exact source/output, method,
evidence hash, receipt/run references and complete canonical evidence JSON.
Frontier decisions retain their full parent state, not only its expression.

`WorkSearchReplay.verify(json, independentlyVerifiedProblem)` reruns the same
search and compares the complete canonical observation. The caller rebuilds
theory sources through the full solver, artifact-verification and independent
replay pipeline. JSON is never deserialized into executable evidence. This is an
execution comparison over trusted installed code, not a code-identity certificate
for arbitrary Java engines, predicates or unvisited program branches.

The integration fixture performs actual finite-polynomial resolution, receipt/run
byte verification and independent replay on every invocation. Characterization
covers mixed frontier paths, two independently verified theories with zero
primitive allowance, mixed macro edges, exact and insufficient path/global
budgets, distinct evidence at the same expression, discarded proposal work,
incomplete expansions and fresh full-verifier/search replay with tamper controls.

## Remaining scope of #900

The budgeted best-first frontier now retains and charges verified exact theory.
Other algorithms using the older `SearchState`, application/DTO persistence and
repository-wide artifact-loading replay still require migration. Those consumers
continue rejecting exact-theory transitions without explicit work authority.
This change does not promote learned plans, change frozen matched-work artifacts,
invent primitive derivations, or assert general theorem-prover soundness,
mathematical novelty or tactic quality.
