# v2 supplemental execution, 2026-11-10

The default prospective protocol (16 blocks, 3 repetitions, 2 arms) executed with
Java 25.0.5. **No learning improvement or speedup is established by this run.**
Both seed batches were rejected as `NO_VALIDATED_BENEFIT` in every arm. Each
improvement arm paid two further attempts, reported as `NO_CANDIDATE` with learner
reasons `NO_VALIDATED_BENEFIT` and `NO_NEW_SCHEMA`. No candidate reached paired
promotion measurements and no generation was adopted by the time-based gate.
This outcome is retained rather than tuning acquisition to produce acceptance.

All 768 evaluation outputs passed source/witness binding, exact polynomial and
node-score audits. Each arm solved 112 of 128 tasks; the 16 negative controls in
each arm remained unsolved as intended. Quality was identical in all pairs.
Mechanical work, including acquisition, snapshot preparation/lookups, learning
attempts and output audits, was 155,064 units for each baseline arm and 181,715 for
each improvement arm: **26,651 extra paid units**, with no changed query policy.
Common exact split validation consumed another 12,084 units at study level.

| Repetition | Baseline wall s | Improvement wall s | Baseline CPU s | Improvement CPU s | Both observed totals lower? |
| ---: | ---: | ---: | ---: | ---: | --- |
| 0 | 2.159594039 | 1.090959999 | 5.66 | 3.72 | yes |
| 1 | 0.462676237 | 0.682787747 | 1.88 | 2.70 | no |
| 2 | 0.399957203 | 0.422113612 | 1.54 | 1.23 | no |

These wall/process-CPU numbers include all arm acquisition and audit work. They
are observations from sequential arms sharing a JVM, with order alternating by
repetition. Their large warmup/order variation and absence of any adopted model
mean the first pair's lower totals cannot be attributed to learning. The JSON
field `observedBothResourcePayback` is only the arithmetic comparison of observed
whole-arm totals; it does not establish causation, confidence or transferable
speedup. CPU can exceed elapsed wall time because process CPU sums JVM threads.

The enclosing Bash `time -p` receipt reports 6.02 seconds real, 18.21 seconds user
and 0.43 seconds system CPU (18.64 CPU seconds in total). Unlike internal timing,
this includes process startup/shutdown and final report serialization. No RSS
measurement is claimed. `/usr/bin/time` was unavailable, so the retained process
receipt uses Bash's built-in `time`.

## Qualification and reproducibility limits

Official Maven tests were attempted, but Maven Central DNS resolution failed for
`junit-bom:6.1.3`, `testcontainers-bom:2.0.5` and `netty-bom:4.2.18.Final` before
compilation. The retained `maven-attempt.txt` records that failure. No network
restriction was bypassed, dependency version changed or dependency stub used.

Supplemental compilation used the changed Java sources and current referenced
source roots with `javac -Xprefer:source`; the locally available optimization SDK
fat jar supplied external dependencies and any uncompiled classes. Exact jar,
source and compiled-class-manifest hashes are in `supplemental-provenance.json`.
This is **not a canonical Maven dependency closure**, so the run is a supplemental
engineering observation and requires official CI qualification. In particular,
the legacy `useful(1)`-style adoption fixture also produced
`NO_VALIDATED_BENEFIT` in this supplemental environment; no claim is made that the
existing Maven adoption tests passed. Resolving that fixture under official
dependencies is a remaining gate.

The dependency-light Java contracts passed for all eight roles, matching weights,
all three exact split intersections, alpha-renaming leak rejection, worker named
workload selection, adversarial single-specialist versus mixed costs and per-case
quality veto. The lifecycle cost contract verifies that a gain repaying 200 units
of current attempt cost but not 1,000,200 cumulative units is rejected, while
explicit legacy scope adopts and a sufficiently large horizon repays cumulative
cost. A mutation that restored current-attempt-only charging failed that exact
assertion. Those clock values are synthetic test data, not timing evidence.

The raw process stdout contained a JVM perfdata-file-lock warning followed by one
JSON object. `supplemental-stdout.txt` preserves it verbatim. Only that complete
JSON line was extracted, unchanged, into `supplemental-report.json` for machine
reading. All raw decisions, rejected attempts and costs remain present.

The representation audit remains a full alternate exact solve; no residual-only
replacement was introduced. Its completeness/rank proof obligations are recorded
in `protocol.md`.
