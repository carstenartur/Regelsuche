# Developer Guide

Pointers for working on the Regelsuche codebase locally.

## Repository layout

```text
regelsuche-*                 focused Gradle library and application modules
app/                         web workbench, CLI wiring and browser/container tests
  src/main/java              production code (de.regelsuche.*)
  src/main/resources/web     workbench UI (index.html, app.js, …)
  src/test/java              JUnit 5 tests and retained evidence generators
  src/e2eTest/java           Playwright browser tests
  src/dockerE2eTest/java     Testcontainers image tests
gradle/                      checkout-owned verification task definitions
scripts/                     checkout-owned validators and report renderers
docs/                        end-user and architecture documentation
.github/workflows/           thin CI and GitHub release adapters only
```

The build is a Gradle multi-project setup. `settings.gradle` is the canonical
module inventory.

## Common commands

| Command | What it does |
| --- | --- |
| `./gradlew :app:compileJava` | Compile the web application sources. |
| `./gradlew test` | Run every Gradle `Test` task in the repository. |
| `./gradlew check` | Run tests plus checkout-local contract and evidence verification. |
| `./gradlew fullCheck` | Add strict Docker, solver and benchmark reproduction. |
| `./gradlew ciCheck` | Run the exact verification and report lifecycle used by CI. |
| `./gradlew :app:e2eTest -Pregelsuche.recordDocs=true` | Re-record documentation media deliberately. |
| `./gradlew :app:installDist` | Produce `app/build/install/app/bin/app`. |
| `docker build -t regelsuche .` | Build the standard Docker image. |
| `docker build -f Dockerfile.proof -t regelsuche-proof .` | Build the proof-enabled image. |

See [`testing.md`](testing.md) for prerequisites and focused verification tasks.

## Coding conventions

- **Atomic rewrite rules only.** Multi-step textbook formulas must emerge as
  paths, not as a single hard-coded `RewriteRule`. The fallback engine is
  `AstRewriteTransformationEngine`.
- **Repositories follow the existing pattern.**
  `Neo4jExpressionGraphStore` and `Neo4jRuleInventoryRepository` show the
  established split between in-memory, JSON-file and Neo4j backends. New
  persistence layers should provide all three or document why not.
- **Proof state lives in `de.regelsuche.proof`.** The persistent stores write
  atomically via temporary files.
- **Web endpoints route through `WebWorkbenchServer.handle*`.** Add a context
  in `start()` and dispatch on the URL suffix.
- **Verification semantics belong in Gradle, JUnit or `scripts/`.** A workflow
  may provision a runner and publish outputs, but it may not define fixtures,
  assertions, Docker reproductions or alternative test selection.

## Adding a new REST endpoint

1. Add a `handle*` method on `WebWorkbenchServer`.
2. Register it with `secure(server.createContext(...))` in `start()`.
3. Use `JsonWriter` for the response body.
4. Add a focused integration test in `app/src/test/java/de/regelsuche/web/`.

## Adding a new search strategy

1. Implement `de.regelsuche.search.strategy.SearchStrategy`.
2. Wire a `SearchProfile` value that returns it from `newStrategy()`.
3. Decide the default `TransformationGoal` for the profile.
4. Add a benchmark scenario so the strategy appears in the benchmark report.

## Configuration knobs

| Env var | Read by |
| --- | --- |
| `REGELSUCHE_PERSISTENCE_MODE`, `_PATH`, `_NEO4J_*` | `PersistenceConfig.fromEnvironment()` |
| `REGELSUCHE_PROOF_ENABLED`, `_ARTIFACT_PATH`, `_JOB_STORE`, `_CACHE` | `ProofConfig.fromEnvironment()` |
| `REGELSUCHE_WEB_*` | `WebSecurityConfig` |

JVM `-D` properties of the same name, lowercased and dotted, win over the
environment. Tests use this to override defaults without polluting the real
environment.

## CI

The only push/pull-request verification workflow is
`.github/workflows/gradle.yml`. After runner provisioning it invokes:

```bash
./gradlew --no-configuration-cache ciCheck
```

A developer reproduces a red verification job with the same task from the same
commit. GitHub-only publication is a later job in that workflow and cannot
change the verification result. `release.yml` remains separate because tags,
GitHub Releases and follow-up pull requests are platform operations rather than
checkout verification.
