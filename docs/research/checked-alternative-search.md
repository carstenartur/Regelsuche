# Bounded checked alternatives

The source-only task solver can optionally continue beyond its first acceptable
answer. This uses the existing search kernel and checked primitive, construction
and learned providers. `solve` and `learnBatch` retain their previous stopping
behavior and knowledge snapshot formats.

```java
var execution = knowledge.explore(task,
    CumulativeTaskKnowledge.Strategy.AUTOMATIC,
    500_000, // selection/replay reserve inside task.budget().totalWork()
    32);     // maximum number of distinct alternative expressions
if (execution.withinBudget()) {
    var evidence = execution.evidence().orElseThrow();
    for (var candidate : evidence.candidates()) {
        // candidate.state(), candidate.score(), candidate.witness()
    }
}
```

The task supplies its input, general measured structure goal and bounds. It does
not supply an expected output expression. Candidates must differ from the input,
qualify for the task goal, and have a complete independently replayed path.
The immutable generation and actually selected strategy remain attached to the
execution. Exploration applies existing knowledge but does not train or promote
a successor generation.

Routing is charged before the reserve is taken from the remaining query budget.
Search, candidate inspection, path construction and additional replay share that
same original budget. Atomic work can exceed a limit, but it remains charged and
`withinBudget()` then returns false. Acquisition and restore/setup remain separate
receipts of the knowledge generation; the query total is not a full lifecycle or
CPU/memory measurement.

Alternative lineage retains immutable prefixes with their cumulative theory
work. For the same exact state, an admitted cheaper prefix dominates a more
expensive one. A regression under both continuation contracts admits prefixes
costing 9 and 1, then a continuation costing 5 under a theory bound of 10. The
returned path uses **1 + 5 = 6**, rather than incorrectly splicing **9 + 5 = 14**.
The test first reproduced the invalid 14-unit witness and then passed after the
lineage correction. Its test-only theory authority is isolated in a child JVM.

## Historical development examples

`StructuralAlternativeSearchTest` searches first, then reads the expected
expression and independently checks every step's exact polynomial identity.
The bridge profile, a total query budget of 10 million logical units, a reserve
of 500,000 and a limit of 32 alternatives are fixed before execution.

| Input | Reference found afterwards | Candidates | Declared query work | Selection | Additional replay |
| --- | --- | ---: | ---: | ---: | ---: |
| `y*y+(2*x)*y+x*x` | `(x+y)^2` | 2 | 7,421,738 | 248,605 | 20,647 |
| `(a-b)*(a+b)` | `a*a-b*b` | 4 | 9,864,373 | 307,877 | 39,269 |

Depth/state bounds are respectively 4/400 and 6/600. These examples recover two
previously omitted reference spellings. They do not replace the frozen
production-atlas results, reproduce every original per-case contract, establish
exhaustive enumeration, or demonstrate a general learning speedup. The original
binomial-square target spelling remains a separate open development case.

## Result boundaries

- Distinctness is structural AST identity; output order follows qualifying
  admitted events, with one independently replayed prefix per expression.
- `truncated=true` is conservative: it also occurs as soon as the requested
  output limit is reached, even if that happened to be the last qualifying
  state. `truncated=false` describes collection from the admitted states;
  inspect the underlying search outcome for frontier exhaustion or other limits.
- The existing incumbent final replay retains its strict verification-record
  equality, including work. Additional alternative replay requires the same
  accepted proof receipts and reason while charging its actual returned work.
  Cost-varying custom incumbent verifiers can therefore fail closed before
  collection even when proof receipts agree.
- Throwing or conflicting callbacks issue no successful exploration evidence.
  Arbitrary supplied objectives and verifiers remain trusted code.

Reproduce with Java 25:

```sh
mvn -pl regelsuche-autopilot -am clean test
```

Focused contracts are `TypedSourceOnlyBestTest`, `TypedAlternativeLineageTest`,
`StructuralAlternativeSearchTest` and `CumulativeAlternativeSearchTest`.
