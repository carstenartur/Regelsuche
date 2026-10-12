# Testing und Verifikation

Regelsuche besitzt einen repositoryweiten Verifikationsvertrag, der unabhängig
von GitHub Actions aus einem normalen Checkout ausführbar ist. Testauswahl,
Fixtures, Assertions, Evidence-Prüfung und Erfolgsentscheidung liegen in
Gradle, JUnit und den versionierten Skripten des Repositorys.

## Welcher Task ist der richtige?

| Ziel | Befehl | Typische Dauer und Voraussetzungen |
| --- | --- | --- |
| schneller Modultest | `./gradlew :<modul>:test` | JDK 25; für `regelsuche-math-sympy` unter Linux zusätzlich `patchelf` |
| Anwendungstests | `./gradlew :app:test` | JDK 25 |
| Browserfluss | `./gradlew :app:e2eTest` | JDK 25, Chromium/Playwright |
| Containerintegration | `./gradlew :app:dockerE2eTest` | JDK 25, Docker |
| alle Gradle-Testschichten | `./gradlew test` | JDK 25, je nach Tests Docker/Tools und unter Linux `patchelf` |
| Tests plus Vertragsprüfung | `./gradlew check` | JDK 25, Python-`venv`, unter Linux `patchelf` |
| strikte Reproduktion | `./gradlew fullCheck` | zusätzlich Docker, externe Solver und unter Linux `patchelf` |
| autoritativer CI-Lebenszyklus | `./gradlew --no-configuration-cache ciCheck` | vollständige CI-Toolchain einschließlich `patchelf` |

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

Ist der vollständige autoritative Verifikationsaufruf eines normalen Checkouts.
Er führt den gesamten Repositoryvertrag aus und erzeugt zusätzlich die
veröffentlichbaren Coverage-, Test-, Benchmark- und Dokumentationsartefakte.

Ein roter GitHub-Lauf muss vom selben Commit mit diesem Befehl reproduzierbar
sein. GitHub-spezifische Veröffentlichungsjobs dürfen das Ergebnis nicht
verändern.

### Parallele CI-Autoritäten ohne reduzierte Aussagekraft

Bei normalen `push`- und `pull_request`-Läufen verkürzt GitHub Actions
 ausschließlich die Wandzeit des vollständigen Checkout-Vertrags. Vier
unabhängige, frische Checkouts beginnen gleichzeitig:

1. die checkout-lokale Gradle-Korrektheitsautorität ohne doppelte JMH- und
   geladene SymPy-Laufzeitarbeit;
2. die unveränderte vollständige JMH-Autorität auf einem eigenen Runner;
3. die unveränderte vollständige SymPy-Laufzeit- und JaCoCo-Autorität auf einem
   eigenen Runner;
4. der vollständige Maven-Produkt- und Docker-Vertrag.

Der Showcase-Train-Freeze-Lauf über das `create`-Ereignis verwendet dagegen
nur Gradle, JMH und SymPy. Der Maven-Produkt-/Docker-Job ist für dieses Ereignis
bewusst nicht erforderlich; der Konvergenzcheck behandelt diese Ausnahme
explizit und weiterhin fail-closed.

Die Delegation der SymPy-Laufzeittests ist doppelt fail-closed: Sowohl die
Gradle-Property als auch der Workflow-Autorisierungsmarker müssen vorhanden
sein. Ein normaler lokaler `ciCheck` besitzt keinen solchen Marker und bleibt
deshalb vollständig.

Der stabile erforderliche Check `Checkout-local ciCheck` wird erst erfolgreich,
wenn alle für das Ereignis erforderlichen Produzenten erfolgreich waren. Danach
führt ein eigener Konvergenzschritt den isolierten SymPy-JaCoCo-Bericht mit der
projektparallelen Coverage zusammen und wendet den unveränderten
repositoryweiten Coverage-Verifier an. Kein Benchmark, Test, Threshold oder
negativer Ausgang wird durch die Parallelisierung entfernt. Zwischen den
Produzenten werden keine Build-Ausgaben wiederverwendet; nur abgeschlossene
Evidence-Artefakte gelangen in die Konvergenz.

## Externe Voraussetzungen

### Playwright auf Linux

```bash
./gradlew :app:installPlaywrightHostDependencies
```

Dieser Task installiert Hostbibliotheken. Er definiert keine alternative
Testsemantik.

### GraalPy-Isolation nativer Module auf Linux

Der eingebettete SymPy-Adapter setzt `python.IsolateNativeModules=true`, damit
ein nach Timeout verworfener GraalPy-Context innerhalb derselben JVM sicher
durch einen neuen Context ersetzt werden kann. GraalPy kopiert und relokiert
dazu native ELF-Bibliotheken und startet hierfür das Hostwerkzeug `patchelf`.

