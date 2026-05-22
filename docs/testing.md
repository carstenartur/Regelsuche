# Testing

Regelsuche fährt drei Test-Schichten, alle als Gradle-Tasks:

| Schicht | Gradle-Task | Was läuft? | Wo? |
| --- | --- | --- | --- |
| Unit & Integration | `./gradlew test` | JUnit-5-Tests aller Module (`app/src/test/java`) | im JVM, kein Browser |
| Browser-E2E | `./gradlew e2eTest` | Playwright steuert Chromium gegen die echte `WebWorkbenchServer`-Instanz | `app/src/e2eTest/java` |
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
Testcontainers, weil das den gleichen Production-Code-Pfad zehnmal schneller
prüft. `org.testcontainers:testcontainers` ist im
`e2eTestImplementation`-Classpath vorhanden, damit eine zukünftige
Docker-Image-Variante (z. B. Full-Mode mit Neo4j) mit minimaler Änderung
ergänzt werden kann.

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

`.github/workflows/ci-cd.yml` fährt vier voneinander unabhängige Jobs:

* `unit-test` — `./gradlew test`
* `browser-e2e` — installiert Chromium und ruft `./gradlew e2eTest` auf
  (deckt u. a. die landing-page-spezifischen Flows `landingPageShowsSimplePrimaryFlow`,
  `tabsHiddenBeforeFirstSearch`, `tabsVisibleAfterSearch`,
  `goalSelectionIsSubmittedWithSearch` aus
  [`LandingPageBrowserFlowTest`](../app/src/e2eTest/java/de/regelsuche/e2e/LandingPageBrowserFlowTest.java)
  und den Proof-Job-Flow `proofJobPanelBrowserFlow` aus
  [`ProofJobPanelBrowserFlowTest`](../app/src/e2eTest/java/de/regelsuche/e2e/ProofJobPanelBrowserFlowTest.java) ab)
* `docs-assets` — nur auf `main`: `./gradlew e2eTest -Pregelsuche.recordDocs=true`
  und lädt die frischen Screenshots/Videos als CI-Artifact hoch.
* `benchmark-report` — `./gradlew benchmarkReport` rendert die aktuelle
  Qualitäts-Übersicht und lädt `docs/benchmark-report.md` +
  `docs/assets/benchmark-summary.json` als Artefakte hoch.

Bei roten E2E-Tests lädt der Workflow zusätzlich die Playwright-Trace-Dateien
und den `e2eTest`-HTML-Report hoch, damit Fehler ohne lokalen Re-Run
diagnostizierbar sind.
