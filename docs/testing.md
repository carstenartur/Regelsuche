# Testing

Regelsuche fährt vier Test-Schichten, alle als Gradle-Tasks:

| Schicht | Gradle-Task | Was läuft? | Wo? |
| --- | --- | --- | --- |
| Unit & Integration | `./gradlew test` | JUnit-5-Tests aller Module (`app/src/test/java`) | im JVM, kein Browser |
| Browser-E2E | `./gradlew e2eTest` | Playwright steuert Chromium gegen die echte `WebWorkbenchServer`-Instanz | `app/src/e2eTest/java` |
| Docker-Image-E2E | `./gradlew dockerE2eTest` | Testcontainers baut das Dockerfile, fährt den Container hoch und prüft Asset-Serving und KaTeX-Rendering | `app/src/dockerE2eTest/java` |
| Doku-Assets | `./gradlew e2eTest -Pregelsuche.recordDocs=true` | gleiche Tests + Screenshots/Videos für die [Demo-Gallery](demo-gallery.md) | Output unter `docs/assets/` |
| Benchmark-Report | `./gradlew benchmarkReport` | rendert `docs/benchmark-report.md` + `docs/assets/benchmark-summary.json` aus der `BenchmarkSuite` | JVM, kein Browser |

## `./gradlew test`

Standard-Unit- und Integrationstests. Läuft als Pflicht-CI-Schritt
(`unit-test`-Job in `.github/workflows/ci-cd.yml`).

## `./gradlew e2eTest`

Treibt den Web-Workbench mit echten Browser-Flows an. Jeder Test:

1. startet `WebWorkbenchServer` in-process auf einem zufälligen Port
   (siehe [`RegelsucheAppEnvironment`](../app/src/e2eTest/java/de/regelsuche/e2e/RegelsucheAppEnvironment.java)),
2. öffnet die Landing-Page mit Chromium (headless),
3. klickt den Demo-Button,
4. prüft Graph-, Replay- und Summary-Panels sowie Proof-Status,
5. lädt das Export-Bundle herunter und prüft die Größe.

Vor dem ersten Lauf zieht der Sub-Task `installPlaywrightBrowsers`
Chromium ins lokale `~/.cache/ms-playwright/`. Dieser Schritt ist
idempotent.

Die Browser-Tests verwenden bewusst eine in-Process-Server-Variante statt
Testcontainers für schnelle Feedback-Loops — ein In-Process-Start ist eine
Größenordnung schneller als ein Docker-Container-Start.

Für Asset-Serving-Regressions (z. B. den `/vendor/`-Static-Path-Bug, der im
Mai 2026 die Mathe-Darstellung über mehrere PRs hinweg gebrochen hat) gibt es
den ergänzenden `dockerE2eTest`-Layer (siehe unten).

## `./gradlew dockerE2eTest`

Baut das Standard-`Dockerfile` via Testcontainers' `ImageFromDockerfile`,
fährt den Container auf einem zufälligen Port hoch und prüft via HTTP-Client
und Playwright, dass:

- `/`, `/app.js`, `/style.css` mit korrekten MIME-Typen geliefert werden,
- `/vendor/katex/katex.min.css`, `/vendor/katex/katex.min.js`,
  `/vendor/katex/contrib/auto-render.min.js` mit Status 200 und korrekten
  Content-Types ausgeliefert werden,
- KaTeX-Fonts (`*.woff2`) mit `font/woff2` ausgeliefert werden,
- `/vendor/cytoscape/cytoscape.min.js` mit Status 200 ausgeliefert wird,
- Path-Traversal-Versuche (`/vendor/../../../../etc/passwd`) mit 4xx
  abgelehnt werden,
- nach Klick auf den Demo-Button „Binomische Formel" mindestens ein
  `.katex`-Element im DOM vorhanden ist (KaTeX hat wirklich gerendert).

**Wann laufen diese Tests?** Der Task ist nicht in `check` eingehängt; er
läuft explizit im CI-Job `docker-image-e2e` oder lokal mit
`./gradlew dockerE2eTest` (Docker muss verfügbar sein). Ohne erreichbaren
Docker-Daemon skippen sich die Tests automatisch (via
`DockerClientFactory.instance().isDockerAvailable()`).

## `./gradlew e2eTest -Pregelsuche.recordDocs=true`

