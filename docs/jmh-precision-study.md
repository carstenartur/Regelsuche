# Finite JMH precision study v1

Issue [#981](https://github.com/carstenartur/Regelsuche/issues/981) asks whether a
more informative measurement protocol can replace the current short shared-runner
contract at a justified CI cost. This change preregisters the experiment and its
acceptance rules. **No shared-runner study data or selected production protocol
is claimed here.** Measurements, selection review and any subsequent production
authority migration remain separate steps.

## Preregistration

`config/quality/jmh-precision-study-policy-v1.json` owns the complete finite
matrix, ordering, budgets, JVM properties and adoption rules. Its exact SHA-256
is bound by `scripts/jmh_precision_study_v1.py`, and its bytes are LF-normalized.
Changing any of those rules after data collection requires a new study revision;
an incomplete or unsuccessful study is retained under its original identity.

The existing `jmh-regression-policy-v2.json` and
`jmh-regression-decision-policy-v3.json` remain byte-identical and content-bound.
All 29 benchmark identities, families, units, maximum scores and multipliers
remain unchanged. Candidate results use new **study v1** schemas; they are never
submitted to a historical verifier with rewritten execution metadata or appended
as same-contract v2/v3 history points.

Every protocol measures the complete inventory: CORE (10, `us/op`), REWRITE_PROGRAM
(15, `ms/op`) and END_TO_END_SEARCH (4, `ms/op`). Java 25, JMH 1.36, average time,
one thread, unit annotations, benchmark code and inputs remain fixed. The runner
explicitly reproduces the ordinary Gradle fork's UTF-8, locale and temporary
directory properties. Inherited `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and
`_JAVA_OPTIONS` are rejected. The complete observed JDK patch/VM identity must
match across the three replicas; runner image identities must also match.
Three different Linux boot IDs distinguish runner instances. Runner names are
retained but are not required to be unique, as the
[GitHub variable reference](https://docs.github.com/en/actions/reference/workflows-and-actions/variables)
explicitly permits repeated names.

| Protocol | Warm-up / measurement / forks | Warm-up / measurement time | Profiler | Nominal seconds per replica |
| --- | --- | --- | --- | ---: |
| `baseline` | 2 / 3 / 1 | 1 s / 1 s | none | 145 |
| `more-measurements` | 2 / 8 / 1 | 1 s / 1 s | none | 290 |
| `more-warmup` | 6 / 3 / 1 | 1 s / 1 s | none | 261 |
| `more-forks` | 2 / 3 / 2 | 1 s / 1 s | none | 290 |
| `longer-measurements` | 2 / 3 / 1 | 1 s / 2 s | none | 232 |
| `family-contract` | CORE 2/3/1; REWRITE 4/6/2; SEARCH 6/6/2 | 1 s / 1 s | none | 446 |
| `gc-profile` | 2 / 3 / 1 | 1 s / 1 s | gc | 145 |

The first four candidates isolate one factor each. Longer iterations include the
very fast synthetic benchmarks without selecting a subset after inspection. The
family candidate is a preregistered combined protocol for comparing efficiency;
it cannot identify the individual causal contribution of each changed factor.
The current latency ratchet already runs without a profiler, and the existing
allocation slice runs separately. `gc-profile` tests the effect of combining
profiling with the full inventory; it does not change either existing lane.

Exactly three independent GitHub-hosted Ubuntu 22.04 runner jobs execute all
seven protocols on one checkout, in one workflow run and attempt. Each job runs
its cells serially; the family protocol requires three cells, giving nine JVM
launcher calls per replica and 27 across the study. Every benchmark still has
its declared independent JMH forks. The fixed protocol orders are:

1. baseline, more-measurements, more-warmup, more-forks, longer-measurements, family-contract, gc-profile;
2. more-warmup, more-forks, longer-measurements, family-contract, gc-profile, baseline, more-measurements;
3. gc-profile, family-contract, longer-measurements, more-forks, more-warmup, more-measurements, baseline.

These orders put the control at the beginning, middle and end. They do not remove
all order or host effects. There is no adaptive parameter change, outlier deletion,
successful-subset selection, retry or additional replica inside this study.

## Cost and adoption rules

Nominal iteration time is **1,809 seconds (30:09) per replica**, **5,427 seconds
(90:27) across three runners**. This excludes JVM startup, benchmark setup,
compilation and provisioning. The runner records those costs separately and
retains total wall-clock time beginning at the workflow's first timestamp step.
Each replica has a 3,600-second budget including prior checkout/tool setup and
the jar build. The build is capped at 900 seconds. Each cell is capped at the
smaller of `2 * nominalSeconds + 60`, 900 seconds and the remaining replica budget.
Descendant termination/reaping can require additional time; report validation
allows two seconds for shutdown/bookkeeping. A 75-minute workflow job timeout
leaves time to upload failed-run artifacts. Three parallel jobs bound additional
workflow wall-clock time; the sum of runner costs remains visible.
The separate report job is capped at five minutes. The workflow therefore caps
the additional study jobs at 230 runner-minutes, including the upload allowance;
queue time and the unchanged normal CI jobs are outside that bound. Retained
replica elapsed time ends after collection and excludes post-collection artifact
upload and the report job. Retain GitHub's completed job timings alongside these
artifacts when reporting total observed CI cost; billing rounding is not a JMH
measurement.

Protocol costs are measured JVM-launcher elapsed times, including startup/setup,
summed over all its cells. A one-time build/provisioning cost is reported per
replica, without allocating it differently to selected candidates. Adoption uses
the ratio of candidate and control **median protocol wall-clock costs**.

A candidate is eligible for manual review only when all these preregistered
conditions hold:

- All three complete, valid 29-benchmark inventories exist on the same source,
  jar contents, VM and declared runner image; there are no process or evidence errors.
- The control has at least one `LOW_PRECISION` row. Across the 87 rows per protocol,
  the candidate has at most half as many, with no increase in any family.
- Both median and nearest-rank p90 `scoreError / score` are strictly lower than
  control. Reports also retain these summaries separately for each 29-row replica.
- Every candidate ratchet outcome is `PASSED`. An `INCONCLUSIVE` result is never
  rescued by averaging it with other replicas.
- Median protocol cost is at most 3.5 times control, and all fixed budgets hold.
- Observed per-search work counters match the paired control for all search
  benchmarks. Work counts are normalized by the `searches` event count, with the
  same finite `#` units; the numerical equality tolerance is `1e-9` absolute/relative.

The report always leaves `selectedProtocol` null. An eligible candidate still
requires documented review of within-family ranking changes, score ratios and
semantic behavior against both the paired control and frozen baseline, plus a
justified CI cost decision. Pairwise order inversions and first/last measurement
ratios are diagnostics, not new statistical tests or mathematical guarantees.
The frozen threshold policy does not retain search work counters, so it cannot
establish historical semantic parity by itself; the report states that gap.

With no low-precision control rows the outcome is
`NO_LOW_PRECISION_IMPROVEMENT_ESTABLISHED`. With no eligible candidate it is
`NO_PROTOCOL_QUALIFIES`. Missing/duplicate replicas, aborted jobs, build failures,
timeouts, empty results, invalid metadata or changed artifacts produce
`INCOMPLETE_EVIDENCE_NO_SELECTION`. Retained process errors remain available;
incomplete protocol subsets never qualify. A new attempt requires an explicit
new preregistration/retention decision rather than silently replacing these data.

## Execute once through a pull request

The additive study jobs belong inside the existing `.github/workflows/gradle.yml`:
a matrix with `replicate: [1, 2, 3]`, `fail-fast: false`, three independent
`ubuntu-22.04` jobs and the repository's existing pinned checkout, Temurin 25,
Gradle setup and artifact actions. The launch scope is bound in the study policy
and checked against the original GitHub event before any Java process starts:
`carstenartur/Regelsuche`, the same repository's
`codex/issue-981-jmh-precision-study` branch, `pull_request` action `opened`,
`run_attempt=1`, and job `jmh-precision-study`. Later pushes, reopened PRs and
manual reruns do not launch more measurements. This is one designated study PR;
opening another PR from that branch requires a new preregistration decision.
The runner retains the PR number, head commit and launch metadata; replay binds
all three replicas to the same launch. Checkout uses the PR merge commit recorded
by `GITHUB_SHA`, so the report must be replayed from that measured commit.
The existing five required authorities, `ciCheck` and maximum-two-workflow
contract remain in force; the study is not a default `ciCheck` dependency.

[GitHub's event reference](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request)
defines the PR merge SHA and default PR activity types.
[Matrix failure handling](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#jobsjob_idstrategyfail-fast)
is disabled for this matrix so a failed replica cannot automatically cancel the
other two. Do not update the PR head during collection: existing workflow
concurrency can cancel the original run, which is retained as incomplete.

The first matrix-job step, before checkout and tool setup, records:

```bash
echo "REGELSUCHE_STUDY_JOB_START=$(date +%s)" >> "$GITHUB_ENV"
```

After provisioning, with the replica value supplied by the matrix:

```bash
python3 -B scripts/run-jmh-precision-study-v1.py \
  --replicate "$REGELSUCHE_STUDY_REPLICATE" \
  --job-start-epoch "$REGELSUCHE_STUDY_JOB_START" \
  --output "build/reports/jmh-precision-study-v1/replicate-$REGELSUCHE_STUDY_REPLICATE"
```

The script builds `:app:jmhJar` once with the existing wrapper and at most two
Gradle workers. It refuses a dirty checkout, prebuilt jars on hosted runners and
any existing output directory. It executes every declared cell despite an earlier
benchmark ratchet failure; only a failed build/probe or exhausted budget prevents
subsequent launches. Failed processes are retained without retry. Each command has
a private Linux subreaper supervisor: detached Gradle/JVM children remain owned
after their parent exits and may finish within the original command deadline.
On timeout, owned descendants are terminated and reaped before receipts and log
hashes are written, including detached descendants that ignore TERM. A supervision
or cleanup failure prevents further launches. PID translation uses the supervisor's
`NSpid` depth and verifies `/proc` identity against pidfd metadata before signalling;
raw `/proc` identifiers are never used as signalable PIDs. The supervisor cannot
collect unrelated children of the calling runner/test process.
Observed exit codes of adopted children are retained; a successful parent cannot
hide an adopted child's nonzero exit. Such a command is `CHILD_PROCESS_ERROR`,
and replay also rejects retained child failures under a relabeled successful
receipt. Child exit statuses already consumed by their own parent are outside
this observation boundary and are not reconstructed.

Upload the **entire** replica directory using an `always()` artifact step, a
unique artifact name per replica, and `if-no-files-found: error`. Retain failed
study jobs as well as successful ones. A cancelled job may leave an incomplete or
missing artifact; that is an incomplete study, not permission to drop a replica.
The existing required regression/CI authorities continue to run unchanged.
The five-minute `jmh-precision-study-report` job downloads the three named
artifacts into [separate directories under the pinned download contract](https://github.com/actions/download-artifact/blob/3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c/README.md)
and runs replay even when a download or
collection failed. Its canonical error/null-outcome report is uploaded as
`jmh-precision-study-v1-analysis`; an incomplete corpus cannot qualify a protocol.

Download all three directories and check out their measured `sourceRevision`.
Replay from those bytes, without launching a benchmark:

```bash
python3 -B scripts/report-jmh-precision-study-v1.py \
  --replicate retained/replicate-1 \
  --replicate retained/replicate-2 \
  --replicate retained/replicate-3 \
  --output build/reports/jmh-precision-study-v1/replayed
```

The runner and report exit 2 for incomplete/invalid collection, 1 when any retained
ratchet is `FAILED` or `INCONCLUSIVE`, and 0 only when collection is complete and
all ratchets pass. Exit 0 does not select or adopt a protocol. The replica order
given to the replay command does not change canonical JSON output. Report/output
directories cannot be reused to overwrite earlier evidence.

## Evidence and verification

Each replica retains exact original JMH JSON, per-cell combined stdout/stderr,
command arguments, exit status, monotonic elapsed seconds, UTC timestamps, source
revision/tree, raw and timestamp-independent jar content digests, JVM version
output, runner/CPU/memory/image metadata and copies of all three policy files.
SHA-256 binds raw JSON and logs to receipts. Duplicate JSON keys, symbolic or
escaping evidence paths, raw sample loss, inventory drift, wrong units, changed
fork/iteration/time/batch parameters and inconsistent raw means fail closed.
The reporter re-derives cell summaries and validates commands, policies and costs;
it never trusts a saved summary instead of raw JMH data.

JMH keeps primary per-fork/per-iteration values in `rawData`; warm-up iteration
output remains in the unedited logs. Scores, `scoreError`, raw samples and profiler
secondary metrics are retained before any analysis. The canonical report retains
every native-unit score/error and precision/ratchet outcome, allocation `B/op`,
family summaries and descriptive rankings. No cross-family raw-unit ranking is
created. Cross-replica medians are descriptive only: errors are not divided by
the square root of replica count and no pooled confidence interval is invented.
Per-benchmark aggregation propagates the worst individual decision.

Run the cheap process/evidence controls with:

```bash
python3 -B scripts/test-jmh-precision-study-v1.py
./gradlew --no-daemon --no-configuration-cache testJmhPrecisionStudyHarness
```

The Gradle task is part of normal checkout verification and launches no JVM or
benchmark. Controls create temporary synthetic executables and git checkouts;
their output is explicitly `LOCAL_CONTROL` and cannot qualify a protocol.
They cover real failing processes, orphaned children, timeouts, corrupt jars,
missing raw results, artifact mutation, decision boundaries and deterministic
replay. Linux process controls run in the declared hosted-runner environment and
are explicitly skipped on other operating systems; policy/measurement controls
remain portable. Existing v2/v3 characterization remains unchanged.

## Interpretation and any later migration

JMH's [forking sample](https://github.com/openjdk/jmh/blob/1.36/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_12_Forking.java)
and [run-to-run sample](https://github.com/openjdk/jmh/blob/1.36/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_13_RunToRun.java)
explain why independent JVM launches matter. Its
[profiler sample](https://github.com/openjdk/jmh/blob/1.36/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_35_Profilers.java)
describes profiler tradeoffs. The pinned
[JSON writer](https://github.com/openjdk/jmh/blob/1.36/jmh-core/src/main/java/org/openjdk/jmh/results/format/JSONResultFormat.java)
and [result implementation](https://github.com/openjdk/jmh/blob/1.36/jmh-core/src/main/java/org/openjdk/jmh/results/Result.java)
define retained metadata/raw data and JMH's reported error. These sources motivate
controlled measurement; they do not establish that any candidate will win.

After complete real data and review, an adoption change must introduce its own
versioned execution authority and baseline, bind the selected contract and retained
artifact digests, and preserve all frozen maximum scores and multipliers. It must
keep v2/v3 readers, policies and historical evidence reproducible. Study summaries
cannot authorize silently rewriting those old formats or tolerances.

The supported claim is a more informative finite **shared-runner ratchet** for
this checkout, workload and runtime. It is not cross-hardware absolute performance,
proof of unchanged mathematical behavior, general scalability, or statistical
proof. `LOW_PRECISION` remains visible even if its incidence improves.
