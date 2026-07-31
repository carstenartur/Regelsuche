# Java-internal rewrite programs

Status: implemented foundation

Regelsuche can now express combinations of existing transformation engines as
immutable, typed Java programs. This is the semantic foundation for a later
textual DSL; it is not a second search implementation.

## Architecture

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

Every primitive source remains an ordinary `TransformationEngine`. Existing
rules therefore do not have to be rewritten before they can participate in a
program. The adapter returns ordinary `Transformation` instances, so search,
telemetry, replay and evidence code keep their existing contracts.

## Example

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

## Canonical nodes

| Node | Semantics |
|---|---|
| `Source` | Invoke one existing `TransformationEngine`. |
| `Choice` | Evaluate every alternative and form a deterministic union. |
| `FirstApplicable` | Evaluate alternatives in order and retain the first non-empty result. |
| `Sequence` | Feed every candidate of one step into the next step. Multi-step paths become explicit macro-like transformations. |
| `Repeat` | Apply a body for a bounded number of iterations and retain every endpoint between the declared minimum and maximum. |
| `Require` | Hard semantic filter. Rejected candidates cannot reach search. |
| `Prioritize` | Soft ordering only. It must not add or remove candidates. |
| `Prune` | Explicit candidate truncation. An execution that actually truncates candidates is marked incomplete. |

The distinction between `Require`, `Prioritize` and `Prune` is intentional.
Soundness conditions must not be disguised as heuristics, and a candidate
budget must remain visible as a completeness limitation.

## Determinism and composed transformations

Primitive transformations are ordered deterministically by rule id, output,
application key and rewrite kind before program combinators inspect them.
Duplicate paths are removed by output plus the complete sequence of application
keys.

A one-step candidate is returned unchanged. A multi-step candidate is converted
to one explicit transformation whose metadata contains:

- the ordered primitive rule ids;
- the combined application key;
- the saturated sum of estimated cost deltas;
- the conjunction of construction-time equivalence guarantees;
- normalized assumptions;
- contributing rule-pack and license identifiers.

This preserves the existing search boundary while retaining enough information
for replay and evidence.

## Tracing and debugging

Tracing is observational: `OFF`, `SUMMARY` and `FULL` execute the same program
and produce the same candidates.

`SUMMARY` emits node entry/exit, selected alternatives and real pruning.
`FULL` additionally emits source candidates, rejected candidates, skipped
alternatives and repeat iterations. Every event contains:

- a monotonically increasing sequence number;
- node id and node kind;
- optional source file, line and column;
- input and optional output expression;
- primitive rule ids;
- candidate count, completeness and explanatory detail.

`RewriteTraceCollector` is a thread-safe in-memory sink. Other sinks can stream
the same events to NDJSON, the Web Workbench or an IDE debugger.

The first implementation traces program execution and generated
transformations. Exact AST positions, matcher bindings and failed structural
matches remain the responsibility of position-aware rule engines and the AST
Rule Radar. A later adapter can attach those events beneath the corresponding
`Source` event without changing the program model.

## Java version

The implementation deliberately stays on the repository's Java 21 baseline.
Records, sealed interfaces and exhaustive pattern matching for `switch` already
provide the language mechanisms needed for the program AST and interpreter.
Java 25 adds useful platform capabilities, notably final `ScopedValue`, but it
is not required here because interpreter context and trace sinks are passed
explicitly. No preview feature is used.

A repository-wide Java 25 migration should therefore be handled separately,
including Gradle, runtime images, proof images, Testcontainers and release
reproduction. The rewrite-program format remains compatible with such an
upgrade.

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
