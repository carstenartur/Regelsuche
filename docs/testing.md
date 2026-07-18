# Testing

Regelsuche trennt Testimplementierung und CI-Orchestrierung. Die Tests gehören
zum Git-Checkout und werden über Gradle ausgeführt; GitHub Actions darf dieselben
Tasks aufrufen und Reports veröffentlichen, enthält aber keine exklusive
Integrationslogik.

## Lokale Einstiegspunkte

| Schicht | Gradle-Task | Inhalt | Voraussetzung |
| --- | --- | --- | --- |
| JVM-Tests | `./gradlew test` | JUnit-Tests aller Java-Module | JDK 21 |
| Infrastruktur-Integration | `./gradlew integrationTest` | PostgreSQL-, Standard-Image- und Proof-Image-Tests über Testcontainers | JDK 21, Docker |
| Browser-E2E | `./gradlew browserE2eTest` | Playwright gegen die echte `WebWorkbenchServer`-Instanz | JDK 21, Chromium |
| JVM + Infrastruktur | `./gradlew verificationTest` | alle JVM-Tests und die Testcontainers-Suite | JDK 21, Docker |
| Doku-Assets | `./gradlew test browserE2eTest -Pregelsuche.recordDocs=true` | Screenshots und Videos für die Demo-Gallery | JDK 21, Chromium |
| Benchmark-Report | `./gradlew benchmarkReport` | `docs/benchmark-report.md` und `docs/assets/benchmark-summary.json` | JDK 21 |

Die Root-Tasks sind stabile, CI-neutrale Einstiegspunkte. Die zugrunde liegenden
App-Tasks bleiben für gezielte Läufe verfügbar:

```bash
./gradlew :app:dockerE2eTest
./gradlew :app:e2eTest
```

Ein GitHub-Account, GitHub-spezifische Umgebungsvariablen und GitHub APIs sind
für keinen dieser Testläufe erforderlich.

## JVM-Tests

`./gradlew test` führt die JUnit-5-Suiten aller Module aus. Diese Tests benötigen
weder Browser noch Container. Datenbank- oder Image-Lebenszyklen gehören nicht
in diese Schicht.

## Infrastruktur-Integration mit Testcontainers

`./gradlew integrationTest` delegiert an `:app:dockerE2eTest`. Die Suite unter
`app/src/dockerE2eTest/java` besitzt den vollständigen Infrastruktur-Lebenszyklus:

- PostgreSQL wird als Testcontainer auf einem zufälligen Host-Port gestartet;
- das Standard-`Dockerfile` wird über `ImageFromDockerfile` gebaut und über HTTP
  sowie Playwright geprüft;
- `Dockerfile.proof` wird über Testcontainers gebaut;
- Z3 und cvc5 werden im laufenden Proof-Container ausgeführt;
- die Proof-Workbench wird über den zufällig gemappten Port angesprochen und ein
  realer Proof-Job wird eingereicht und wieder aufgelistet;
- Container, Netzwerke und temporäre Ressourcen werden von Testcontainers
  aufgeräumt.

Es gibt keine fest verdrahteten CI-Ports und keine von GitHub Actions verwalteten
Datenbank-Services. Einzelne Tests lassen sich direkt auswählen:

```bash
./gradlew :app:dockerE2eTest \
  --tests de.regelsuche.dockere2e.ProofDockerImageIntegrationTest

./gradlew :app:dockerE2eTest \
  --tests de.regelsuche.dockere2e.ScientificDiscoveryPostgresE2ETest
```

Ohne erreichbaren Docker-Daemon markiert die JUnit-Testcontainers-Integration
die Docker-Tests als übersprungen. CI-Umgebungen, die diese Suite als Gate
verwenden, müssen Docker bereitstellen.

## Browser-E2E

`./gradlew browserE2eTest` delegiert an `:app:e2eTest`. Playwright startet
Chromium und prüft reale Benutzerflüsse gegen eine In-Process-Instanz der
`WebWorkbenchServer`-Anwendung. Der In-Process-Start ist hier bewusst schneller
als ein Containerstart; die Image-Grenze wird separat durch `integrationTest`
geprüft.

Vor dem ersten Lauf werden Browser und gegebenenfalls Linux-Hostbibliotheken
installiert:

```bash
./gradlew :app:installPlaywrightBrowsers
# Auf Linux bei fehlenden Systembibliotheken zusätzlich:
./gradlew :app:installPlaywrightHostDependencies
```

Danach kann die Installation bei wiederholten Läufen übersprungen werden:

```bash
./gradlew browserE2eTest -Pregelsuche.skipPlaywrightInstall=true
```

## Wissenschaftliche Discovery mit PostgreSQL

`ScientificDiscoveryPostgresE2ETest` führt kuratierte Seeds über den produktiven
Workflow aus, persistiert Seeds, Search-Runs, Hypothesen, Gegenbeispiele,
Reports und Proof-Worker-Metadaten in einem PostgreSQL-Testcontainer und prüft
die deterministische Wiederholung.

Die erzeugten Dateien liegen lokal unter
`app/build/discovery-artifacts/scientific-postgres-e2e/`:

- `discovery-report.json`;
- `discovery-report.html`;
- `discovery-report.md`;
- `discovery-replay.json`;
- `discovery-summary.png`;
- `discovery-replay.gif`.

Für den schnellen, nicht-containerisierten Determinismus- und Budgetcheck:

```bash
./gradlew :app:test \
  --tests de.regelsuche.discovery.ScientificDiscoveryReproductionTest
```

## Doku-Assets

Der Record-Modus schreibt zusätzlich reproduzierbare Screenshots und Videos:

```bash
./gradlew test browserE2eTest \
  -Pregelsuche.recordDocs=true
```

Outputs:

- `docs/assets/screenshots/*.png`;
- `docs/assets/videos/*.webm`;
- optional `docs/assets/gifs/*.gif`, wenn `ffmpeg` verfügbar ist.

## CI-Grenze

GitHub Actions übernimmt ausschließlich:

1. Checkout und Toolchain-Setup;
2. Aufruf der oben dokumentierten Gradle-Tasks;
3. Cache- und Report-Upload;
4. Veröffentlichung, soweit Credentials erforderlich sind.

Testassertionen, Container-Lebenszyklen, Schema-/Hashregeln und negative Fälle
müssen dagegen in JUnit, Gradle oder eingecheckten Verifiern liegen. Ein neuer
Integrationsfall gilt erst dann als vollständig, wenn er aus einem normalen
Checkout ohne GitHub-spezifische Umgebung reproduzierbar ist.

Die Architekturentscheidung ist in
[`docs/adr/0002-local-verification-and-testcontainers.md`](adr/0002-local-verification-and-testcontainers.md)
festgehalten. Die schrittweise Bereinigung der bestehenden Workflows wird in
Issue #399 verfolgt.
