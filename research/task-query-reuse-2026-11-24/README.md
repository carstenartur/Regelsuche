# Bounded task-query path reuse: result and limits

The opt-in `TaskKnowledgeQuerySession` avoids repeated proposal generation for
the same successful query while paying fresh regular candidate verification and
the complete final selected-path replay. The delivered second-observation policy
reduces complete logical work by **3.33%** on the declared repeated stream and
limits its distinct-source overhead to **0.65%**. This development run does
**not** demonstrate wall-time/CPU payback: none of its three repeated-stream pairs
improved both measured resources. It establishes an exact, bounded reuse path
and a reduction in counted search work, not amortized learning transfer or a
competitive runtime result against another algebra system.

## Existing implementation and the remaining cost

`TaskKnowledgeSession` already reuses independently verified immutable knowledge
generations. `TaskKnowledgeSelfImprover` already has a cumulative
`IMPROVEMENT_RUN` adoption gate, and the existing `MixedTaskKnowledgeStudy` uses
the corrected mixed task roles. Those mechanisms remain in place. In particular,
the old negative learning result is not repaired by relabelling repeated-query
reuse as learning.

Before this change, a repeated `CumulativeTaskKnowledge.solve` call still starts
the existing typed frontier and generates candidates again. The existing
primitive candidate cache has a search-owned lifetime and does not retain a
selected task path between independent calls. A separate caller-owned session
can skip that repeated proposal generation. It cannot skip mathematical checks.

The session is tied to one immutable generation. Its exact typed keys include
source identity, normalized assumptions, a supported stateless built-in objective
and threshold, every query budget field, and the requested strategy. Arbitrary
objective callbacks use ordinary fresh execution. A session never retrieves a
path from a different generation or owner.

The first successful nonempty result retains a bounded observation key. A second
successful occurrence performs ordinary solving again and may retain its path.
Only a subsequent query uses the path as proposals. Observation keys and full
paths share one LRU entry limit and one logical-character limit. The first
observation reuses the already-generated canonical input document to bound the
retained key; it does not reserialize the source or traverse the whole witness.
Full path preparation pays the existing `RetainedGraph` traversal and every
replacement/eviction. Logical characters are not a JVM heap or RSS bound.

The retained provider supplies original moves to the original verifier. It marks
its successor relation incomplete; it cannot certify impossibility. The existing
source-only search constructs a fresh result and independently replays its whole
selected path. Unsuccessful, interrupted and over-budget executions grant no
retained answer authority. There is no silent second search with a replenished
budget after a failed replay. `Query.totalWork()` includes paid retention, and
`Query.solved()` checks that enclosing budget. The nested solver execution has a
narrower cost scope. Closing the session releases retained references, and zero
capacity preserves the original persistent-solver work contract.

See the [API guide](../../prepared-task-knowledge.md) and the production classes
`TaskKnowledgeQuerySession`, `RetainedTaskPath`, `CumulativeTaskKnowledge` and
`CheckedSchemaTaskSolver` in `regelsuche-learning`.

## Fixed panel and adaptive development history

The [v1 protocol](protocol.md) fixes three scenarios, the existing eight mixed
evaluation roles, their unchanged budgets/goals, independent seed acquisition
and strict preparation in each arm, a capacity of 32 entries / 4,000,000 logical
characters, three repetitions, and alternating arm order. Each arm pays a fresh
exact polynomial/occurrence-node audit in addition to the solver's own proofs.
An outer owned-JVM receipt includes startup, shutdown and JSON serialization.

V1 admitted every successful nonempty path immediately. Its repeated stream paid
back the logical admission cost, but distinct requests paid for unused full
witnesses. That public negative control motivated the
[v2 development follow-up](development-v2.md): only prepare a full path after an
actual repeated observation. V2 uses the same panel and does not overwrite any
v1 data. This is an adaptation to a known development panel, not a new independent
holdout or a preregistered confirmation of the adapted policy.

`COLD_ONE` means one block of eight role-specific queries. `REPEATED_SIXTEEN`
contains sixteen identical blocks, including initial observations and admission.
`DISTINCT_SIXTEEN` consistently renames variables for each block while preserving
the expression shapes and goals. Its already-small constant repeats but has no
retained nonempty path. Renaming tests an exact-query miss; it does not create an
independent mathematical family.

## Complete logical costs

The following totals are identical across all three repetitions. Each total
includes 15,810 units of seed acquisition, strict restoration/preparation, all
queries including lookup/admission/eviction, and the additional exact audit.
The audit contributes 3,816 units to the cold arm and 61,056 to each longer arm.

| Policy | Scenario | Persistent baseline | Reuse | Reuse minus baseline | Relative change |
| --- | --- | ---: | ---: | ---: | ---: |
| v1 immediate admission | Cold, 8 queries | 22,602 | 26,967 | +4,365 | +19.31% |
| v1 immediate admission | Repeated, 128 queries | 124,482 | 119,667 | −4,815 | −3.87% |
| v1 immediate admission | Distinct, 128 queries | 124,482 | 194,450 | +69,968 | +56.21% |
| v2 second observation | Cold, 8 queries | 22,602 | 22,651 | +49 | +0.22% |
| v2 second observation | Repeated, 128 queries | 124,482 | 120,334 | −4,148 | −3.33% |
| v2 second observation | Distinct, 128 queries | 124,482 | 125,287 | +805 | +0.65% |

