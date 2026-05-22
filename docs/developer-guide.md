# Developer Guide

Pointers for working on the Regelsuche codebase locally.

## Repository layout

```
app/                         single Gradle module
  src/main/java              production code (de.regelsuche.*)
  src/main/resources/web     workbench UI (index.html, app.js, …)
  src/test/java              JUnit 5 tests
  src/e2eTest/java           Playwright/Testcontainers browser tests
docs/                        end-user documentation
Dockerfile, Dockerfile.proof Docker entry points
.github/workflows/           CI (build, e2e, proof image)
```

The build is a Gradle multi-project setup with a single `app` module
(see `settings.gradle`).

## Common commands

| Command | What it does |
| --- | --- |
| `./gradlew :app:compileJava` | Compile production sources only. |
| `./gradlew :app:test` | Unit + integration tests. |
| `./gradlew e2eTest` | Browser flows (`BrowserDemoFlowTest`). |
| `./gradlew e2eTest -Pregelsuche.recordDocs=true` | Re-record screenshots into `docs/assets/screenshots/`. |
| `./gradlew :app:installDist` | Produce `app/build/install/app/bin/app`. |
| `docker build -t regelsuche .` | Build the standard Docker image. |
| `docker build -f Dockerfile.proof -t regelsuche-proof .` | Build the proof-enabled image (Z3 + cvc5). |

## Coding conventions

- **Atomic rewrite rules only.** Multi-step textbook formulas must
  emerge as paths, not as a single hard-coded `RewriteRule`. The
  fallback engine is `AstRewriteTransformationEngine`.
- **Repositories follow the existing pattern.**
  `Neo4jExpressionGraphStore` and `Neo4jRuleInventoryRepository` show
  the established split between in-memory, JSON-file and Neo4j
  backends. New persistence layers should provide all three (or
  document why not).
- **Proof state lives in `de.regelsuche.proof`.** All three persistent
  stores (`JsonFileProofJobRepository`, `JsonFileProofCache`,
  `JsonFileProofArtifactRepository`) write atomically via temp files.
- **Web endpoints route through `WebWorkbenchServer.handle*`.** Add a
  context in `start()` and dispatch on the URL suffix — see
  `handleProofJobs` and `handleMemory` for the dispatch pattern.

## Adding a new REST endpoint

1. Add a `handle*` method on `WebWorkbenchServer`.
2. Register it with `secure(server.createContext(...))` in `start()`.
3. Use `JsonWriter` for the response body (it handles escaping).
4. Add a focused integration test in `app/src/test/java/de/regelsuche/web/`
   following `MemoryUniversalApiTest` as a template.

## Adding a new search strategy

1. Implement `de.regelsuche.search.strategy.SearchStrategy`.
2. Wire a `SearchProfile` value that returns it from `newStrategy()`.
3. Decide the default `TransformationGoal` for the profile (see
   [`search-intelligence.md`](search-intelligence.md)).
4. Add a benchmark scenario in `de.regelsuche.benchmark` so the
   strategy shows up in the `/api/benchmark` dashboard.

## Configuration knobs

| Env var | Read by |
| --- | --- |
| `REGELSUCHE_PERSISTENCE_MODE`, `_PATH`, `_NEO4J_*` | `PersistenceConfig.fromEnvironment()` |
| `REGELSUCHE_PROOF_ENABLED`, `_ARTIFACT_PATH`, `_JOB_STORE`, `_CACHE` | `ProofConfig.fromEnvironment()` |
| `REGELSUCHE_WEB_*` (TLS, basic auth) | `WebSecurityConfig` |

JVM `-D` properties of the same name (lowercased, dotted) win over the
environment, which is the pattern used by every config record in
this repo. Tests rely on this to override defaults without polluting
the real environment.

## CI

The default workflow runs unit tests, the browser E2E suite, and the
proof-image smoke test (`Dockerfile.proof` + Z3/cvc5 binary check +
`POST /api/proof/jobs`). All three must stay green for a merge.