Identisch zum `e2eTest`-Lauf, schreibt aber zusätzlich:

* `docs/assets/screenshots/*.png` — pro Demo ein Full-Page-Screenshot,
* `docs/assets/videos/*.webm` — pro Test eine WebM-Aufnahme
  (gitignoriert; in CI als Artifact hochgeladen),
* optional `docs/assets/gifs/*.gif`, falls `ffmpeg` im `PATH` liegt
  (sonst werden nur Videos behalten).

Die Screenshots werden danach von [`docs/demo-gallery.md`](demo-gallery.md)
und vom Projekt-`README.md` referenziert. Da sie aus genau den Tests stammen,
die auch die Funktion absichern, ist die Doku per Konstruktion aktuell.

## CI-Integration

`.github/workflows/ci-cd.yml` fährt sechs voneinander unabhängige Jobs:

* `unit-test` — `./gradlew test`
* `browser-e2e` — installiert Chromium und ruft `./gradlew e2eTest` auf
  (deckt u. a. die landing-page-spezifischen Flows `landingPageShowsSimplePrimaryFlow`,
  `tabsHiddenBeforeFirstSearch`, `tabsVisibleAfterSearch`,
  `goalSelectionIsSubmittedWithSearch` aus
  [`LandingPageBrowserFlowTest`](../app/src/e2eTest/java/de/regelsuche/e2e/LandingPageBrowserFlowTest.java)
  und den Proof-Job-Flow `proofJobPanelBrowserFlow` aus
  [`ProofJobPanelBrowserFlowTest`](../app/src/e2eTest/java/de/regelsuche/e2e/ProofJobPanelBrowserFlowTest.java) ab)
* `docker-image-e2e` — baut das Standard-Dockerfile, fährt den Container via
  Testcontainers hoch und verifiziert via HTTP-Client + Playwright das
  Asset-Serving und KaTeX-Rendering (schützt vor dem `/vendor/`-Bug und
  ähnlichen Regressions). Zusätzlich laufen PostgreSQL-basierte
  Discovery-Full-Mode-Tests wie `ScientificDiscoveryPostgresE2ETest`, die
  wissenschaftliche Seeds reproduzierbar ausführen, Replay-Artefakte erzeugen
  und Seeds/Search-Runs/Hypothesen/Gegenbeispiele/Reports/Proof-Worker-
  Metadaten persistieren. Die erzeugten Dateien liegen lokal unter
  `app/build/discovery-artifacts/scientific-postgres-e2e/` und werden im CI als
  Artifact `scientific-discovery-artifacts` hochgeladen:

  ```bash
  ./gradlew :app:dockerE2eTest --tests de.regelsuche.dockere2e.ScientificDiscoveryPostgresE2ETest
  ```

  Für den schnellen nicht-containerisierten Determinismus-/Budget-Check:

  ```bash
  ./gradlew :app:test --tests de.regelsuche.discovery.ScientificDiscoveryReproductionTest
  ```

  Artefakte: `discovery-report.json` (byte-stabil; volatile Laufzeitfelder sind
  markiert und stabilisiert), `discovery-report.html`,
  `discovery-report.md`, `discovery-replay.json`,
  `discovery-summary.png` (synthetischer Report-Screenshot) und
  `discovery-replay.gif` (mehrere Replay-Frames). Echte UI-Screenshots bleiben
  Aufgabe der Playwright-Flows.
* `docs-assets` — nur auf `main`: `./gradlew e2eTest -Pregelsuche.recordDocs=true`
  und lädt die frischen Screenshots/Videos als CI-Artifact hoch.
* `benchmark-report` — `./gradlew benchmarkReport` rendert die aktuelle
  Qualitäts-Übersicht und lädt `docs/benchmark-report.md` +
  `docs/assets/benchmark-summary.json` als Artefakte hoch.
* `proof-image` — baut `Dockerfile.proof`, prüft die enthaltenen Prover
  (`z3`, `cvc5`) und macht einen REST-Smoketest gegen `/api/proof/jobs`.

Bei roten E2E-Tests lädt der Workflow zusätzlich die Playwright-Trace-Dateien
und den `e2eTest`-HTML-Report hoch, damit Fehler ohne lokalen Re-Run
diagnostizierbar sind.
