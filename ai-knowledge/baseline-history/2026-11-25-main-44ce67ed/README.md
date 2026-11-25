# AI Knowledge evidence for accepted main 44ce67ed

The reference comes exclusively from accepted main
`44ce67edab538c5c5a69f992e75f3f222ec4fd72`, tree
`407fa19621be08d47b4bdc8641d0f9e4858f8b83`.
[Original main CI 45655429380](https://github.com/carstenartur/Regelsuche/actions/runs/45655429380)
passed all fourteen mandatory source authorities, aggregate `ciCheck`, and report
publication. The four optional JMH study jobs were skipped. The
[qualification record](ci-qualification.json) binds the required jobs to the
workflow at that exact commit; all sixteen mandatory jobs completed successfully.

## Original evidence and reference

The original `quality-acceptance-evidence` artifact `15048345801` is 29,212,500
bytes. Its downloaded SHA-256 matches the independent GitHub API digest:
`0c13da09a4127a66fa13f0e6e6357da0113079d313542fc4b9314e510c37493d`.
All 293 indexed file lengths and hashes were verified. All 64 tracked source
entries also match their exact accepted Git blobs; the other 229 entries are
generated reports. The index binds a clean checkout of the accepted revision.
[Artifact verification](artifact-verification.json) retains every file binding.
Execution authority comes from the successful job graph, separately from index
completeness.

The byte-preserved [native check](accepted-check.json) passed its absolute and
trend gates with zero claim failures. The [native complexity report](accepted-complexity.json)
measures **905,475** estimated context tokens, concept radius **125**, and
normalized context debt **13.22**. Against the [previous reference](previous-baseline.json)
this is **+14,490** tokens and **+2** radius units, within the unchanged
15,000-token and three-radius limits.

The 766,258,160-byte `repository-verification` artifact `15045453210` exceeds the
download tool's 536,870,912-byte limit and was **not downloaded or verified**.
The available quality artifact contains the original native check and complexity
reports, but no metrics snapshot. Following the existing accepted-main protocol,
the [snapshot](metrics-snapshot.json) is projected from the complete original
complexity report by unchanged released v0.1.10 `TrendAnalyzer.snapshot` and
`StableIo.writeJson`. Its SHA-256 is
`6d9d7df9fdbcb5873753401166494d8fe605e4d99cafd09322f9c88ed692e16d`.
The active `ai-knowledge/complexity-baseline.json` is a byte-for-byte copy.
No candidate values enter the reference.

## Original rejection and unchanged replay

The original [PR #1394 Gradle job](https://github.com/carstenartur/Regelsuche/actions/runs/45674833926/job/139419894086)
tested merge `728ee8612c2aa38142ac0ad4dc6179d9768c4458`, whose tree equals PR head
`5f25a206c2afae32c8d46aa6fb1711de22dbdbc9`. The
[checkout excerpt](rejected-checkout-log.txt) and commit API bind its accepted-main
parent and exact source tree. Its original artifact `15051506541` matches API
SHA-256 `14ea52bdedcdacaa110bf8d0e771bb3152429840786094ce9ec09bfda51a8a23`.

The original [check](rejected-check.json), [complexity](rejected-complexity.json),
[snapshot](rejected-metrics-snapshot.json), and [trend](rejected-trend.json) remain
byte-preserved. They measure 911,295 tokens and fail at **+20,310** versus the old
890,985-token reference. Concept radius and normalized debt pass. The unchanged
native implementation reproduces the rejected snapshot and failing trend **byte
for byte**. Changing only the replay reference to accepted main gives
[a passing comparison](replayed-trend.json): **+5,820** tokens, **+0** radius units,
and **-0.05** normalized debt units. The feature contribution is 12 production
files at 260 units, nine tests at 140, and eight documents at 180.

This is a retained-measurement replay, not a new extraction or complete PR
acceptance. The new README adds one document (an expected further 180 units).
The updated candidate still requires fresh extraction and complete integration
CI. No selector, threshold, exclusion, hotspot exception, or extractor behavior
is changed. The retained candidate artifact also passed the unchanged required
file, evidence, schema-v3, and semantic coverage verifier and its eight controls.

## Reproduction and provenance

The tag `v0.1.10` resolves through tag object
`ef0956a5743197a2aa62f425591282396ad46825` to extractor commit
`b409bed957c31d63ce7b6ef37205890f0f0ebd9a`. Four original source files were
verified against their Git blob IDs and compiled unchanged with Java 25.0.5,
targeting release 17. The existing
[ProjectionDriver](../2026-11-24-main-f183aedd/ProjectionDriver.java) reads full JSON
reports, derives thresholds from the retained native check, and delegates all
projection, trend, and serialization semantics to those released classes.

Use the [existing compile procedure](../2026-11-24-main-f183aedd/README.md#reproduce-the-native-projection)
with this directory's accepted reports and `previous-baseline.json`; compare the
outputs to `metrics-snapshot.json` and `accepted-trend-projected.json`. Repeat with
the rejected reports and previous baseline, comparing the original snapshot and
trend byte for byte. Finally repeat the rejected reports with the accepted
snapshot as reference, comparing `replayed-trend.json`. Jackson Databind/Core
2.23.0 and Annotations 2.23 only parse the retained JSON inputs.

[Provenance](provenance.json) binds the original reports, archive identities,
workflow, exact source files, unchanged policies, release sources, parser JARs,
and driver. [Projection verification](projection-verification.json) records the
three native decisions and the exact byte comparisons. The full original
archives remain with GitHub Actions; unrelated binaries are not copied here.
