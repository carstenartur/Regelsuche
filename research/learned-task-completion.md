# Learned knowledge as task completion

`TypedLearnedMoveInventory.newTaskSolver(model)` prepares a reusable solver from a
learned or independently reloaded `CheckedLearnedSchemaModel`. A task declares its
quality criterion **before** execution. The existing frontier tries lazy learned
applications first, verifies every admitted edge, and stops when that criterion
is reached. The independent selected-path replay still runs and still consumes
the task's budget. A failed or over-budget replay cannot produce `solved() == true`.

This addresses a concrete integration gap: the historical comparison worker
performed exploration and selected an incumbent afterwards. Discovering a useful
answer earlier did not by itself stop the remaining exploration. That worker and
its frozen experiment remain unchanged; task completion is a different, explicit
API, not a retroactive reinterpretation of the old results.

## Java integration

```java
import de.regelsuche.evolution.CheckedLearnedSchemaModel;
import de.regelsuche.evolution.CheckedSchemaTaskSolver;
import de.regelsuche.evolution.TraceRewriteStrategyLearner;
import de.regelsuche.evolution.TraceStrategyTransferExample;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;

var formation = new TraceRewriteStrategyLearner().learn(
    TraceStrategyTransferExample.inventory(),
    TraceStrategyTransferExample.trainingInputs(),
    TraceStrategyTransferExample.limits());
var inventory = formation.typedMoves();
var learned = inventory.checkedSchemas();
var restored = CheckedLearnedSchemaModel.load(
    learned.toCanonicalJson(), learned.inventoryHash());
var solver = inventory.newTaskSolver(restored); // prepare once, not once per query

var task = new CheckedSchemaTaskSolver.Task(
    new ExpressionParser().parseExactTerm("(x+y)*(x-y)+y*y").expression(),
    java.util.List.of(),
    CheckedSchemaTaskSolver.Goal.atMostNodes(3),
    new MoveSearch.Budget(12, 12, 10_000, 128, 10_000));
var solution = solver.solve(task);
if (solution.solved()) {
    var output = solution.evidence().incumbent().expression();
    // This output satisfies the declared goal after independent replay.
}
long queryWork = solution.evidence().totalWork();
long oneTimeCompilation = solver.preparationWork();
```

For a fair primitive control, use `inventory.newTaskSolver()` with the **same**
task. A benchmark control can construct its inventory with
`TypedLearnedMoveInventory.primitives(genome)` without running the learner; the
executable contract does exactly that. The control retains its cheaper batch scheduler; it is not forced through
unused learned-cursor machinery. Both arms use the same shared frontier, structural
valuation, caller criterion and final replay. Different criteria are different
tasks, not interchangeable benchmark wins.

`Goal.atMostNodes` counts syntax-tree occurrences, not algebraic optimality. A
custom `Goal` accepts an existing measured `TypedSourceOnlySearch.Objective`; it
must be deterministic and report its work. The objective neither authorizes a
rewrite nor supplies a hidden desired expression. `solved()` is not a claim that
the whole rewrite relation was exhaustively enumerated.

## What the procedure replaces

The executable contract trains the existing learner on two examples containing a
six-step composition of already known basic identities. It then saves and reloads
its inferred schemata. No hand-written six-step shortcut is inserted into the
runtime and no new search algorithm is introduced.

On a new task, one learned application can replace the six primitive applications.
If a further `+0` remains and the task's goal excludes it, the same frontier calls
the primitive fallback for that remainder. A learned application that does not
meet the criterion is never labelled a complete solution.

The schema cursor is prepared once and fresh per-task cursors are opened. The
optional shape index from #1120 is not activated here: its earlier small-model
comparison was negative. There is no result cache, cross-task proof cache, second
budget or automatic production/native qualification.

## Development observations, not a universal learning win

A direct local execution produced these **historical typed logical-work receipts**:

| Task | Primitive task solver | Learned task solver | Result |
|---|---:|---:|---|
| Six-step composed identity, bound 5 nodes | 443 | 247 | Both solve; learned witness has one step instead of six |
| Compound substitution and remaining `+0`, bound 7 nodes | 592 | 489 | Both solve; learned step followed by one primitive step |
| Already satisfactory `x`, bound 1 node | 3 | 3 | Neither opens providers |
| Near miss with unattainable bound 1 node | 112 | 470 | Both unsolved; the learned attempt is more expensive |

The learned setup is charged separately: training **21,605**, schema formation
**7,549**, model restoration **5,949**, cursor compilation **2,326** reported units.
Those costs have **not** been amortized by the four tasks above. They must not be
omitted to turn the first row into a full lifecycle advantage.

These are public development cases, not a preregistered sealed holdout or a broad
SymPy comparison. The first case isolates method reuse; the second changes both
the substitution structure and surrounding task. The tests retain negative and
unsupported cases. The old unit contract is not a complete CPU/heap account.
No total-runtime speedup, completed P04/P05 or general learning superiority follows.

## Reproduction and verification scope

```bash
./gradlew --no-daemon :regelsuche-learning:test \
  --tests de.regelsuche.evolution.CheckedSchemaTaskSolverTest
./gradlew --no-daemon :regelsuche-core:test :regelsuche-search:test :regelsuche-learning:test
./gradlew --no-daemon --no-configuration-cache ciCheck
```

Nine executable contracts cover genuine learning and restoration, early task
completion, residual fallback, replay overruns, fresh invocations, missing
assumptions, unsupported/near-miss inputs and invalid requests. The JUnit wrapper
runs the same contract methods. Its dependency-light `main` also supports
`--write-model path` and `--restore path` for a separate-JVM check without training.

Local execution used the real production sources of the seven relevant modules
and the real Jackson dependencies recovered from retained CI artifacts. No
production class or mathematical checker was stubbed. The container only has
Java 21: a **separate, unshipped** compatibility copy wraps the three Java-25
`ScopedValue.call` sites in `VerifiedPolynomialTransitionCacheStore` to bridge
Java 21's checked-exception signature. All other production source bytes used in
the direct run are unchanged except the proposed patch. This is not a Java-25
project-build result. The unchanged Gradle command was attempted but cannot
resolve `services.gradle.org`; full hosted Java-25 CI and independent review
remain required before merge.

Initial RED was a missing-API compiler error. Supplemental compiled mutations
that restore continued exploration, ignore the post-replay budget, or put
primitive work first all fail the real-model contracts. These controls are not
claimed as original pre-implementation behavioral RED.
