# Testing und Verifikation

Regelsuche besitzt einen repositoryweiten Verifikationsvertrag, der unabhängig
von GitHub Actions aus einem normalen Checkout ausführbar ist. Testauswahl,
Fixtures, Assertions, Evidence-Prüfung und Erfolgsentscheidung liegen in
Gradle, JUnit und den versionierten Skripten des Repositorys.

## Welcher Task ist der richtige?

| Ziel | Befehl | Typische Dauer und Voraussetzungen |
| --- | --- | --- |
| schneller Modultest | `./gradlew :<modul>:test` | JDK 21 |
| Anwendungstests | `./gradlew :app:test` | JDK 21 |
| Browserfluss | `./gradlew :app:e2eTest` | JDK 21, Chromium/Playwright |
| Containerintegration | `./gradlew :app:dockerE2eTest` | JDK 21, Docker |
| alle Gradle-Testschichten | `./gradlew test` | JDK 21, je nach Tests Docker/Tools |
| Tests plus Vertragsprüfung | `./gradlew check` | JDK 21, Python-`venv` |
| strikte Reproduktion | `./gradlew fullCheck` | zusätzlich Docker und externe Solver |
| autoritativer CI-Lebenszyklus | `./gradlew --no-configuration-cache ciCheck` | vollständige CI-Toolchain |

`allTests` ist ein expliziter Alias für `test`.

## Testschichten

### Modul- und Komponententests

Jedes Java-Modul besitzt JUnit-5-Tests für seine fachliche Verantwortung. Diese
Tests sollen schnell, deterministisch und frei von nicht benötigter
Infrastruktur sein.

### Browser-E2E

`:app:e2eTest` startet eine reale lokale Workbench auf einem zufälligen Port und
steuert Chromium mit Playwright. Die Tests prüfen sichtbare Nutzerflüsse,
Zustände, Downloads und semantische Darstellung.

### Container-E2E

`:app:dockerE2eTest` baut und startet die tatsächlichen Projektimages über
Testcontainers. PostgreSQL-, Proof- und Runtime-Integration werden mit
zufälligen Host-Ports und ohne GitHub-Service-Container geprüft.

### Externe Solver

Solver-Tests verwenden echte ausführbare Backends, wenn der jeweilige Vertrag
dies verlangt. Technische Nichtverfügbarkeit bleibt von mathematischer
Refutation oder Bestätigung getrennt.

### Evidence- und Vertragsverifier

Gradle-Tasks und Skripte prüfen unter anderem:

- JSON-Schemas, strikte Codecs und kanonische Serialisierung;
- Hashbindungen, Lineage und Manifestvollständigkeit;
- Ressourcenbilanzen und mandatory evaluations;
- Split-, Leakage- und Informationsparitätsregeln;
- Capability- und Claim-Konsistenz;
- Plugin-Artefakt-, Index- und Trust-State-Verträge;
- byteidentische Gradle-/Container-Reproduktion;
- Dokumentationslinks, Mathematik und generierte Seiten;
- die semantische Schlankheit der GitHub-Workflows.

## Die vier Repository-Lebenszyklen

### `test`

```bash
./gradlew test
```

Führt alle Gradle-`Test`-Tasks aus. Dazu gehören je nach Modul auch Browser- und
Container-Testquellen. Bedingte Infrastruktur wird innerhalb der JUnit-Verträge
klassifiziert.

### `check`

```bash
./gradlew check
```

Ergänzt Tests um checkout-lokale Konsistenz- und Evidence-Prüfungen. Dieser Task
ist der normale Merge-Gate für Änderungen ohne vollständige externe
Reproduktionspflicht.

### `fullCheck`

```bash
./gradlew fullCheck
```

Ergänzt `check` um strikte Docker-, Solver-, Benchmark- und
Reproduktionsverträge. Er darf externe Infrastruktur voraussetzen und soll bei
fehlender Pflichtinfrastruktur nicht stillschweigend erfolgreich sein.

### `ciCheck`

```bash
./gradlew --no-configuration-cache ciCheck
```

Ist der einzige autoritative Verifikationsaufruf der zentralen CI. Er führt den
vollständigen Repositoryvertrag aus und erzeugt zusätzlich die veröffentlichbaren
Coverage-, Test-, Benchmark- und Dokumentationsartefakte.

Ein roter GitHub-Lauf muss vom selben Commit mit diesem Befehl reproduzierbar
sein. GitHub-spezifische Veröffentlichungsjobs dürfen das Ergebnis nicht
verändern.

## Externe Voraussetzungen

### Playwright auf Linux

```bash
./gradlew :app:installPlaywrightHostDependencies
```

Dieser Task installiert Hostbibliotheken. Er definiert keine alternative
Testsemantik.

### Docker

Der Docker-Daemon muss für den aktuellen Benutzer erreichbar sein. Tests
verwenden Testcontainers, bauen die realen Dockerfiles und vergeben zufällige
Ports.

### Python-Verifier

Die gepinnte Umgebung wird unter `build/verification-venv` erzeugt. Verifier
starten Gradle nicht rekursiv, sondern prüfen die im selben Taskgraphen erzeugten
Artefakte.

### AI-Knowledge-Vertrag

Der optionale Vertrag wird explizit aktiviert:

