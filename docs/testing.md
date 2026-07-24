# Testing

Regelsuche besitzt einen repositoryweiten, von GitHub unabhängigen
Verifikationsvertrag. GitHub Actions stellt lediglich Betriebssystem, JDK und
externe Werkzeuge bereit. Testauswahl, Fixtures, Assertions, Evidence-Prüfung
und die Entscheidung über Erfolg oder Fehlschlag liegen im Checkout.

## Testgraph

Der übliche Root-Aufruf startet alle als Gradle-`Test` modellierten Schichten:

```bash
./gradlew test
```

`./gradlew allTests` ist ein expliziter Alias für denselben vollständigen Lauf.

| Schicht | Enthaltener Task | Inhalt |
| --- | --- | --- |
| Modul- und Komponententests | alle Modul-`test`-Tasks | JUnit-5-Tests aller Java-Module |
| Browser-Integration | `:app:e2eTest` | Playwright gegen eine echte lokale `WebWorkbenchServer`-Instanz |
| Container-Integration | `:app:dockerE2eTest` | reale Anwendungs-, PostgreSQL- und Proof-Images über Testcontainers |
| Externer Solver | `:regelsuche-solver-portfolio:test` | echter Z3-Lauf, sofern Z3 erreichbar ist |

## Verifikations-Lifecycles

Der normale Vertragslauf ergänzt JUnit um checkout-lokale Evidence- und
Konsistenzprüfungen:

```bash
./gradlew check
```

`check` umfasst unter anderem:

- Dokumentations-Mathematik und deterministisch erzeugte Seiten;
- die kandidatenunabhängige Benchmark-Präregistrierung;
- Solver-IR-Schemas, Hashbindungen und kanonische Beispiele;
- Discovery-Domain-, Lifecycle-Handoff- und Release-Readiness-Evidence;
- Capability- und Claim-Matrix;
- Plugin-Index-, Plugin-Trust- und Trust-Store-Revision-Evidence;
- den Development-Pilot-Receipt aus den bereits erzeugten JUnit-Reports;
- die Begrenzung und semantische Schlankheit der GitHub-Workflows.

Die Python-Verifier laufen in der gepinnten Umgebung
`build/verification-venv`. Sie starten Gradle nicht rekursiv. Tests und
Evidence-Generatoren sind normale Abhängigkeiten desselben Gradle-Taskgraphen.

Der strikte Reproduktionslauf benötigt zusätzlich Docker und Z3 4.8.12:

```bash
./gradlew fullCheck
```

`fullCheck` ergänzt `check` um:

- den realen Solver-Portfolio-Pfad;
- den byteidentischen Vergleich zwischen Gradle- und Runtime-Image-Evidence;
- das Independent-Reproduction-Artefakt einschließlich Container-Test;
- JMH-Ausführung und Validierung;
- zwei lokale sowie eine gepinnte Container-Ausführung des vergleichenden
  Benchmarks mit byteidentischem Evidence-Vergleich.

## Exakter CI-Einstiegspunkt

Der verbindliche Einstiegspunkt für die zentrale CI ist:

```bash
./gradlew ciCheck
```

`ciCheck` führt `fullCheck` aus und erzeugt zusätzlich die lokal
veröffentlichbaren Benchmark-, Coverage-, Test- und Dokumentationsreports.
Der AI-Knowledge-Vertrag wird in denselben Taskgraphen aufgenommen, sobald er
explizit aktiviert ist:

```bash
GITHUB_ACTOR=<github-user> \
GITHUB_TOKEN=<package-read-token> \
AI_KNOWLEDGE_EXTRACTOR_ENABLED=true \
  ./gradlew --no-configuration-cache ciCheck
```

Für die Entwicklung des Extractors kann statt GitHub Packages ein benachbarter
Checkout verwendet werden:

```bash
AI_KNOWLEDGE_EXTRACTOR_ENABLED=true \
  ./gradlew --no-configuration-cache \
  -PuseLocalAiKnowledgeExtractor=true \
  -PaiKnowledgeExtractorCheckout=../ai-knowledge-extractor \
  ciCheck
```

Auf Linux kann Playwright einmalig zusätzliche Systembibliotheken benötigen:

