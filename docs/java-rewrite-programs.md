# Java-internal rewrite programs

Status: implemented foundation

Regelsuche can express combinations of existing transformation engines as
immutable, typed Java programs. This is the semantic foundation for a later
textual DSL; it is not a second search implementation.

## Architecture

Ordinary primitive and proof-expandable transformations follow the existing
search boundary:

```text
existing rule, move and macro engines
                 │
                 ▼
       RewriteProgram.Source
                 │
                 ▼
       canonical RewriteProgram AST
                 │
                 ▼
       RewriteProgramInterpreter
          │                 │
          │                 └── structured RewriteTraceEvent stream
          ▼
 ProgrammedTransformationEngine
                 │
                 ▼
existing best-first, beam, A*, MCTS and other SearchStrategy implementations
```

Every ordinary source remains a `TransformationEngine`. Existing rules therefore
do not have to be rewritten before they can participate in a program. The
adapter returns ordinary `Transformation` instances, so search, telemetry,
replay and evidence code retain their existing contracts.

Verifier-backed exact-theory operations use a second, deliberately narrower
path:

```text
verifier-owned candidate evidence
                 │
                 ▼
   BudgetedTransformationSource
                 │
                 ▼
   RewriteProgram.BudgetedSource
                 │
                 ▼
 executeBudgetedSource(explicit mathematical work)
                 │
                 ▼
BudgetedTransformationSourceProgramExecution
```

This path does not convert an exact solver operation into a primitive
`Transformation`. It remains top-level-only until mathematical path budgets are
defined for every composition node.

## Ordinary example

```java
import static de.regelsuche.search.program.RewritePrograms.*;

RewriteProgram safeAlgebra = prune(
    "bounded-safe-algebra",
    prioritize(
        "cheap-first",
        require(
            "equivalence-only",
            firstApplicable(
                "macro-before-primitives",
                source("learned-macros", macroEngine),
                sequence(
                    "normalize-then-factor",
                    source("normalization", normalizationEngine),
                    source("factorization", factorizationEngine)
                ),
                source("ordinary-rules", ordinaryRuleEngine)
            ),
            "equivalence preserving by construction",
            equivalencePreserving()
        ),
        "estimated cost, then rule id",
        byEstimatedCostThenRule()
    ),
    40,
    "search profile candidate budget"
);

RewriteTraceCollector trace = new RewriteTraceCollector();
TransformationEngine engine = new ProgrammedTransformationEngine(
    safeAlgebra,
    RewriteTraceLevel.FULL,
    trace
);

SearchProblem problem = existingProblemWith(engine);
List<SearchState> states = new BestFirstSearchStrategy().search(problem);
```

The program remains ordinary Java: refactoring, type checking, navigation,
breakpoints and tests work without a custom parser or editor plugin.

## Exact-theory example

```java
RewriteProgram.BudgetedSource exactPlan = budgetedSource(
    "verified-finite-plan",
    verifiedFinitePolynomialCandidateSource
);

BudgetedTransformationSourceProgramExecution execution =
    new RewriteProgramInterpreter().executeBudgetedSource(
        exactPlan,
        sourceExpression,
        availableMathematicalWorkUnits
    );
```

The result preserves `CANDIDATES`, `NO_MATCH` and `BUDGET_INCONCLUSIVE` as
separate states. Each successful candidate reports zero primitive rewrites and
one evidence-bound exact-theory step.

## Canonical nodes

| Node | Semantics |
|---|---|
| `Source` | Invoke one existing ordinary `TransformationEngine`. |
| `BudgetedSource` | Invoke one exact-theory source only through the explicit top-level budgeted entry. It cannot yet be composed. |
| `Choice` | Evaluate every ordinary alternative and form a deterministic union. |
| `FirstApplicable` | Evaluate ordinary alternatives in order and retain the first non-empty result. |
| `Sequence` | Feed every ordinary candidate of one step into the next step. Multi-step paths become explicit macro-like transformations. |
| `Repeat` | Apply an ordinary body for a bounded number of iterations and retain every endpoint between the declared minimum and maximum. |
| `Require` | Hard semantic filter. Rejected candidates cannot reach search. |
| `Prioritize` | Soft ordering only. It must not add or remove candidates. |
| `Prune` | Explicit candidate truncation. An execution that actually truncates candidates is marked incomplete. |

