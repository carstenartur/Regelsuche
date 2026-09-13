# Performance & Coverage Dashboards

Regelsuche renders all quality pages from checkout-local Gradle outputs and
deploys them only after a successful `ciCheck` run on `main`.

| Dashboard | URL | Checkout source |
| --- | --- | --- |
| Coverage report | <https://carstenartur.github.io/Regelsuche/coverage/> | JaCoCo XML/HTML produced by the repository test graph |
| Test report | <https://carstenartur.github.io/Regelsuche/tests/> | Gradle JUnit XML/HTML produced by the repository test graph |
| Performance report | <https://carstenartur.github.io/Regelsuche/dev/bench/> | validated `app/build/reports/jmh/result.json` |

The shields.io endpoint badges consume the generated JSON files under
`public/`. They therefore reflect the last successfully deployed `main` run.

## Local commands

```bash
# Coverage and test pages
./gradlew test jacocoTestReport generateCiPages

# JMH result validation, badge and static report
./gradlew verifyJmhBenchmark

# Complete CI report set
./gradlew ciCheck
```

`verifyJmhBenchmark` runs the JMH suite, validates every
benchmark/parameter identity and finite primary metric, and writes:

```text
app/build/reports/jmh/result.json
public/dev/bench/badge.json
public/dev/bench/index.html
```

The performance page is a deterministic static report. The checkout also owns
the content-bound v3 regression decision and the Java renderer for two retained
JMH history snapshots and 29 SVG charts; see
[performance history](performance-history.md). Neither history nor regression
interpretation is delegated to a GitHub Action.

## Verification and publication boundary

`.github/workflows/gradle.yml` separates expensive authorities within one workflow:

1. Gradle, isolated JMH, isolated SymPy runtime, complete Maven/Docker and the
   external polynomial comparison execute their checkout-owned commands.
2. Required `verification` rejects failed authorities, joins the coverage
   inputs, reruns the unchanged coverage verifier, and collects the complete
   [quality acceptance evidence](quality-acceptance-evidence.md).
3. `publish-pages` downloads the retained outputs, combines `docs/` and `public/`, and
   deploys the static files through GitHub Pages.

The publication job has no test selection, assertions or benchmark interpretation.
It cannot turn a failed verification into a successful deployment. There is no
additional benchmark workflow and no `gh-pages` branch write that creates a
second Pages build run.