V2's repeated query work is 43,468 units against 47,616 for persistent solving
(8.71% less before common setup/audit). Its first block costs 3,025 units, its
second 7,347 units including full path admission, and each of the remaining
fourteen blocks 2,364 units. Persistent solving costs 2,976 units per block.
Thus a warm block saves 20.56% of query work, but this is not the complete-stream
percentage. The first two blocks and all common costs remain in the totals.

Each v2 repeated arm retains six paths and has 84 later hits. Each cold and
distinct arm admits **zero full paths**. The distinct arm retains at most 32
observation keys, pays 64 evictions, and ends at 49,738 conservatively bounded
logical characters. It pays 677 units of observation/admission/eviction plus
128 query lookups. This does not promise a universal 0.65% upper bound: different
sources, assumptions and failure rates still need measurement.

## Observed elapsed time and CPU

All differences below are **persistent baseline minus reuse**. Positive values
mean reuse saved that resource; negative values mean reuse cost more. Every pair
includes its arm's acquisition, preparation, queries, final replay, shutdown and
audit. The outer JVM receipts are a wider scope and must not be added to these
arm totals. The exact nanosecond observations are retained in the JSON reports.

| Version | Repetition | Scenario | Work saved | Wall saved (ms) | CPU saved (ms) |
| --- | ---: | --- | ---: | ---: | ---: |
| v1 | 0 | Cold | -4,365 | +2238.774 | +1630.000 |
| v1 | 0 | Repeated | +4,815 | +0.406 | -510.000 |
| v1 | 0 | Distinct | -69,968 | +367.112 | +500.000 |
| v1 | 1 | Cold | -4,365 | -260.577 | -220.000 |
| v1 | 1 | Repeated | +4,815 | +551.015 | +970.000 |
| v1 | 1 | Distinct | -69,968 | -64.286 | +60.000 |
| v1 | 2 | Cold | -4,365 | +11.180 | +70.000 |
| v1 | 2 | Repeated | +4,815 | +37.095 | +90.000 |
| v1 | 2 | Distinct | -69,968 | -1.310 | +60.000 |
| v2 | 0 | Cold | -49 | +1088.821 | +1640.000 |
| v2 | 0 | Repeated | +4,148 | -225.722 | -620.000 |
| v2 | 0 | Distinct | -805 | +198.785 | +240.000 |
| v2 | 1 | Cold | -49 | -3.255 | -10.000 |
| v2 | 1 | Repeated | +4,148 | -14.953 | +10.000 |
| v2 | 1 | Distinct | -805 | -33.085 | -210.000 |
| v2 | 2 | Cold | -49 | +30.004 | +100.000 |
| v2 | 2 | Repeated | +4,148 | -242.922 | -380.000 |
| v2 | 2 | Distinct | -805 | -9.928 | -150.000 |

V1 has two of three repeated-stream pairs with both-resource savings. V2 has
zero of three: wall time is worse in all three, while CPU is marginally better
in one. Neither observation supports a reliable runtime guarantee. In particular,
the large first cold-pair wall/CPU savings coexist with *more* logical work, which
exposes shared-JVM order, startup and JIT effects. The study deliberately has no
extra warmup and uses alternating order. No favourable median is substituted for
the complete set of observations.

The owned v2 JVM used 6.330308 seconds of elapsed time and 16.728277 seconds of
user-plus-system CPU, with peak child RSS 310,692 KiB. Those figures cover the
whole eighteen-arm process and JSON output; they are not a single-arm speedup.
The v1 process took 10.953705 seconds / 18.712822 CPU seconds. Its RSS receipt is
a supervisor-wide child maximum, also used by the subsequent mixed-workload
process; the later process's receipt must not be read as its isolated RSS peak.
Cross-run differences do not isolate the admission policy's runtime effect.

## Exact quality and independent checks

Each version contains 18 arms, 1,584 query rows and 5,742 witness edges. All rows
have complete accounting and pass exact final equivalence, witness continuity,
per-edge equivalence, occurrence-node scoring and budget consistency checks.
Each block solves seven of its eight roles; the deliberately unsupported target
remains unsolved in both arms. All nine paired quality comparisons are
non-regressing. No unknown mathematical case is counted as a reuse gain.

`verify-report.py` additionally uses the existing Python `Fraction` polynomial
judge, independent of Java and SymPy, on every recorded row and edge. It also
recomputes scores, ledger sums, hit counts and paired quality/work differences.
Both v1 and v2 pass. This post-run audit is explicitly outside the measured Java
costs; the timed Java arm already contains its own additional exact audit.
The independent check is limited to this panel's frozen arithmetic grammar.

