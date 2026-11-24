# Prepared task knowledge and measured self-improvement

`TaskKnowledgeSession` reuses existing, independently checked cumulative knowledge
inside an explicitly owned lifetime. `TaskKnowledgeSelfImprover` runs the existing
batch learner and adds measured adoption decisions. They use the existing typed
search frontier, schema proofs and primitive fallback. No additional mathematical
procedure or query-answer cache is installed.

For explicitly repeated requests, the separate `TaskKnowledgeQuerySession`
retains successful selected paths as proposals. It is opt-in and does not change
the APIs above. A first success retains a bounded observation key; a second
successful occurrence may retain its path. Later hits execute the regular
candidate verifier and the complete independent selected-path replay again.

```java
try (var queries = new TaskKnowledgeQuerySession(preparedKnowledge, 32, 4_000_000)) {
    var result = queries.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
    if (result.solved()) {
        var proof = result.execution().solution().orElseThrow().evidence();
        // Fresh verified output and witness from this invocation.
    }
    long paidQueryWork = result.totalWork();
}
```

One immutable generation owns the query session. Keys include exact typed source
identity, normalized assumptions, the built-in goal and threshold, all six query
budget fields and the requested strategy. Arbitrary objective callbacks always
run fresh. A retained path never supplies an unsolved-result or impossibility
certificate. Misses perform ordinary search; hits replace proposal generation
only, with a deliberately incomplete successor relation. No unsuccessful replay
silently restarts with an additional budget.

Observation keys and full paths share the configured LRU capacity and retained
logical-character limit. A first observation reuses the normal solver's existing
canonical source document to conservatively bound its key; it does not traverse
the full witness graph. Full path admission pays the existing `RetainedGraph`
footprint traversal, replacement and eviction. Over-budget atomic preparation
remains paid and cannot admit the path. `Query.totalWork()` includes retention;
`Query.solved()` also checks that enclosing total. Its embedded execution reports
the solver's narrower scope and must not replace the enclosing query decision.
Disabled capacity preserves the original persistent solver's work contract.
Closing the session drops retained observations and paths.

The [public comparison and limits](research/task-query-reuse-2026-11-24/README.md)
distinguish repeated-query reuse from learning transfer and include cold and
distinct-source controls. The existing `TaskKnowledgeSession` still only reuses
verified generations and never performs this query retention implicitly.

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
exceed **all actually paid formation and evaluation costs of the improvement run**
in both elapsed time and CPU, including earlier rejected attempts and intervening
controller work. Explicit `LEGACY_ATTEMPT` scope preserves historical per-attempt
selection for old studies. `Attempt.paid` remains incremental;
`Attempt.adoptionCost` states the actual cumulative or legacy cost used by the gate.
Large horizons/products use exact integer arithmetic. The horizon is a
caller-supplied forecast; adoption predicts savings, not a measured guarantee.
An independent complete stream is necessary to demonstrate actual amortization.
Promotion samples must represent the expected task mix; a future query count
alone does not specify that mix. Rejection does not recover already paid work.

Time/work cutoffs are checked between bounded atomic API operations, including
after split inspection before learning, between the two members of a comparison pair and before committing adoption. An atomic overrun remains fully
paid and cannot adopt a generation. Technical failures preserve completed lessons
and known work, mark unknown unfinished work incomplete and retain the previous
active generation. Invalid promotion splits, including in later batches, return
a failed incomplete attempt with the earlier ledger and active generation intact.
No-op and rejected learning also stay in the returned ledger.
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
never silently replaced. Offline verification checks the retained evidence without
executing reference procedures:

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
scores on every case. The experiment measures preparation and further learning
within the declared composition family; proof-strategy formation and the
Euclid/Ferrari historical tasks require separate task contracts. The
[research methodology](research-methodology.md) explains the role of reference
procedures in these measurements.