The distinction between `Require`, `Prioritize` and `Prune` is intentional.
Soundness conditions must not be disguised as heuristics, and a candidate
budget must remain visible as a completeness limitation.

`ProgrammedTransformationEngine` remains restricted to the ordinary path. The
ordinary interpreter recursively preflights the complete tree and rejects any
contained `BudgetedSource` before invoking an ordinary or exact source. This
prevents hidden unlimited work, budget resets and partial side effects before a
later unsupported node is encountered.

## Determinism and composed transformations

Primitive transformations are ordered deterministically by rule id, output,
application key and rewrite kind before program combinators inspect them.
Duplicate paths are removed by output plus the complete sequence of application
keys.

A one-step ordinary candidate is returned unchanged. A multi-step ordinary
candidate is converted to one explicit transformation whose metadata contains:

- the ordered primitive rule ids;
- the combined application key;
- the saturated sum of estimated cost deltas;
- the conjunction of construction-time equivalence guarantees;
- normalized assumptions;
- contributing rule-pack and license identifiers.

This preserves the existing search boundary while retaining enough information
for replay and evidence.

An exact-theory candidate instead remains bound to its complete
`ExactTheoryTransition`, including source, result, theory-step ID, evidence hash,
assumptions, mathematical work and application key. Its content-addressed
program projection and mechanical-work ledger are validated independently of
ordinary transformation provenance.

## Tracing and debugging

Tracing for ordinary programs is observational: `OFF`, `SUMMARY` and `FULL`
execute the same program and produce the same candidates.

`SUMMARY` emits node entry/exit, selected alternatives and real pruning. `FULL`
additionally emits source candidates, rejected candidates, skipped alternatives
and repeat iterations. Every event contains:

- a monotonically increasing sequence number;
- node id and node kind;
- optional source file, line and column;
- input and optional output expression;
- primitive rule ids;
- candidate count, completeness and explanatory detail.

`RewriteTraceCollector` is a thread-safe in-memory sink. Other sinks can stream
the same events to NDJSON, the Web Workbench or an IDE debugger.

The first implementation traces ordinary program execution and generated
transformations. Exact AST positions, matcher bindings and failed structural
matches remain the responsibility of position-aware rule engines and the AST
Rule Radar. A later adapter can attach those events beneath the corresponding
`Source` event without changing the program model.

The isolated v1 exact-theory entry retains its complete source execution and
work ledger rather than projecting it into the ordinary trace model. A future
compositional execution model must add typed exact-theory trace events without
claiming primitive rule applications.

## Java version

The repository targets Java 25 LTS. Records, sealed interfaces and exhaustive
pattern matching for `switch` provide the language mechanisms needed for the
program AST and interpreter. Java 25 also provides final `ScopedValue`, but the
interpreter continues to pass context and trace sinks explicitly. No preview
feature is used.

The rewrite-program format is independent of the concrete JDK patch level and
remains compatible across the repository's Java 25 toolchain, runtime-image and
reproduction contracts.

## Path toward a textual DSL

A future parser should target `RewriteProgram`, not call search classes
directly:

```text
textual DSL ── parser ──┐
                        ├── RewriteProgram ── interpreter ── search
Java factories ─────────┘
```

That keeps one semantic implementation and lets a textual editor add syntax
highlighting, validation and source locations without duplicating execution
logic.

The textual DSL must preserve the distinction between ordinary and budgeted
sources. Until compositional path budgets are implemented, it must not allow a
`BudgetedSource` below `Choice`, `FirstApplicable`, `Sequence`, `Repeat` or the
other ordinary combinators.
