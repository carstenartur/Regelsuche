# AI Knowledge evidence for accepted main f183aedd

The reference comes exclusively from accepted main
`f183aedd1ff2ab061ed903559b96a11d28974e7f`, tree
`c5764e9bdb462df4ac4d92a06532c169d96f68cb`.
[Original main CI 45290302102](https://github.com/carstenartur/Regelsuche/actions/runs/45290302102)
completed successfully. The thirteen source authorities required by that exact
workflow, its aggregate `Checkout-local ciCheck`, and report publication all
passed. The four optional manual JMH study jobs were skipped. The
[qualification record](ci-qualification.json) retains every mandatory job ID and
status and the workflow-owned definition of the required set.

## Original measurement and provenance

The downloaded `quality-acceptance-evidence` artifact `14878535049` has ZIP
SHA-256 `2aaf9601814adfb78f3ca8bd4968aa1a4429935f053961facd6fab48c15250d5`, matching
the independently read GitHub API metadata. Its index binds a clean `f183aedd`
checkout. All **293** indexed file lengths and hashes were verified; all **64**
tracked source entries additionally match the accepted commit bytes. The
[artifact verification](artifact-verification.json) distinguishes the remaining
229 generated reports from source bindings. Index completeness alone is not an
execution result; the original successful job graph supplies that authority.

The byte-preserved [check](accepted-check.json) and
[complexity report](accepted-complexity.json) record a passing native check and
trend, zero claim failures, **876,100** estimated context tokens, concept radius
**121**, and normalized context debt **13.52**. Against the
[previous reference](previous-baseline.json), accepted main was **+14,915** tokens
and **+2** radius units. The limits remain 15,000 tokens, three radius units and
ten normalized debt units. The absolute debt threshold remains 21.7; selectors,
coverage policy, method thresholds and hotspot exceptions are unchanged.

The artifact did not contain `metrics-snapshot.json`. As with the preceding
reference, the [snapshot](metrics-snapshot.json) is projected from the **complete
original accepted complexity report** by the unmodified released extractor
0.1.10 `TrendAnalyzer.snapshot` and `StableIo.writeJson`. Its SHA-256 is
`56cfa7cd7dc090533ba4de401bca9af95fea31086704865b6d988539a0a64c6b`.
The active `ai-knowledge/complexity-baseline.json` is a byte-for-byte copy.

The tag `v0.1.10` resolves through annotated tag object
`ef0956a5743197a2aa62f425591282396ad46825` to original extractor commit
`b409bed957c31d63ce7b6ef37205890f0f0ebd9a`. The four required source classes were
fetched at that commit, verified against their Git blob IDs, and compiled without
changes. The small [projection driver](ProjectionDriver.java) parses the full
JSON reports and delegates projection, trend decisions and serialization to those
classes. It obtains all trend thresholds from the retained native check; it does
not build a snapshot from a chosen subset of metrics.

[Supplemental provenance](supplemental-provenance.json) binds the source, original CI, artifact and native
report hashes, unchanged policy files, released extractor sources and driver.
The [projection verification](projection-verification.json) also records that the
same original implementation reproduces the preceding 9d1d4ac snapshot byte for
byte. The [accepted trend projection](accepted-trend-projected.json) is a local
replay of the retained accepted measurement, not a separately downloaded CI file.

## Preserved SDK rejection and bounded replay

The original [#1376 Gradle job](https://github.com/carstenartur/Regelsuche/actions/runs/45318827395/job/138192095093)
failed the trend at **876,785 − 861,185 = 15,600**, above the unchanged 15,000
allowance. Its downloaded `repository-verification` artifact `14885923074`
matches the API digest
`f95416f94f3102ea949e8a644769ee549fc1afcb6d5c902d001bd94a4d0d3622`.
The original [check](rejected-sdk-check.json),
[complexity](rejected-sdk-complexity.json),
[snapshot](rejected-sdk-metrics-snapshot.json) and
[trend](rejected-sdk-trend.json) remain byte-preserved diagnostic evidence.

The [checkout-log excerpt](rejected-sdk-checkout-log.txt) identifies GitHub's
actual test merge `9921b04b673e1520378efca9c343f866715f4023`, with parents main
`f183aedd` and PR head `31298a6a5aafb7325c30eaf63ca715e161b89c76`. The commit API
confirms tree `35236683dca6e4669bcf1ee314751d4546e0e48e`, identical to the PR head.
The provenance distinguishes that checkout identity from the run's head SHA.
Other source jobs were still running when the original workflow was inspected;
no overall failure or acceptance is inferred from the completed Gradle failure.

Replaying the original extractor on the retained SDK complexity reproduces its
original snapshot **and its original failing trend byte for byte**. Changing only
the replay's reference input to accepted main yields
[the new comparison](replayed-sdk-trend.json): **+685** tokens, **+0** radius units,
and **−0.01** normalized debt units, with identical thresholds. This replay is not
a fresh extraction or a complete qualification of the SDK branch. None of the
SDK candidate's values enters the active reference. The reference update and
later integrated candidates still require their own complete CI.

## Reproduce the native projection

Use the original extractor checkout at the commit above, a JDK supporting Java
17, and Jackson Databind/Core 2.23.0 plus Annotations 2.23 solely to parse the full
retained JSON input. Set `baseline_extractor` to that checkout and
`baseline_jackson_cp` to those three JARs separated by the platform classpath
separator. From the Regelsuche root, on a Unix shell:

```sh
baseline_history=ai-knowledge/baseline-history/2026-11-24-main-f183aedd
baseline_work=$(mktemp -d)
test "$(git -C "$baseline_extractor" rev-parse HEAD)" = b409bed957c31d63ce7b6ef37205890f0f0ebd9a
mkdir -p "$baseline_work/classes" "$baseline_work/ai-knowledge"
cp "$baseline_history/previous-baseline.json" "$baseline_work/ai-knowledge/complexity-baseline.json"
javac --release 17 -cp "$baseline_jackson_cp" -d "$baseline_work/classes" \
  "$baseline_extractor/core/src/main/java/org/aiknowledge/core/TrendAnalyzer.java" \
  "$baseline_extractor/core/src/main/java/org/aiknowledge/core/StableIo.java" \
  "$baseline_extractor/core/src/main/java/org/aiknowledge/core/JsonSupport.java" \
  "$baseline_extractor/core/src/main/java/org/aiknowledge/core/ExtractionOptions.java" \
  "$baseline_history/ProjectionDriver.java"
java -cp "$baseline_work/classes:$baseline_jackson_cp" org.aiknowledge.core.ProjectionDriver \
  "$baseline_history/accepted-complexity.json" "$baseline_history/accepted-check.json" \
  "$baseline_work/ai-knowledge" "$baseline_work/metrics-snapshot.json" "$baseline_work/trend.json"
cmp "$baseline_work/metrics-snapshot.json" "$baseline_history/metrics-snapshot.json"
cmp "$baseline_work/trend.json" "$baseline_history/accepted-trend-projected.json"
```

The driver performs no repository scan. It projects original reports using the
pinned native semantics. A successful replay therefore establishes only that
projection and comparison, while the accepted predecessor's qualification comes
from its independently checked complete original main CI.

## Concurrent integration

PR #1375 independently landed the same accepted-main snapshot before this
supplement was integrated. The active reference and its original
[provenance](provenance.json) remain byte-for-byte unchanged from main
`310bf41230ecfc51a2cd85bdc2b711a8da2de6fc`. This change adds retained native
reports, source verification and the SDK rejection/replay evidence; it does
not advance the reference a second time. The supplemental record preserves
the complete independent verification conducted for this work.