```bash
GITHUB_ACTOR=<github-user> \
GITHUB_TOKEN=<package-read-token> \
AI_KNOWLEDGE_EXTRACTOR_ENABLED=true \
  ./gradlew --no-configuration-cache ciCheck
```

Für die gemeinsame Entwicklung kann ein lokaler Checkout eingebunden werden:

```bash
AI_KNOWLEDGE_EXTRACTOR_ENABLED=true \
  ./gradlew --no-configuration-cache \
  -PuseLocalAiKnowledgeExtractor=true \
  -PaiKnowledgeExtractorCheckout=../ai-knowledge-extractor \
  ciCheck
```

## Fokussierte Ausführung

Einzelne Klassen oder Methoden lassen sich mit dem normalen Gradle-Filter
starten:

```bash
./gradlew :app:dockerE2eTest \
  --tests de.regelsuche.dockere2e.ScientificDiscoveryPostgresE2ETest

./gradlew :regelsuche-solver-portfolio:test \
  --tests de.regelsuche.solver.portfolio.Z3SmtSolverBackendTest.systemZ3ReturnsRealProofObject
```

Repositoryweite JUnit-Tags:

```bash
./gradlew test -PincludeTestTags=external-prover
./gradlew test -PexcludeTestTags=external-prover
./gradlew test -PexcludeTestTags=external-prover,slow
```

Wichtige fokussierte Verträge:

```bash
./gradlew verifyPluginArtifactIndexEvidence
./gradlew verifyPluginArtifactTrustEvidence
./gradlew verifyPluginTrustStoreRevisionEvidence
./gradlew verifyJmhBenchmark
./gradlew verifyComparativeBenchmarks
./gradlew verifyDiscoveryChallengeDevelopmentPilots
```

Convenience-Skripte dürfen lediglich an diese checkout-eigenen Tasks delegieren.

## Testdaten und Determinismus

- Fixtures gehören zum fachlichen Test oder zu einer versionierten
  Experimentquelle im Repository.
- Zufallsbasierte Tests verwenden retained Seeds und strikte Grenzen.
- Kanonische Evidence enthält keine Wandzeit, temporären Pfade oder
  Plattformadressen.
- Wiederholte Läufe mit identischen Eingaben müssen semantisch identische,
  bei entsprechendem Vertrag byteidentische Ergebnisse liefern.
- Ein nicht ausgeführter Fall bleibt `SKIPPED`, `UNSUPPORTED` oder
  `NOT_EVALUATED`; er wird nicht aus einer Gesamtzahl entfernt.

## Checkout-Sauberkeit

Ein normaler Test-, Build- oder `ciCheck`-Lauf darf keine tracked Quelldatei
verändern. Abhängigkeitsabgeleitete Ressourcen, generierte Reports und
Zwischenergebnisse gehören unter `build/` oder in einen ausdrücklich
versionierten, separat geprüften Generierungsworkflow.

Ein Dirty-Worktree-Gate darf nicht durch Ignorieren erzeugter Änderungen
umgangen werden. Zuerst ist zu klären, warum ein Build in den Source Tree
schreibt.

## Reports und Diagnoseartefakte

Bei einem Fehler zuerst den kleinsten fehlschlagenden Task und dessen Artefakte
prüfen:

- JUnit XML und HTML unter `<modul>/build/test-results` und
  `<modul>/build/reports/tests`;
- JaCoCo unter `<modul>/build/reports/jacoco`;
- Verifier-Ausgaben und Manifeste unter `build/` beziehungsweise den
  modulspezifischen Reportverzeichnissen;
- Containerlogs in den Testreports;
- zentralen CI-Log unter `build/logs/ci-check.log`.

### Triage-Reihenfolge

1. erster fachlicher Fehler;
2. fehlgeschlagener Task und konkrete Assertion;
3. technische Infrastruktur oder mathematisches Ergebnis unterscheiden;
4. kanonische Inputs, Hashes und Ressourcenbilanz vergleichen;
5. erst danach einen Fix an Test oder Implementierung vornehmen.

## Dokumentationsaufnahmen

Screenshots und Videos sind ein bewusster Ausgabemodus derselben Browser-Tests:

```bash
./gradlew :app:e2eTest -Pregelsuche.recordDocs=true
```

Ein normaler `ciCheck` aktualisiert keine Dokumentationsmedien. Änderungen an
sichtbaren Flows müssen UI, Browser-E2E, Handbuch und gegebenenfalls Screenshots
gemeinsam aktualisieren.

## GitHub-Grenze

Unter `.github/workflows/` verbleiben nur Plattformadapter:

- `gradle.yml` provisioniert die Toolchain, ruft `ciCheck` auf und veröffentlicht
  bereits erzeugte Ergebnisse;
- `release.yml` führt GitHub-spezifische Tag-, Release- und Pull-Request-
  Operationen aus.

`verifyWorkflowSemantics` blockiert workfloweigene Assertions, alternative
Testauswahl und fachliche Parallelpipelines.

## Siehe auch

- [Testing-Strategie](testing-strategy.md)
- [Developer Guide](developer-guide.md)
- [Architektur](architecture.md)
- [Documentation Quality Checklist](documentation-quality-checklist.md)
