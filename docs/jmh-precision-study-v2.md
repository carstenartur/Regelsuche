# Finite JMH precision study v2

This successor corrects collection admission and error retention after the
[retained v1 failure](jmh-precision-study-v1-outcome.md). Run 34729477401 remains
`ERROR` / `INCOMPLETE_EVIDENCE_NO_SELECTION`, with 277 seconds of observed study
job wallclock and no chosen protocol. The original manifests were not locally
byte-verified because their signed artifact download returned 403. Its exact
raw-row counts and original jar-error strings remain unknown. New local controls
neither repair those artifacts nor turn them into completed measurements.

The successor is prepared for one separately reviewed PR-opened launch. No v2
measurement, precision improvement or production protocol is claimed here.
The [v1 preregistration](jmh-precision-study.md) remains the source of the unchanged
matrix, budgets, numerical rules and interpretation boundaries.

## Version and compatibility contract

| Contract | Retained v1 | Separately collected v2 |
| --- | --- | --- |
| Study policy | `jmh-precision-study-policy-v1.json`, SHA-256 `236e6fc60fd7048a4f1e4683e5db0da79e4779c07469cd46f660103e11d51043` | `jmh-precision-study-policy-v2.json`, SHA-256 `cd296629d75a37e1a4266550093e8a81573a4450e79be1dadebd72e24233693f` |
| Study ID | `issue-981-shared-runner-precision-v1` | `issue-981-shared-runner-precision-v2` |
| Policy / replicate / report schema suffix | `/v1` | `/v2` |
| Jar content identity | Unversioned v1 descriptor; duplicate names rejected | `regelsuche.quality.jmh-jar-identity/v2`; every logical entry bound in original order |
| Policy copies in every replica | Threshold v2, decision v3, study v1 | The same three, plus study v2 |
| Original execution | Run `34729477401`, attempt `1`; retained failure | A new, explicitly reviewed qualifying PR event |
| Production regression authority | Existing threshold v2 and decision v3 | The same unchanged production policies and authority |

`jmh_precision_study_v2.py` checks the exact new policy hash, loads and validates
the exact old policy, then requires complete structural equality after applying
only the listed version/launch/jar identities and predecessor-run reference.
Every protocol, fixed order, benchmark identity, family, unit, limit, multiplier,
JVM setting, cost budget and adoption field must still equal the original.
A changed statistical contract therefore fails admission rather than silently
becoming a different experiment under the v2 name.

The v1 runner, report, numerical helpers, process supervisor and policy remain
byte-identical. V2 directly reuses the existing matrix, command, JSON, numerical
verification, summary, provenance and private Linux subprocess supervision
helpers. It does not patch v1 globals. The new entrypoints own their separate
schemas, four policy inputs, jar admission and typed GC validation. Replay
rejects a v1 manifest, old study ID, absent/old jar schema or missing policy copy.
The source revision/tree must equal the measured checkout, as before.

## Collection corrections

The [jar identity v2 contract](jmh-jar-identity-v2.md) reads actual `ZipInfo`
entries from one immutable archive snapshot. It binds every central-directory
entry, including duplicate names and order. Exact physical-header aliases are
admitted only when their metadata and existing bounded-read limits agree;
conflicting aliases, partial overlaps and CRC corruption remain errors. It never
replaces duplicate entries by a last-name lookup. The retained raw jar SHA is
separate from the ordered content SHA.
Replay requires equal schema, content SHA and both entry counts across replicas;
raw jar hashes and paths may differ. Each counted duplicate name requires at
least two logical entries, so impossible inventories are rejected individually.

The one authorized local jar build on the original published tree produced
39,301 ZIP entries, including directories, with 24 duplicate names. Both local
Python 3.12 implementations reproduce jar identity v2 and reject manipulated
aliases/overlaps. These are admission controls, with no JMH execution. CPython's
private read-only `_end_offset` compatibility boundary is explicit in that
contract; a runtime without the required safe boundary rejects the jar.

A non-object `gc.alloc.rate.norm` metric now raises a typed validation error
before the unchanged numerical verifier. The runner retains its original JSON,
digest, process/log receipt and failed-cell diagnostic, finishes the remaining
bounded cells without retry, and writes the final `ERROR` manifest. The report
retains that failure and cannot qualify the partial corpus. Controls exercise
`null`, arrays, numbers and strings; a real synthetic-process pipeline writes
malformed JSON data and proves final error retention.

Search event units remain `#`. The retained JMH 1.36 output from PR #986 contains
that unit for all six counters in all four search benchmarks. Its 29-row result
JSON has SHA-256 `41e0a64064093ae2d78ea783325f635c3e53e14144bbb1e24f4d95b6ea422420`.
The generated JMH 1.36 average-time source from the authorized jar build also
constructs `ScalarResult` counters with `#` and `AggregationPolicy.SUM`.
`events/op` would reject those actual inputs. This is a format check, not reuse
of the historical scores as successor-study evidence. Normalization by the
`searches` event count and the existing work-parity tolerance remain unchanged.

