# Mixed primitive and exact-theory program execution

This slice of #900 gives `Transformation`, `RewriteCandidate` and `RewriteExecution`
one common structural provenance model. `executeWithWorkBudget` runs ordinary
`RewriteProgram.Source` nodes containing both AST rewrites and one explicitly
selected, independently verified finite-polynomial candidate. It advances mixed
program execution; [the work-budget search frontier](work-budget-search-frontier.md)
now consumes the same observations and typed transformations.

## Canonical provenance and authority

`TransformationProvenance` has the versioned canonical JSON schema
`regelsuche.transformation-provenance/v1` and three closed kinds:

| Kind | Retained authority | Mathematical work |
| --- | --- | --- |
| `PRIMITIVE_REWRITE_SEQUENCE` | Ordered primitive rule IDs, including multiplicity, and the actual application key | Actual primitive count |
| `EXACT_THEORY_STEP` | Verifier-issued capability, exact source/output, method, evidence hash, receipt/run artifact IDs and complete canonical evidence JSON | Zero primitives, one theory step, positive evidence-bound work |
| `SEQUENCE` | Ordered transformations, every intermediate output, application identity, assumptions and nested provenance | Checked sum of both dimensions |

Existing constructors remain primitive constructors. A macro keeps its real rule
sequence and compound application identity; this model does not invent individual
primitive application locations which the macro did not retain. Exact theory can
only be constructed from `ExactTheoryEvidence`, whose constructor is private.

The core module cannot depend on the learning verifier. A small installed-code
`ExactTheoryEvidenceProvider` service bridges that dependency: the learning
provider accepts only the sealed `VerifiedCandidateEvidence` capability issued by
`ExactFinitePolynomialPlanCandidateEvidenceVerifier`. Public `EvidenceData`, JSON,
hashes, `Binding` records and the older public budgeted-source transition records
cannot issue the core capability. Callers cannot pass a provider to the factory;
it uses services installed with the core class loader and rejects ambiguous
providers. The trusted computing base includes installed provider code and the
existing verifier, not merely the correctness of a supplied content hash.
Installing another provider is a trusted code change, not a data-level operation.

`VerifiedFinitePolynomialTransformationEngine` is immutable and fixes one candidate
at construction. It returns exactly that candidate for its parser-canonical source
and no candidate for another source. It performs no solver rerun, candidate
selection, hidden best-of search or assumption strengthening. Its ordinary
`transform` entry fails; candidate materialization is invoked by the explicit
work-aware interpreter. Receiving or serializing a candidate is not frontier
admission authority.

Theory application keys are derived from canonical provenance. Record equality
retains the provenance kind and evidence; mixed deduplication also binds canonical
provenance. Composition validates evidence-bound source continuity, output and
assumptions. Serialization is observational: canonical JSON does not deserialize
into an executable capability. Replay obtains fresh verifier-issued evidence and
re-executes the declared program; the integration test compares the complete
canonical provenance, content hash and transformation after full fresh replay.
The search frontier adds canonical run observations and fresh execution comparison.
Repository-wide artifact loading and other search-state serializers remain separate work.

## Explicit budgets and retained work

```java
RewriteExecution result = new RewriteProgramInterpreter().executeWithWorkBudget(
    program, input,
    new BudgetedRewriteProgramExecution.PathBudget(primitiveAllowance, theoryAllowance));
```

`ExecutionWork` separates `primitiveRewrites`, `exactTheorySteps` and
`exactTheoryWorkUnits`. Canonical path work is primitives plus theory work; the
theory-step count is not added again. All mathematical addition is checked and
overflow fails closed. Every source candidate is recorded before budget admission
in `sourceObservations`, together with its full provenance, remaining budget and
admission decision. The root result also retains the supplied path budget.

`Choice` siblings receive the same incoming budget. `Sequence` and `Repeat` pass
only the remainder after the actual prefix in both dimensions. Returning to an
earlier expression cannot reset work. A budget rejection makes the result
incomplete, including when another branch succeeds. `FirstApplicable` cannot skip
an incomplete empty branch. Repetition retains all admissible endpoints.
`Require`, `Prioritize`, deduplication and `Prune` retain the source observations
and their work; real pruning remains incomplete. Nested
`ProgrammedTransformationEngine` sources execute in the same interpreter context,
preserving their budgets, evidence and incomplete outcomes. Other measured sources
retain their delegated mechanical ledger; a mathematical batch without the
execution-observation contract is rejected instead of projected silently.

The frozen mechanical `TransformationWorkMetrics.totalWorkUnits()` remains v1.
Existing primitive-only execution and matched-work baselines keep that formula.
The explicit entry reports `regelsuche.rewrite-program-work/v2`:

```
totalWorkUnitsV2 = mechanical v1 work + all observed candidate canonical work
```

`candidateWork` retains separate primitive/theory dimensions. It counts each
source proposal, including duplicates and candidates later blocked or discarded;
it is not the work of just the surviving path. Evidence-bound solver/replay work
is charged for each observed theory proposal. This is a deterministic accounting
convention, not a claim that the solver ran again during materialization, a
wall-clock measurement, or proof of unrecorded historical generation cost.
No frozen benchmark threshold, historical receipt, cohort or baseline is revised.

Trace levels do not alter candidates, observations or work. Full traces use
`EXACT_THEORY_CANDIDATE` with canonical provenance and no primitive rule IDs;
budget rejections are explicit `WORK_BUDGET_REJECTED` events.

## Compatibility and remaining boundary

The original `execute` entry preflights known work-aware sources, including nested
program engines, before calling any source, and rejects theory values emitted by
an unmarked ordinary engine. The old `executeBudgetedSource` and theory-only
`executeBudgeted` contracts remain available and unchanged; public hash-bound
protocol values are not promoted into verifier capabilities by this migration.

Legacy transformation batches, search strategies without explicit theory budgets,
and bounded reachability consumers reject exact-theory values. They cannot
interpret zero primitive steps as a free edge. `WorkBudgetBestFirstSearchStrategy`
accepts mixed programs with explicit path and global work authority and retains
typed paths, source observations and replay evidence. Repository-wide state
serialization and artifact-loading replay remain open in #900. This API does not
add a primitive derivation, general theorem-prover soundness or tactic-quality claim.

The characterization uses actual AST add-zero/multiply-one rules around a real
finite solver result. It covers both insufficient dimensions, repeat, incomplete
first-applicable, nested programs, equal outputs with distinct evidence, dedup,
pruning, filtering, ordering, trace parity, forged data, source/output substitution,
legacy rejection, overflow and complete fresh verifier replay.