Unter Ubuntu 22.04 entspricht die von CI und Release verwendete Toolchain:

```bash
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
  patchelf=0.14.3-1 \
  python3-venv \
  z3=4.8.12-1
```

Diese Installation provisioniert nur die Hostwerkzeuge. Die Testauswahl,
Erfolgsentscheidung und mathematische Prüfung bleiben vollständig im Checkout.
Fehlt `patchelf`, muss der GraalPy-Bootstrap technisch fehlschlagen; der Fall
darf nicht als mathematische Nichtzerlegbarkeit umgedeutet werden.

### Docker

Der Docker-Daemon muss für den aktuellen Benutzer erreichbar sein. Tests
verwenden Testcontainers, bauen die realen Dockerfiles und vergeben zufällige
Ports.

### Python-Verifier

Die gepinnte Umgebung wird unter `build/verification-venv` erzeugt. Verifier
starten Gradle nicht rekursiv, sondern prüfen die im selben Taskgraphen erzeugten
Artefakte.

Der Release-Readiness-Verifier benötigt zusätzlich POSIX-Verzeichnisdeskriptoren
mit `O_NOFOLLOW`; diese Dateigrenze ist unter Linux kontrolliert. Ohne die
erforderlichen Funktionen endet er ausdrücklich mit `UNSUPPORTED_PLATFORM`,
auch im aggregierten `check`-Pfad. Ein nicht unterstütztes System erhält keine
erfolgreiche Qualifikation. Gewöhnliche Java-Modultests sind davon unabhängig;
Windows und andere nicht ausgeführte Plattformen sind für diesen Verifier nicht
qualifiziert. Details stehen bei der
[Release-Evidence-Integrität](release-evidence-task-reuse.md#evidence-integrity).

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

- `gradle.yml` provisioniert unabhängige Runner, ruft ausschließlich
  checkout-eigene Autoritäten auf, konvergiert deren Evidence und veröffentlicht
  bereits erzeugte Ergebnisse;
- `release.yml` führt GitHub-spezifische Tag-, Release- und Pull-Request-
  Operationen aus.

`verifyWorkflowSemantics` blockiert workfloweigene Assertions, alternative
Testauswahl und fachliche Parallelpipelines.

### Native Java-Coverage

Nach erfolgreicher Konvergenz übermittelt der Java-Reporter dieselben kanonischen
JaCoCo-Messungen aus Gradle und der isolierten SymPy-Autorität an GitHub. Alle 23
Policy-Module, unbedeckten Zeilen und Coverage-Grenzen bleiben erhalten;
`config/quality/coverage-policy.json` und
`build/reports/quality/coverage-report.json` bleiben die numerische Autorität.

`JacocoCoberturaExporter` erzeugt eine Cobertura-Klasse je Repository-Quelldatei.
Innere Klassen verdoppeln keine Zeilen; gleiche Dateinamen verschiedener Module
behalten ihre getrennten relativen Pfade. JaCoCo-Instruktionen bestimmen binäre
Zeilentreffer, keine erfundenen Ausführungszahlen. Zeilen- und Branchsummen müssen
auf Quell-, Modul- und Aggregatebene exakt übereinstimmen. Komplexität übernimmt
die tatsächlichen covered-plus-missed-Zähler auf Quell-, Package- und Reportebene;
nur Quellen ohne ausführbare Zeilen dürfen einen fehlenden Zähler als nicht
verfügbaren Nullwert ausweisen. SHA-256 bindet Policy, Aggregat, jedes XML und jede
gemeldete Quelle. Fehlende/zusätzliche Module, ungültige Zähler, Pfad-/Symlink-Flucht
und externe XML-Entitäten werden abgewiesen. Üblicher JaCoCo-DOCTYPE wird ohne
DTD-Nachladen akzeptiert; Tests prüfen die Ausgabe gegen die erhaltene strikte
Cobertura-04-DTD ohne Netzwerkzugriff.

Die Produzenten starten die ausschließlich vom JDK abhängige Java-25-Quelldatei
`NativeCoverageSourceReceipt` mit `capture <root> <temporary-receipt>` vor ihrer
Messung und `confirm <root> <temporary-receipt> <retained-receipt>` danach.
Capture verlangt einen vollständig unveränderten getrackten Checkout und lehnt
auch ignorierte ungetrackte Java-Dateien unter `*/src/main/java/**` ab. Confirm
prüft diese ungetrackten Quellen ebenfalls und verlangt bytegenau `HEAD\nTREE\n`
des ursprünglichen Belegs sowie unveränderte Java-/Config-Eingaben, bevor der
aufzubewahrende Beleg geschrieben wird. Relative Belegpfade werden zu root aufgelöst;
generierte Buildkopien liegen außerhalb der kanonischen Modulquellen.
`NativeCodeCoverageMain` verwendet dieselben `git`-, `hash`- und `verifyInputs`-Helfer
auch vor und nach dem Export. Bei Konvergenz
müssen beide Produzentenbelege zum Checkout passen und dessen Tree zum Upload-Ziel.
Ein PR-Upload bindet den tatsächlichen Head desselben Repositorys; ein separater
Test-Merge ist nur mit identischem Tree zulässig.

`:regelsuche-quality:nativeCoverageRuntime` wird im selben Gradle-Aufruf angefordert
und stellt das JAR samt bestehenden Abhängigkeiten bereit. Die Java-25-Konvergenz
benötigt die Merge-Eltern (Checkout-Tiefe 2) und startet Main mit `--root`,
`--gradle-source`, `--sympy-source` und `--output`. Im Konvergenzjob erhält nur der
Upload-Schritt `GITHUB_TOKEN`; dieser Job besitzt `code-quality: write`. Uploads
gelten für PRs desselben Repositorys und Pushes auf den Default-Branch; Fork-PRs
und andere Ereignisse behalten einen expliziten Skip ohne Upload. Ein
`pull_request_target`-Pfad ist dafür nicht vorgesehen.

Versionsquelle des Java-Clients ist das Protokoll der
[upload-code-coverage-Action](https://github.com/actions/upload-code-coverage/tree/2b21a77928be8d5168c2b9581a67f2adbebacc52)
in `upload_coverage.py`, `resolve_upload_context.py` und `action.yml`.
Ein PUT an `/repos/{repository}/code-coverage/report` sendet Base64/GZIP-Cobertura
mit Sprache Java, dem Label `code-coverage/jacoco` und genau einer PR-/Ref-Zuordnung.
GET `/repos/{repository}/code-coverage/reports/{id}` prüft die Verarbeitung nach
anfänglich fünf Sekunden, exponentiell bis maximal 60 Sekunden Wartezeit, innerhalb
einer monotonen 160-Sekunden-Frist mit maximal 30 Sekunden je Anfrage. Nur eine Report-ID
mit Status `succeeded` besteht. HTTP 200 ohne ID dokumentiert übersprungenes
Speichern und lässt einen unterstützten Upload fehlschlagen; transiente Abfragefehler
dürfen weder Ablehnung noch Frist verdecken. PUT wird nicht blind wiederholt,
Zugangsdaten werden redigiert, Abbruch und Timeout schlagen fehl.

Das Artefakt `coverage-verification` erhält `coverage.cobertura.xml`,
`coverage-manifest.json` und `native-coverage-outcome.json` unter
`build/reports/quality/native-coverage`; das Outcome bindet beide Produzenten,
deren ursprüngliche Belege zusätzlich in den Produzentenartefakten liegen.
Die Code-Quality-Einrichtung des Repositorys, native Minimum-/Drop-Regeln,
Base-Branch-Vergleich und Review-Anforderungen müssen separat erfüllt sein.
Ein Merge belegt ihre Auswertung nicht; künstliche Checkruns, administrative
Umgehungen und eine Identität als offizielle Action sind kein Teil dieses Reporters.
Die Implementierung nutzt Java 25 und bestehendes Jackson, ohne neue Abhängigkeit
oder Python-Automation. Proofs, arithmetische Kosten, Java-Guards und der qualifizierte
SDK-Pin bleiben unverändert; die Runtime liegt außerhalb der standalone SDK-Closure.

Die ursprünglichen 30 Exporter- und 35 Client-/Main-Tests bestanden unabhängig lokal.
Der damalige vollständige Quality-Suite-Versuch war am bestehenden Scanner-Download
blockiert. Erst normale CI qualifiziert Runtime-Staging, den vollständigen Export
und tatsächliche native Verarbeitung; die historischen Kontrollen ersetzen das nicht.
Die Integrationsbasis war SDK-PR #1087, Merge
`e4ea0d9eedda28868a181df61fc3fd6cb1c4ee3a`, mit demselben Tree wie die qualifizierte
Quelle `6a29154e217ea8748257cfda0340eb463ff157b2`. Die getrennten Produkt-/Help-Belege
und der Vergleich über 3.109 Quellen bleiben Sandbox-PR #1695, Merge
`7e11fd618733a390268d38fbd7e12014b0948f23`, zugeordnet. Frühere Ausführungsbelege
behalten ihre tatsächliche Quellrevision. Für diese Berichtsintegration gilt ein
Review-Ziel von 1.500 und eine harte Grenze von 2.000 geänderten Textzeilen.

## Siehe auch

- [Testing-Strategie](testing-strategy.md)
- [Developer Guide](developer-guide.md)
- [Architektur](architecture.md)
- [Documentation Quality Checklist](documentation-quality-checklist.md)
