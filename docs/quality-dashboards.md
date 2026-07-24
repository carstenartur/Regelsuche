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

The performance page is intentionally a deterministic static report. Historical
trend storage and regression decisions are not delegated to a GitHub Action.
Such a policy can be added later as a checkout-owned comparison task with a
versioned baseline.

## Verification and publication boundary

`.github/workflows/gradle.yml` contains two jobs in one workflow run:

1. `verification` provisions Java and external tools, invokes only
   `./gradlew ciCheck`, and retains the resulting reports.
2. `publish-pages` downloads those outputs, combines `docs/` and `public/`, and
   deploys the static files through GitHub Pages.

The second job has no test selection, assertions or benchmark interpretation.
It cannot turn a failed verification into a successful deployment. There is no
additional benchmark workflow and no `gh-pages` branch write that creates a
second Pages build run.
