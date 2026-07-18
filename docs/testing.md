# Testing

Regelsuche besitzt einen repositoryweiten, von GitHub unabhängigen Testvertrag. Aus einem normalen Checkout startet der übliche Gradle-Testaufruf sämtliche als `Test` modellierten Testschichten:

```bash
./gradlew test
```

Der Root-Task aggregiert die `Test`-Tasks aller Module. `./gradlew allTests` bleibt als expliziter Alias für denselben vollständigen Lauf verfügbar.

| Schicht | Enthaltener Task | Inhalt |
| --- | --- | --- |
| Modul- und Komponententests | alle Modul-`test`-Tasks | JUnit-5-Tests aller Java-Module |
| Browser-Integration | `:app:e2eTest` | Playwright gegen eine echte lokale `WebWorkbenchServer`-Instanz |
| Container-Integration | `:app:dockerE2eTest` | reale Anwendungs-, PostgreSQL- und Proof-Images über Testcontainers |
| Externer Solver | `:regelsuche-solver-portfolio:test` | echter Z3-Lauf, sofern Z3 auf dem Rechner verfügbar ist |

Die GitHub-Workflows sind nicht Bestandteil der Testimplementierung. Sie dürfen denselben Gradle-Aufruf ausführen und Reports veröffentlichen, definieren aber weder Fixtures noch Assertions noch die Entscheidung über Erfolg und Fehlschlag.

## Bedingte Infrastruktur über JUnit

Nicht überall verfügbare Infrastruktur wird innerhalb der Tests behandelt:

- Docker-basierte Klassen verwenden `@Testcontainers(disabledWithoutDocker = true)`. Ohne erreichbaren Docker-Daemon markiert JUnit sie als übersprungen.
- Der echte Z3-Test trägt `@Tag("external-prover")` und verwendet eine JUnit-Annahme. Fehlt Z3, wird nur dieser Integrationstest übersprungen; die Solver-Unit-Tests laufen weiter.
- Playwright-Chromium wird vor den Browser- und Docker-Browser-Tests durch den repositoryeigenen Gradle-Task `installPlaywrightBrowsers` bereitgestellt.

Ein fehlendes optionales Werkzeug wird damit nicht über eine GitHub-Variable simuliert und führt auch nicht dazu, dass eine komplette Testklasse aus einem Workflow entfernt werden muss.

## JUnit-Tags auswählen

Tags können repositoryweit für jeden Gradle-`Test`-Task gefiltert werden:

```bash
# Nur Tests mit dem angegebenen JUnit-Tag
./gradlew test -PincludeTestTags=external-prover

# Alles außer bestimmten JUnit-Tags
./gradlew test -PexcludeTestTags=external-prover

# Mehrere Tags, kommasepariert
./gradlew test -PexcludeTestTags=external-prover,slow
```

Für einzelne Klassen und Methoden bleibt der normale Gradle/JUnit-Filter verfügbar:

```bash
./gradlew :app:dockerE2eTest \
  --tests de.regelsuche.dockere2e.ScientificDiscoveryPostgresE2ETest

./gradlew :regelsuche-solver-portfolio:test \
  --tests de.regelsuche.solver.portfolio.Z3SmtSolverBackendTest.systemZ3ReturnsRealProofObject
```

## Einzelne Testschichten

Für schnelle Entwicklungszyklen können die Schichten weiterhin separat ausgeführt werden:

```bash
./gradlew :app:test
./gradlew :app:e2eTest
./gradlew :app:dockerE2eTest
./gradlew :regelsuche-solver-portfolio:test
```

Der unqualifizierte Root-Aufruf `./gradlew test` ist dagegen der vollständige verbindliche Testlauf. Er umfasst ausdrücklich auch die zusätzlich benannten Browser- und Container-`Test`-Tasks.

## Browser-Integration

`e2eTest` startet die Web Workbench in-process auf einem zufälligen Port und steuert Chromium headless. Die Tests prüfen unter anderem Navigation, Such- und Demo-Flows, Graph- und Replay-Panels, Regelautorisierung, Proof-Jobs sowie mathematische Darstellung.

Der Browserdownload ist idempotent. Auf Linux-Systemen, denen native Chromium-Bibliotheken fehlen, können diese einmalig über Playwright installiert werden:

```bash
./gradlew :app:installPlaywrightHostDependencies
```

Dieser Betriebssystem-Schritt benötigt je nach Distribution erhöhte Rechte; er ist keine GitHub-Abhängigkeit.

## Docker- und Datenbankintegration

`dockerE2eTest` besitzt den vollständigen Container-Lebenszyklus. Testcontainers:

- baut die wirklichen Projekt-Dockerfiles;
- vergibt zufällige Host-Ports;
- startet PostgreSQL und weitere benötigte Dienste;
- wartet auf die tatsächliche Dienstbereitschaft;
- stellt Logs in den JUnit-/Gradle-Reports bereit;
- entfernt Container nach dem Lauf.

Das normale `Dockerfile` erzeugt bewusst das schlanke Web-Workbench-Runtime-Image und installiert keine externen Beweiser. Der Proof-Integrationstest baut separat `Dockerfile.proof`; dieses Image installiert Z3 und cvc5. Der JUnit-Test führt im gestarteten Container sowohl `z3 --version` als auch `cvc5 --version` aus und prüft zusätzlich die Proof-Job-REST-Schnittstelle. Testcontainers stellt die Solver also nicht selbst bereit, sondern startet das versionierte Projektimage, in dem sie installiert sind.

Es sind keine fest verdrahteten GitHub-Service-Container, Host-Ports oder Workflow-Credentials erforderlich. PostgreSQL-Discovery-Tests erzeugen ihre wissenschaftlichen Diagnoseartefakte unter `app/build/discovery-artifacts/`; diese Dateien können von jeder CI-Umgebung optional veröffentlicht werden.

## Dokumentationsaufnahmen

Screenshots und Videos sind ein zusätzlicher Ausgabemodus derselben Browser-Tests:

```bash
./gradlew :app:e2eTest -Pregelsuche.recordDocs=true
```

Das Ändern von Dokumentationsartefakten ist bewusst nicht Teil von `test`, damit ein normaler Verifikationslauf den Checkout nicht verändert.
