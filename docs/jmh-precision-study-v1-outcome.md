# Retained outcome of JMH precision study v1

The single authorized [PR #993](https://github.com/carstenartur/Regelsuche/pull/993) study, [run 34729477401, attempt 1](https://github.com/carstenartur/Regelsuche/actions/runs/34729477401), ended with `ERROR` in all three replicas and the analysis job. The preregistered incomplete-evidence rule gives **no selection**. No precision improvement or protocol cost ratio is established. This is an observer record from authorized job logs/API metadata, not a replay of the retained report.

The published head is `7d2bbb7d582666900f8eebaed5198fdc16c908b6`. The PR merge checkout is `95da11264519d64ef7785a703310c8143b5642ae`; its API-reported tree is `dc49fcda8921516185e1dbec31e5bfb1d8214524`, identical to the reviewed local source. The study policy SHA256 remains `236e6fc60fd7048a4f1e4683e5db0da79e4779c07469cd46f660103e11d51043`.

## Actual GitHub cost

All three fixed replicas started at attempt 1. Times below are UTC on 2026-09-13.

| Job | Job ID | Start | End | Duration | Conclusion |
| --- | --- | --- | --- | ---: | --- |
| JMH precision study replica 1 | 103649495280 | 01:01:38 | 01:03:10 | 92 s | failure |
| JMH precision study replica 2 | 103649495290 | 01:01:38 | 01:03:05 | 87 s | failure |
| JMH precision study replica 3 | 103649495351 | 01:01:39 | 01:02:52 | 73 s | failure |
| Retained JMH precision study analysis | 103649683216 | 01:03:13 | 01:03:38 | 25 s | failure |

The jobs consumed **277 seconds (4 minutes 37 seconds)** in summed runner wallclock: 252 seconds for replicas and 25 seconds for analysis. The elapsed span from first replica start to analysis completion was 120 seconds. These values include job setup/cleanup, exclude billing rounding and unrelated CI authorities, and do not measure candidate protocol runtime growth.

## Original retention and access boundary

Each replica logged successful upload of six files. The analysis job logged successful download of all three replica archives with matching SHA256 digests, then uploaded its error report. The original artifacts remain retained; their metadata and exact decoded job logs are captured separately.

| Original artifact | Bytes | GitHub-reported archive SHA256 |
| --- | ---: | --- |
| [jmh-precision-study-v1-analysis](https://github.com/carstenartur/Regelsuche/actions/runs/34729477401/artifacts/10308427665) | 1294 | `6665bf67b351e9b3d53e4d1634f4c872bbf36565c973a08b5342b22ddd8f7e00` |
| [jmh-precision-study-v1-replicate-1](https://github.com/carstenartur/Regelsuche/actions/runs/34729477401/artifacts/10308387772) | 9980 | `ae5fdcf33e20d7839354c90a037aff53fa3b99e8f47c929e341e69a43f97391b` |
| [jmh-precision-study-v1-replicate-2](https://github.com/carstenartur/Regelsuche/actions/runs/34729477401/artifacts/10308808252) | 9980 | `6c94a5b9c77170a74b6395af0a0fac3b5a73654a0e0b11243ec25dec9b0163e8` |
| [jmh-precision-study-v1-replicate-3](https://github.com/carstenartur/Regelsuche/actions/runs/34729477401/artifacts/10309087595) | 10052 | `9449f64604dea395ae494aff35942cc2e51704b4966fcf51a8d5178775a4ebb1` |

All four archives were materialized by the authorized artifact connector. Reading the fresh analysis download URL locally returned HTTP 403. That boundary was respected; no alternate route or subsequent local URL request was attempted. Original ZIP bytes, manifest reasons, raw row inventories, boot identities and report replay are therefore **not independently verified here**. Counts of `LOW_PRECISION`, `INCONCLUSIVE`, failed cells and raw rows remain unknown, not zero. The observed collection errors cannot be presented as a precise or successful null result.

## Admission failure reproduced locally

A separately authorized, single `:app:jmhJar` build on the identical published tree completed successfully in 79.053 seconds, under the bounded supervisor, with no remaining descendants or observed child failures. No JMH benchmark was executed.

The resulting `app/build/libs/app-jmh.jar` has 64,751,342 bytes, SHA256 `8691afc2f5ef3c3fc408343702c4cf5ff72cece1ad48ab5f9e16940d215f4a97`, 39,301 ZIP entries (2,234 directories and 37,067 files) and 24 duplicate file-entry names. Examples include license, service-provider and multi-release metadata. Calling the unchanged v1 `jar_identity()` on this actual artifact raises `ValueError: duplicate jar entry`. The full ZIP listing, build log, process receipt and admission result are retained as local controls. This proves the local admission defect. It does not prove the unread original manifests recorded that same reason. The hosted successful jar-build summaries, early `ERROR` and six-file uploads are consistent with it.

## Preregistered criteria and remaining acceptance gaps

Complete three-replica, seven-protocol, 29-benchmark inventories are not established. The positive baseline noise count, halved candidate `LOW_PRECISION` count, no family deterioration, lower median/p90 error ratios, all-passed candidate ratchets, at-most-3.5-times median protocol runtime and semantic/ranking comparability cannot be evaluated. No candidate qualifies for selection on this evidence.

Issue #981 remains open: an empirical precision improvement and justified CI runtime growth are still missing. The harness/report design exposes raw score, error, precision and decision fields, but this failed corpus provides no locally verified measurement comparison. A new production execution authority/baseline has not been selected. Historical v2/v3 thresholds and meanings remain unchanged; shared-runner observations do not establish cross-hardware performance or statistical proof.

Retain this v1 outcome. Prepare a separately versioned jar-identity correction that reads each actual `ZipInfo` payload and binds duplicate names and order, with mutation controls and the existing real jar. Any further measurement requires a new explicit study revision with the same statistical criteria. No second run, rerun, synchronize measurement, adoption, threshold change or merge follows from this record.
