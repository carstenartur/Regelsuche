# Proof Workbench

Regelsuche ships a fully asynchronous proof pipeline behind the
`Proof-Jobs` tab. Lean and SMT (Z3 / cvc5) workers run jobs from a
persistent queue and write a structured artefact bundle per job.

![Proof-Job-Panel im Browser](assets/screenshots/proof-job-panel.png)

> Screenshot wird automatisch von `ProofJobPanelBrowserFlowTest` mit
> `./gradlew e2eTest -Pregelsuche.recordDocs=true` aktualisiert.

## End-to-end browser flow

`ProofJobPanelBrowserFlowTest#proofJobPanelBrowserFlow` startet die
Workbench in-process mit einem deterministischen
`StubAlwaysSucceedsWorker`, öffnet den `Proof-Jobs`-Tab und reicht genau den
retained Kandidaten der autonomen Produktionskampagne ein:

```text
(A + 2)*x + A*x → (2*A + 2)*x
```

Der Test wartet auf die Jobliste, ruft die Artefakt-Liste auf und erzeugt den
Dokumentations-Screenshot. Der Stub prüft dabei bewusst nur den kompletten
Browser-, Queue-, Scheduler- und Artefaktfluss; die mathematische
Solverausführung wird separat mit den realen SMT-Workern und dem Proof-Image
getestet. So bleibt der Browserflow auch auf CI-Runnern ohne lokal
installiertes Z3 oder Lean deterministisch.

## Architecture

```
ProofJob ──► JsonFileProofJobRepository (persistent queue)
              │
              ▼
ProofJobScheduler ──► CompositeProofWorker
                          ├── LeanProofWorker
                          └── SmtProofWorker
              │
              ▼                            ▼
JsonFileProofCache               JsonFileProofArtifactRepository
(result memoisation)             (proofs/<jobId>/{proof.lean|smt2,
                                  stdout.txt, stderr.txt,
                                  metadata.json})
```

All three persistence files are atomic temp-file writes and survive
restarts.

## REST API

| Verb + path | Purpose |
| --- | --- |
| `POST /api/proof/jobs` | Submit a new job. Body: `{ leftPattern, rightPattern, assumptions[], priority }`. Returns `201 { jobId, status, workerId }`. |
| `GET /api/proof/jobs` | List all jobs (most recent first in the UI). |
| `GET /api/proof/jobs/{id}` | Job detail incl. status, retries, error. |
| `POST /api/proof/jobs/{id}/cancel` | Cancel a queued or running job. |
| `GET /api/proof/jobs/{id}/artifacts` | List artefact filenames for the job's bundle. |
| `GET /api/proof/jobs/{id}/artifacts/{name}` | Stream a single artefact file. Path-traversal is rejected. |

The active worker or worker composition is configured by the application,
not selected per request. A non-empty `worker` field is rejected instead of
silently changing the proof boundary.

When `REGELSUCHE_PROOF_ENABLED=false`, the entire `/api/proof/jobs`
context responds with `503` so callers can gracefully degrade.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `REGELSUCHE_PROOF_ENABLED` | `true` | Enables the scheduler + REST endpoints. |
| `REGELSUCHE_PROOF_ARTIFACT_PATH` | `<persistencePath>/proofs` | Root directory of per-job bundles. |
| `REGELSUCHE_PROOF_JOB_STORE` | `<persistencePath>/proof-jobs.json` | Persistent job queue. |
| `REGELSUCHE_PROOF_CACHE` | `<persistencePath>/proof-cache.json` | Result cache keyed by `(leftPattern, rightPattern, workerId)`. |

JVM properties (`regelsuche.proof.enabled`, …) take precedence over the
environment for tests.

## Artefact bundle

Every terminal transition (success **and** failure) writes a uniform
bundle:

```
proofs/
└── <jobId>/
    ├── proof.lean      # or proof.smt2 / proof.txt depending on the worker
    ├── stdout.txt
    ├── stderr.txt      # populated with the failure reason on retry/fail
    └── metadata.json   # jobId, workerId, tool, status, durationMillis,
                        # createdAt, completedAt, priority, retries, error
```

Layout and traversal protection are pinned by
`JsonFileProofArtifactRepositoryBundleTest`.

## Docker image

Use `Dockerfile.proof` to get a runtime with Z3 + cvc5 preinstalled:

```bash
docker build -f Dockerfile.proof -t regelsuche-proof .
docker run --rm -p 8080:8080 regelsuche-proof
```

Lean 4 is heavy (~1 GB) and therefore opt-in:

```bash
docker build -f Dockerfile.proof --build-arg INSTALL_LEAN=true \
    -t regelsuche-proof-lean .
```

The CI job `proof-image` builds this image on every push, verifies that
`z3 --version` and `cvc5 --version` work inside the container and
smoke-tests `POST /api/proof/jobs` end-to-end.

## Tests

- `ProofJobsApiTest` — full submit → list → get → cancel → artefacts loop.
- `JsonFileProofArtifactRepositoryBundleTest` — bundle layout & traversal.
- `ProofConfigTest` — env-var precedence and boolean aliases.
- `ProofJobPanelBrowserFlowTest` — retained production candidate through UI,
  queue, scheduler and artefact listing.

See [`docs/proof-bridge.md`](proof-bridge.md) for the synchronous
`/api/proof-bridge` endpoint (the pre-existing one-shot proof helper).