The behavioral tests exercise cold/second/warm behavior, exact source/goal/
assumption/strategy/all-budget binding, different generations and owners,
negative and empty results, tight budgets, interrupted calls, failing custom
callbacks, capacity and character eviction, disabled reuse and closed sessions.
A verifier-instrumented test observes two real verifier calls per witness edge
(admission and final replay), and a forged foreign-rule proposal is rejected by
the regular proof checker. The comparison test includes all six arms and checks
every result rather than asserting noisy timing thresholds.

The final strict-Java25 learning reactor and its dependencies passed 4,285 test
cases with zero failures/errors and one existing opt-in skip. The learning module
passed all 1,315 cases. `verification.json` records the command, per-module counts,
log hash and skip reason: the external observation metric test has no
`externalObservationMetricsOutput` system property. Counts use actual XML
`testcase` elements and Maven summaries; suite attributes alone omit 78 nested
cases that were executed and reported.

## The existing learning/adoption result remains negative

The unchanged `MixedTaskKnowledgeStudy` was run before the implementation and
again after v1, with 16 blocks and three repetitions. Both runs retain the same
full logical totals: 185,794 for prepared persistent reuse and 225,883 for
measured improvement. All three learner-promoted candidates receive `NO_GAIN`
from the mixed gate; the next attempt has `NO_NEW_SCHEMA` / `NO_CANDIDATE`.
All arms retain correct outputs and complete accounting. This is useful
non-regression evidence for the existing adoption path, not new transfer or
amortization evidence. V2 only changes the opt-in query session and its comparison;
it is not inserted into that original study.

## Reproduction and artifact bindings

Use strict Java25 and the repository's ordinary Maven setup:

```sh
mvn -B -ntp -pl regelsuche-learning -am test
```

The Java entry point is
`de.regelsuche.evolution.TaskKnowledgeQueryComparison NEW_SNAPSHOT_DIRECTORY 3`.
Run it with `-Xmx512m` and the learning reactor's test/runtime classpath, in a fresh
JVM whose stdout/stderr, elapsed time and process CPU are captured. The directory
must not already exist. Exact commands and classpaths from these runs are in the
reproduction receipts. The original study entry point is
`de.regelsuche.evolution.MixedTaskKnowledgeStudy NEW_SNAPSHOT_DIRECTORY 16 3`.

The offline check requires only the repository and standard Python:

```sh
python3 -B docs/research/task-query-reuse-2026-11-24/verify-report.py \
  docs/research/task-query-reuse-2026-11-24/v2-query-report.json.gz
python3 -B docs/research/task-query-reuse-2026-11-24/verify-report.py \
  docs/research/task-query-reuse-2026-11-24/v1-query-report.json.gz
```

| Artifact | Contents |
| --- | --- |
| `protocol.md`, `development-v2.md` | Frozen first protocol and explicitly adaptive second policy |
| `v1-query-report.json.gz`, `v2-query-report.json.gz` | Every arm, query, edge, quality result and actual cost |
| `v1-query-summary.json`, `v2-query-summary.json` | Complete arm ledgers and pair comparisons without query arrays |
| `v1-stdout.txt.gz`, `v2-stdout.txt.gz` | Original Java stdout, including any environment warning before JSON |
| `v1-reproduction.json`, `v2-reproduction.json` | Owned-process commands, costs, runtime, source and raw-output hashes |
| `v1-offline-audit.json`, `v2-offline-audit.json` | Additional independent check receipts |
| `verification.json` | Final strict-Java25 reactor result, exact case counts and existing skip |
| `mixed-v2-before-report.json.gz`, `mixed-v2-after-report.json.gz` | Unchanged existing study before and after the initial implementation |
| `mixed-v2-before-summary.json`, `mixed-v2-after-summary.json` | Existing study's ledgers and adoption decisions |

Local development commits in the receipts are provenance labels, not public
GitHub links. Source/report hashes bind the measured content independently of a
later publication commit identity. V2 documents its source-hash algorithm and
2,075-file scope. Runtime, public panel adaptation, small sample size and the
absence of observed v2 both-resource payback limit the claim to the declared
logical-work result and bounded query-reuse behavior. Existing default solver,
learning and adoption APIs do not enable this session implicitly.

### Preserved development source

The [source-history.bundle](source-history.bundle) retains both original local
development commits relative to the recorded base. It does not imply an external
preregistration. In a clone containing the base commit:

```sh
git bundle verify docs/research/task-query-reuse-2026-11-24/source-history.bundle
git fetch docs/research/task-query-reuse-2026-11-24/source-history.bundle refs/heads/codex/remediation-learning-20261124:refs/heads/reproduce-task-query-reuse
```

Use a separate worktree at the recorded V1 or V2 commit to reproduce that
implementation. The archived results remain tied to their original source
and artifact receipts.

The [V1 provenance addendum](provenance-v1.md) distinguishes the preserved source
snapshot from the historical measurement hash whose original calculation was
not retained. It does not retroactively validate that historical source hash.