The separate v1 test-only readiness correction synchronizes the detached-child
cleanup fixture after its
first flushed write and installed TERM-ignore handler. It changes no production
process deadline, measurement policy or historical runner.

## Exact one-time launch and cost

The existing CI workflow gains `jmh-precision-study-v2` and
`jmh-precision-study-v2-report`. The complete earlier workflow is an unchanged
prefix. There is no third workflow and no new study dependency on the five
required `ciCheck` authorities.

The policy, runner and parsed YAML contract bind the same launch:
`carstenartur/Regelsuche`, same head repository,
`codex/issue-981-jmh-precision-study-v2`, `pull_request` action `opened`,
`run_attempt=1`, and job `jmh-precision-study-v2`. Exactly three fixed replicas
`[1, 2, 3]` run independently with `fail-fast: false`. No synchronize, reopened,
manual rerun or original v1 branch event qualifies. Opening another PR from this
branch requires a new preregistration decision. The coordinator opens the one
new PR only after independent review of this exact implementation and policy.

The PR merge SHA is the executed and replayed source identity. Do not update the
head during collection: existing CI concurrency may cancel the original run,
which remains an incomplete retained study. The full original v1 launch gate is
unchanged and receives no additional authorized execution.

The seven protocols still yield 9 serial launcher cells per replica, 27 total,
and 609 benchmark rows. Nominal iteration time remains 1,809 seconds per replica
and 5,427 across three runners. The complete replica budget, beginning before
checkout/tool setup, remains 3,600 seconds; build and individual cells are each
capped at 900 seconds, and a cell is further bounded by
`2 * nominalSeconds + 60` and the remaining replica time. There are zero retries.
Each replica job has 75 minutes including upload allowance, and analysis has
5 minutes: at most 230 additional runner-minutes under those workflow limits.
Retain actual GitHub job timestamps and total runner cost separately from
collection elapsed time and protocol costs. Queue time, billing rounding and
normal CI are outside the measurement cost ratio.

## Retention and decision

All three original replica directories upload even after failure under distinct
`jmh-precision-study-v2-replicate-N` artifact names. Analysis downloads those
three fixed names into separate directories and runs even after a failed
collection/download. Missing, partial, duplicate or mutated evidence yields
`INCOMPLETE_EVIDENCE_NO_SELECTION`; an available subset never qualifies.

The canonical v2 report is uploaded as `jmh-precision-study-v2-analysis`. Its
exact JSON is also printed between `BEGIN_JMH_PRECISION_STUDY_REPORT_V2` and
`END_JMH_PRECISION_STUDY_REPORT_V2` in the authorized job log, after the artifact
files are written. Collection errors are also printed after the final manifest.
This makes bounded analysis and failure details observable through job logs;
raw-file byte verification still requires the retained artifacts. It does not
bypass the access boundary of the original v1 downloads.

All adoption criteria remain preregistered: complete inventories and provenance,
positive control `LOW_PRECISION`, at most half as many low-precision rows with no
family increase, strictly lower median and p90 relative error, all candidate
ratchets passed, at most 3.5 times median protocol wallclock, and semantic work
parity. Manual ranking/semantic review and a separately versioned production
execution authority/baseline remain necessary. No errors are pooled and no
`INCONCLUSIVE` outcome is averaged into a pass.

`selectedProtocol` is always null. Zero control low-precision rows preserve
`NO_LOW_PRECISION_IMPROVEMENT_ESTABLISHED`; complete evidence with no eligible
protocol preserves `NO_PROTOCOL_QUALIFIES`; eligible protocols still require
manual review. These are distinct from incomplete/error evidence. No winner or
new threshold is chosen as part of the successor implementation.

## Focused controls and replay

The existing normal Gradle `check` includes the cheap
`testJmhPrecisionStudyV2Harness` and `testJmhJarIdentityV2` controls. They start
synthetic Python executables, never a JVM or benchmark. The v1 controls remain.
The parsed Maven YAML contract rejects broadened launch conditions, partial
matrices, missing attempt limits and discarded failure artifacts.

```bash
python3 -B scripts/test-jmh-precision-study-v2.py
python3 -B scripts/test-jmh-jar-identity-v2.py
```

From the exact measured checkout, replay the three downloaded directories:

```bash
python3 -B scripts/report-jmh-precision-study-v2.py \
  --replicate retained/jmh-precision-study-v2-replicate-1 \
  --replicate retained/jmh-precision-study-v2-replicate-2 \
  --replicate retained/jmh-precision-study-v2-replicate-3 \
  --output build/reports/jmh-precision-study-v2/replayed
```

Output directories cannot be reused. Exit status remains 2 for incomplete or
invalid collection, 1 for any retained `FAILED`/`INCONCLUSIVE` ratchet, and 0 only
for complete all-passed data. No exit status selects a production protocol.
