# Prepared task knowledge and measured self-improvement

`TaskKnowledgeSession` reuses existing, independently checked cumulative knowledge
inside an explicitly owned lifetime. `TaskKnowledgeSelfImprover` runs the existing
batch learner and adds measured adoption decisions. They use the existing typed
search frontier, schema proofs and primitive fallback. No additional mathematical
procedure or query-answer cache is installed.

## Reusing a verified generation

```java
try (var session = new TaskKnowledgeSession(inventory, 4, 2_000_000)) {
    var preparation = session.prepare(snapshotPath);
    var knowledge = preparation.knowledge();
    var execution = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
    // Later requests may call prepare again. The file is still read and checked,
    // but identical verified bytes reuse the already prepared generation.
}
```

The first load executes the existing strict snapshot reader, mathematical
reproof and the opt-in indexed matcher preparation. A later hit requires the
same inventory and identical full canonical bytes. A digest alone cannot grant
proof authority. Changed files, false schemata, foreign inventories, symlinks and
invalid content addresses fail through the normal reader/checker.

The session retains at most the configured number of generations and canonical
characters, evicting the least recently used. An individually oversized generation
loads correctly without being cached. These are bounds on retained canonical
data, not a complete heap/RSS limit. `close()` clears the session's references;
caller-held immutable generations keep their ordinary validity. Further session
operations after close fail.

`Preparation` separates lookup/read units from actual restore/preparation work.
`statistics()` retains hits, admissions, failures, eviction and aggregate known
work. A failed load makes complete logical accounting unavailable; it is not a
free successful preparation. Whole elapsed/CPU/I/O costs need actual measurement.
The old `CumulativeTaskKnowledge.load/restore/empty` APIs preserve their behavior.
Their explicit prepared counterparts enable the existing indexed matcher and
carry that runtime choice through subsequent cumulative generations.

## A paid improvement cycle

```java
var policy = new TaskKnowledgeSelfImprover.Policy(
    5_000_000, 120_000_000_000L, 120_000_000_000L, 2, 3, expectedFutureQueries);
var outcome = new TaskKnowledgeSelfImprover().improve(
    preparedKnowledge, batches, policy);
var active = outcome.active();
var admission = session.retainVerified(active);
var saved = active.save(snapshotDirectory);
```

Each `Batch` supplies lessons, the learner's validation inputs and a separate
promotion set. Promotion sources must be polynomial-alpha-disjoint from all
supplied lesson/validation sources. This separation does not establish an
independent mathematical family or a sealed test. Successful previous generations
actually participate in later lessons through the existing cumulative learner.

The loop measures old and proposed generations on the same promotion tasks,
alternating execution order. The default meter observes monotonic elapsed time
and whole-JVM process CPU, including JVM threads. Missing CPU prevents cost-based
adoption. Concurrent unrelated activity in the same JVM can affect these samples.
The package-scoped controlled-clock seam is used only by deterministic state
tests, never by the runtime experiment.

The exact adoption gate rejects an individual solution/score regression or an
incomplete sample. Predicted average savings over `expectedFutureQueries` must
exceed **all actually paid formation and evaluation costs** in both elapsed time
and CPU. Large horizons/products use exact integer arithmetic. The horizon is a
caller-supplied forecast; adoption predicts savings, not a measured guarantee.
An independent complete stream is necessary to demonstrate actual amortization.
Promotion samples must represent the expected task mix; a future query count
alone does not specify that mix. Rejection does not recover already paid work.

Time/work cutoffs are checked between bounded atomic API operations, including
between the two members of a comparison pair. An atomic overrun remains fully
paid and cannot adopt a generation. Technical failures preserve completed lessons
and known work, mark unknown unfinished work incomplete and retain the previous
active generation. No-op and rejected learning also stay in the returned ledger.
Use the **whole `Result`**, not only `active().acquisitionWork()`, when accounting
for the cycle: a rejected attempt leaves that older immutable state unchanged.

`retainVerified` accepts only a same-inventory, already prepared
`CumulativeTaskKnowledge` object. Its private construction/load/learning path has
already checked the mathematics. Admission avoids reproving that object merely
to put it into the owned session; it accepts no arbitrary JSON or proof digest.

## Reproduce the development experiment

With Java25, Maven, Python venv support and the pinned Python packages:

```sh
bash gradle/run-knowledge-lifecycle-improvement.sh
```

Use `REGELSUCHE_KNOWLEDGE_OUTPUT` for a new destination; an existing experiment is
never silently replaced. Offline verification runs no competitor:

```sh
PYTHONPATH=scripts python3 -B -m external_polynomial_comparison.run_knowledge_lifecycle \
  --verify --output /absolute/path/to/evidence
```

The frozen protocol includes primitive control, the existing persistent learned
session, repeated strict loads, prepared reuse, paid improvement and targeted
SymPy factorization. The composition tasks are public development examples of
one family. Resource receipts include acquisition, the fresh first load/reproof,
all repeated loads/queries, rejected attempts and shutdown. Runtime snapshots,
original selected paths, failed outcomes and the outer command costs are retained.
Performance ratios require unchanged goal attainment and no worse output-node
scores on every case. This experiment is not a claim of broad CAS superiority,
general proof invention or completion of Euclid/Ferrari capabilities.