```bash
./gradlew :app:installPlaywrightHostDependencies
```

Das ist Runner-Provisionierung, keine alternative Testlogik.

## Bedingte Infrastruktur über JUnit

Nicht überall verfügbare Infrastruktur wird innerhalb der Tests behandelt:

- Docker-basierte Klassen verwenden
  `@Testcontainers(disabledWithoutDocker = true)`.
- Der echte Z3-Test trägt `@Tag("external-prover")` und verwendet eine
  JUnit-Annahme.
- Playwright-Chromium wird durch den repositoryeigenen Task
  `installPlaywrightBrowsers` bereitgestellt.

`fullCheck` und `ciCheck` sind absichtlich strenger: Für ihre
Reproduktionsverträge müssen Docker und die dokumentierte Z3-Version vorhanden
sein.

## JUnit-Tags auswählen

Tags können repositoryweit gefiltert werden:

```bash
./gradlew test -PincludeTestTags=external-prover
./gradlew test -PexcludeTestTags=external-prover
./gradlew test -PexcludeTestTags=external-prover,slow
```

Für einzelne Klassen und Methoden bleibt der normale Gradle/JUnit-Filter
verfügbar:

```bash
./gradlew :app:dockerE2eTest \
  --tests de.regelsuche.dockere2e.ScientificDiscoveryPostgresE2ETest
./gradlew :regelsuche-solver-portfolio:test \
  --tests de.regelsuche.solver.portfolio.Z3SmtSolverBackendTest.systemZ3ReturnsRealProofObject
```

## Einzelne Schichten und Verträge

Für schnelle Entwicklungszyklen können alle Teilverträge separat ausgeführt
werden:

```bash
./gradlew :app:test
./gradlew :app:e2eTest
./gradlew :app:dockerE2eTest
./gradlew verifyPluginArtifactIndexEvidence
./gradlew verifyPluginArtifactTrustEvidence
./gradlew verifyPluginTrustStoreRevisionEvidence
./gradlew verifyDiscoveryChallengeDevelopmentPilots
./gradlew verifyJmhBenchmark
./gradlew verifyComparativeBenchmarks
```

Die Convenience-Skripte
`scripts/run-jmh-benchmark-verification.sh` und
`scripts/run-comparative-benchmarks-verification.sh` delegieren lediglich an
diese Gradle-Tasks.

## GitHub-Grenze

Unter `.github/workflows/` liegen nur noch:

- `gradle.yml`: Push-, Pull-Request- und manuelle CI; der einzige
  Verifikationsaufruf ist `ciCheck`. Danach werden Artefakte aufbewahrt und die
  bereits lokal erzeugte Site deployt.
- `release.yml`: GitHub-spezifische Tag-, Release- und Pull-Request-Operationen.

`verifyWorkflowSemantics` begrenzt die Zahl der Workflows auf zwei und weist
Inline-Interpreter, Workflow-Assertions, Docker-Lebenszyklen und direkte
Repository-Skriptaufrufe im Verifikationsworkflow zurück.

## Browser-, Docker- und Datenbankintegration

`e2eTest` startet die Web Workbench in-process auf einem zufälligen Port und
steuert Chromium headless. `dockerE2eTest` besitzt den vollständigen
Container-Lebenszyklus. Testcontainers baut die wirklichen Projekt-Dockerfiles,
vergibt zufällige Host-Ports, wartet auf Dienstbereitschaft, stellt Logs in den
JUnit-/Gradle-Reports bereit und räumt Container wieder auf.

Es sind keine fest verdrahteten GitHub-Service-Container, Host-Ports oder
Workflow-Credentials erforderlich. Diagnoseartefakte liegen unter den
jeweiligen `build/`-Verzeichnissen und können von jeder CI-Umgebung optional
veröffentlicht werden.

## Dokumentationsaufnahmen

Screenshots und Videos sind ein zusätzlicher Ausgabemodus derselben
Browser-Tests:

```bash
./gradlew :app:e2eTest -Pregelsuche.recordDocs=true
```

Das Ändern von Dokumentationsmedien ist bewusst kein automatischer Nebeneffekt
von `ciCheck`; ein normaler Verifikationslauf verändert den Checkout nicht durch
einen Bot-PR.
