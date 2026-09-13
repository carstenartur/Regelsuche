# Retained outcome of precision study v2

The preregistered, once-opened study completed in
[run 34733521127](https://github.com/carstenartur/Regelsuche/actions/runs/34733521127),
attempt 1, on measured merge revision
`acee3ee3cb808956687ba3fef954a281533fb0ac`. All three collectors, the analysis
and the five ordinary CI authorities succeeded. The study contains the complete
seven-protocol, three-replicate corpus: 609 benchmark rows, 87 per protocol.
There was no repeated measurement or changed acceptance threshold.

The analysis job verified the retained replica inputs before writing its report.
The exact JSON printed between the documented markers in that completed job log
is retained in [compressed form](evidence/jmh-precision-study-v2-run34733521127-report-from-ci-log.json.gz).
This is explicitly a log-derived report. The original artifact download again
returned HTTP 403 locally; no local byte verification of the uploaded ZIP or
independent replay of its original raw files is claimed. The
[review receipt](evidence/jmh-precision-study-v2-run34733521127-review.json)
records report hashes, original artifact identities and this access limitation.

## Measured precision and cost

Relative error means the reported `scoreError / score`, with the frozen
descriptive median and p90 calculation across each protocol's 87 rows.
Wallclock is the median of the three protocol runs, excluding setup and build.

| Protocol | LOW_PRECISION / 87 | Median relative error | p90 relative error | Median seconds | Cost/control | Eligible for review |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| baseline | 20 | 0.199414 | 2.795201 | 155.304 | 1.000 | Control |
| more-measurements | 0 | 0.022087 | 0.241444 | 300.720 | 1.936 | Yes |
| more-warmup | 0 | 0.080096 | 0.201663 | 271.577 | 1.749 | Yes |
| more-forks | 2 | 0.077612 | 0.507894 | 310.011 | 1.996 | Yes |
| longer-measurements | 11 | 0.101065 | 1.359398 | 242.047 | 1.559 | No |
| family-contract | 2 | 0.024664 | 0.303873 | 462.949 | 2.981 | Yes |
| gc-profile | 18 | 0.153261 | 5.171145 | 155.767 | 1.003 | No |

All 609 rows retain their raw score, error, precision status and fail-closed
ratchet outcome; no row is FAILED or INCONCLUSIVE in this finite corpus.
Longer measurement time misses the preregistered halving criterion. GC profiling
misses that criterion, raises a family's low-precision count and does not lower
p90 relative error. Those negative outcomes remain in the report.

Actual GitHub job start/completion timestamps give 1,993, 1,939 and 1,988 seconds
for the three collectors, plus 15 seconds for analysis: **5,935 job-seconds
(98 minutes 55 seconds)**. The parallel wallclock span is 33 minutes 32 seconds.
These include the study jobs' setup, build and upload; they exclude queueing,
billing rounding and ordinary CI. The preregistered ceiling was 230 additional
runner-minutes.

## Descriptive ranking review and remaining authority

Every eligible protocol preserves CORE and END_TO_END_SEARCH point-estimate
ordering against its corresponding control. REWRITE_PROGRAM has point-order
inversions: respectively 3/5/0 for more-measurements, 1/4/2 for more-warmup,
4/3/2 for more-forks and 4/2/3 for family-contract. Each inversion has overlapping
reported score intervals in at least one of the compared protocols. This is a
descriptive check, not a test of significance or a proof of equivalent behavior.

All measured search-work counters satisfy the frozen within-study parity check.
The report separately states that semantic comparability to historical frozen
work is not established: the historical policy did not bind those counters.
The replicas have distinct boot identities and matching ordered JAR content
identity, while their raw JAR hashes and shared-runner hardware differ.

The measured candidates justify further adoption review. More measurements
gives zero LOW_PRECISION rows and the smallest global median relative error;
more warmup also gives zero with lower cost and a smaller p90. These observations
do not silently choose a production contract. `selectedProtocol` remains null,
and the original v2 threshold policy and v3 decision authority remain unchanged.
Original raw-artifact replay and a separately versioned production execution
authority/baseline remain outstanding for issue #981. The result is bounded
shared-runner ratchet evidence, not cross-hardware absolute performance or
statistical proof.
