# Cumulative checked task knowledge

`CumulativeTaskKnowledge` connects the existing task solver to the existing
checked-schema generalizer. A completed batch can produce a new immutable
knowledge generation. Later lessons actually use the previously learned
schemata. This is an experimental Java API for the existing unconditional
rational-polynomial fragment, not an additional search kernel or theorem checker.

## Use

```java
var knowledge = CumulativeTaskKnowledge.empty(genome);
var update = knowledge.learnBatch(lessonTasks, validationTasks, 100_000);
knowledge = update.next();
var snapshot = knowledge.save(Path.of("knowledge"));

// Another process: the stored schemata are independently proved on loading.
var restored = CumulativeTaskKnowledge.load(genome, snapshot);
var execution = restored.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
if (execution.solved()) {
    var result = execution.solution().orElseThrow().evidence().incumbent().expression();
}
long queryWork = execution.totalWork();
long historicalAcquisition = restored.acquisitionWork();
long thisProcessSetup = restored.sessionSetupWork();
```

The caller supplies the same predeclared `CheckedSchemaTaskSolver.Task` goals and
budgets used by #1122. `BASELINE`, `KNOWLEDGE_FIRST`, and `AUTOMATIC` are typed
strategies. Solving never changes the library; learning is an explicit batch
operation. All lessons in a batch use the same old generation. The returned
successor, not a mutable global pointer, is the publication boundary.

## Learning and activation

Only independently replayed, within-budget, unconditional multistep solutions
supply source/target examples. A single already learned step is reuse, not a new
composition. Source/target pairs use the same generalization, symbolic proof,
deduplication and schema bounds as `CheckedLearnedSchemaModel.learn(...)`.
The original trace-learning entry point and its historical results are unchanged.
Newly learned solutions can include previously learned applications plus primitive
remainder steps. Existing schema IDs remain in the successor; duplicate theorems
are consolidated. The existing maximum of 32 schemata remains in force.

Lesson and validation inputs are separated by the existing exact polynomial
alpha-identity check, not just strings or variable names. This excludes overlaps
but does not turn the development corpus into independent mathematical families.
The supported finite domain and its limits apply to this split check too.

Validation measures baseline, previous automatic policy, candidate knowledge and
then the actually fitted automatic policy. Every executed arm is paid. Activation
must preserve solved validation tasks and the quality of unsuccessful outcomes.
It requires either more solved tasks under their declared budgets or less total
validation query work at unchanged success. Otherwise old mathematics is retained.
Rejection and learning-budget overruns still produce cost-bearing snapshots.
An unexpected exception leaves the old state untouched and throws `BatchFailure`,
retaining completed lessons and known work; unknown unfinished work explicitly
prevents complete accounting.

## Automatic selection

The policy uses bounded input syntax (root and two child levels) and the declared
node-count goal. It does not use expression text, task IDs, variable names or an
expected output. Custom objective callbacks and unknown profiles use the baseline.
The validated policy is frozen until the next explicit learning batch.

A constant baseline policy inspects no syntax. A goal for which no learned route
is active takes a cheap baseline path. Otherwise profile selection is charged to
the same query budget before the existing solver runs. There is no second fallback
budget, refund or free speculative search. Validation re-executes this actual
policy; it does not substitute counterfactual receipts from another arm.
A weak learned candidate therefore need not impose its full application overhead
on every negative case. This is a small, transparent router, not a universal
prediction of the best solver.

## Persistence and audit boundaries

Snapshots contain the model, parent identity, generation number, routing policy,
acquisition receipts, last activation decision and negative formation attempts.
They are strict, bounded JSON. Loading re-proves the model and checks its inventory.
A digest or recorded cost never authorizes a theorem. Historical cost metadata is
descriptive, not authenticated evidence or a newly measured receipt.

Each save publishes a completed temporary file as an immutable content-addressed
file using a same-filesystem hard link. Existing differing files and symbolic-link
files are rejected, never replaced. Filesystems without hard-link support report
an I/O error. There is no implicit latest pointer; selecting an earlier snapshot
is rollback. Atomic publication is not a claim of power-failure durability.

Restore and compilation costs are reported once per prepared process through
`sessionSetupWork()`. They are added to acquisition when that session learns a
successor. Query receipts remain separate, so callers can sum their actual stream
without silently retraining, loading or recompiling on every request.

## Executed development observations

The real learner receives two lessons per generation, with offset/scaling contexts.
Generation 1 learns a three-step cancellation composition. Generations 2 and 3
use the previous library to solve deeper compositions before generalizing and
proving the larger transformation. No shortcut pattern is handwritten into the
production path. Held-out development queries change the substituted expression.

| Generation | Schemata | Previous generation query work | New generation query work | Accumulated acquisition |
|---|---:|---:|---:|---:|
| 1 | 1 | 197 | 196 | 3,600 |
| 2 | 2 | 625 | 340 | 12,969 |
| 3 | 3 | 922 | 604 | 31,611 |

On the *same* four-query transfer stream, including an unattainable near miss,
the automatic-policy totals were **1,762 / 1,763 / 1,594 / 1,194** for generations
0/1/2/3. Generation 1 is slightly worse on that stream: monotonic improvement on
every unknown task or every new generation is deliberately not claimed.

A separate actually executed stream contains 512 queries: 128 rounds with three
composition depths and a retained negative case. Compound substitutions alternate
addition, subtraction, multiplication by 2 and division by 2; names vary, and no
previous query result is cached. All arms have the same task success outcomes.

| Arm | Declared total logical work |
|---|---:|
| Independent primitive baseline; no unused routing | 225,536 |
| Learn generation 1 once, then freeze | 229,264 |
| Cumulative generation 3, including acquisition and fresh-process setup | 186,706 |

The cumulative total includes **31,611** acquisition/validation/preparation units,
**2,263** fresh restore/preparation units, and **152,832** query units. This is about
17.2% less declared work than the primitive baseline on this development stream.
It is an executed amortization example, not a scaled single-query estimate.
It is not a preregistered sealed holdout, broad CAS comparison, wall-clock speedup,
complete JVM CPU/heap/serialization ledger, or completion of P04/P05–P12.

## Verification

```bash
./gradlew --no-daemon :regelsuche-learning:test --tests '*CumulativeTaskKnowledgeTest'
./gradlew --no-daemon :regelsuche-core:test :regelsuche-search:test :regelsuche-learning:test
./gradlew --no-daemon --no-configuration-cache ciCheck
```

The JUnit wrapper runs the same real-model contracts as the executable contract.
They cover three genuine generations, frozen-parent control, persistence and fresh
JVM use, paid selection and learning limits, validation separation, malformed and
mathematically false imports, negative attempts, technical failure receipts and
non-overwriting storage. Seven JUnit methods share a three-generation setup.

Locally, 860 real production source files from seven modules compiled against
real Jackson dependencies. Only Java 21 was available: a separate, unshipped
copy bridges the three existing Java-25 `ScopedValue.call` signatures. No new
production source or mathematical checker was stubbed. The new direct contracts,
the unchanged nine #1122 contracts and separate-JVM restoration passed. Three
compiled mutations fail behaviorally: forgetting earlier schemata, erasing
acquisition costs, and always selecting the baseline. Initial RED was missing-API
compilation failure; mutations are supplemental negative controls, not original
behavioral RED. Gradle was attempted and failed resolving services.gradle.org.
Full hosted Java-25/JUnit CI and independent review remain necessary before merge.
