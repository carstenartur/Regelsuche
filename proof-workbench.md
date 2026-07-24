# Proof Workbench

Regelsuche stellt hinter dem Tab **Proof-Jobs** eine asynchrone Beweispipeline bereit. Lean- und SMT-Worker auf Basis von Z3 beziehungsweise cvc5 verarbeiten Aufträge aus einer persistenten Queue und schreiben pro Auftrag ein strukturiertes Artefakt-Bundle.

![Proof-Job-Panel mit Eingabefeldern, Jobliste, Status und Artefakt-Aktion](assets/screenshots/proof-job-panel.png)

*Der Screenshot zeigt den vollständigen Browserfluss vom Beweisauftrag bis zum geöffneten Artefakt-Bundle.*

## Bedienung in der Web-Workbench

1. Zunächst eine Suche oder Demo ausführen, damit die weiterführenden Tabs sichtbar werden.
2. Den Tab **Proof-Jobs** öffnen.
3. Im Feld für die linke Seite das Ausgangspattern und im Feld für die rechte Seite das Zielpattern eingeben.
4. Erforderliche Annahmen ergänzen und bei Bedarf die Priorität anpassen.
5. **Job einreichen** wählen.
6. Den Auftrag in der Jobliste verfolgen. **Aktualisieren** lädt den aktuellen Zustand; **Abbrechen** stoppt einen noch nicht terminalen Auftrag.
7. Bei einem abgeschlossenen Auftrag **Artefakte** öffnen und Beweisskript, Standardausgabe, Fehlerausgabe sowie Metadaten getrennt prüfen oder herunterladen.

Die Oberfläche muss klar unterscheiden zwischen *wartend*, *laufend*, *erfolgreich bewiesen*, *fachlich nicht bewiesen*, *fehlgeschlagen* und *abgebrochen*. Ist die Proof-Funktion in der Serverkonfiguration deaktiviert, erscheint sie als nicht verfügbar und nicht als uninterpretierte Serverfehlermeldung.

**Technische Zuordnung:** Der vollständige HTTP-Vertrag steht im Swagger-Bereich *Proof Jobs*. Diese Seite wiederholt keine Methoden, Pfade, Payloads oder Statuscodes.

## Aussagekräftiger Browserflow

Der dokumentierte Browser-E2E-Flow reicht die Sophie-Germain-Identität ein:

```text
a^4 + 4*b^4
→ (a^2 - 2*a*b + 2*b^2)*(a^2 + 2*a*b + 2*b^2)
```

Die Identität ist auch in der generierten Discovery Gallery als Hidden-Structure-Bridge mit gelernter Makrowiederverwendung dokumentiert. Sie ersetzt im sichtbaren Produktfluss die frühere Neutralregel `a + 0 → a`, die zwar technisch korrekt, als Demonstration der Proof-Workbench aber wenig aussagekräftig war.

Die SMT-Bridge expandiert begrenzte nichtnegative ganzzahlige Exponenten in gewöhnliche nichtlineare reelle Arithmetik. Andere Exponenten verwenden eine korrekt zweistellig deklarierte `pow`-Fallback-Funktion. Dadurch erzeugt die sichtbare `a^4`-Schreibweise einen gültigen Beweisauftrag und ist nicht nur eine kosmetische Darstellung.

Der Browser-Test wartet auf die Jobliste, öffnet die Artefakte und erzeugt den Dokumentations-Screenshot. Der deterministische Test-Worker prüft bewusst den kompletten Browser-, Queue-, Scheduler- und Artefaktfluss; die mathematische Solverausführung wird separat mit den realen SMT-Workern und dem Proof-Image getestet. Die Oberfläche und die Dokumentation kennzeichnen diesen Unterschied ausdrücklich.

Der Screenshot wird mit folgendem lokalen Befehl reproduzierbar aktualisiert:

```bash
./gradlew e2eTest -Pregelsuche.recordDocs=true
```

## Architektur

```text
ProofJob ──► JsonFileProofJobRepository (persistente Queue)
              │
              ▼
ProofJobScheduler ──► CompositeProofWorker
                          ├── LeanProofWorker
                          └── SmtProofWorker
              │
              ▼                            ▼
JsonFileProofCache               JsonFileProofArtifactRepository
(Ergebniscache)                  (proofs/<jobId>/{proof.lean|smt2,
                                  stdout.txt, stderr.txt,
                                  metadata.json})
```

Queue, Cache und Artefaktablage verwenden atomare temporäre Schreibvorgänge und überstehen Neustarts.

Der aktive Worker beziehungsweise die Worker-Komposition wird durch die Anwendung konfiguriert und nicht pro Auftrag ausgewählt. Damit kann ein Aufrufer die Proof-Grenze nicht unbemerkt durch eine Eingabe verändern.

## Konfiguration

| Umgebungsvariable | Standard | Zweck |
| --- | --- | --- |
| `REGELSUCHE_PROOF_ENABLED` | `true` | Aktiviert Scheduler, grafischen Proof-Workflow und zugehörige REST-Operationen. |
| `REGELSUCHE_PROOF_ARTIFACT_PATH` | `<persistencePath>/proofs` | Wurzelverzeichnis der auftragsspezifischen Bundles. |
| `REGELSUCHE_PROOF_JOB_STORE` | `<persistencePath>/proof-jobs.json` | Persistente Job-Queue. |
| `REGELSUCHE_PROOF_CACHE` | `<persistencePath>/proof-cache.json` | Ergebniscache für wiederholte Beweisaufträge. |

JVM-Properties (`regelsuche.proof.enabled` und verwandte Einstellungen) haben in Tests Vorrang vor der Umgebung.

## Artefakt-Bundle

Jeder terminale Übergang — Erfolg ebenso wie Fehler — schreibt ein einheitliches Bundle:

```text
proofs/
└── <jobId>/
    ├── proof.lean      # alternativ proof.smt2 oder proof.txt
    ├── stdout.txt
    ├── stderr.txt      # Fehlergrund bei Retry oder Fehlschlag
    └── metadata.json   # Auftrag, Worker, Tool, Status, Dauer und Zeitpunkte
```

Die grafische Artefaktansicht erklärt den Zweck der Dateien und bietet sie einzeln an. Layout und Schutz gegen Pfad-Traversal werden durch automatisierte Repository-Tests abgesichert.

## Docker-Image mit realen Solvern

`Dockerfile.proof` stellt eine Laufzeit mit Z3 und cvc5 bereit:

```bash
docker build -f Dockerfile.proof -t regelsuche-proof .
docker run --rm -p 8080:8080 regelsuche-proof
```

Lean 4 ist wegen seiner Größe optional:

```bash
docker build -f Dockerfile.proof --build-arg INSTALL_LEAN=true \
    -t regelsuche-proof-lean .
```

Die Image-Verifikation steckt nicht in einem GitHub-spezifischen Shell-Ablauf. `ProofDockerImageIntegrationTest` baut das reale Image über Testcontainers, prüft die installierten Solver, reicht die Sophie-Germain-Identität über die öffentliche Anwendungsschnittstelle ein, wartet auf den formalen Abschluss und verifiziert das vollständige Artefakt-Bundle.

Der maßgebliche, auch außerhalb von GitHub ausführbare Befehl lautet:

```bash
./gradlew :app:dockerE2eTest \
  --tests de.regelsuche.dockere2e.ProofDockerImageIntegrationTest
```

Die zentrale CI ruft lediglich den repositoryweiten Gradle-Vertrag `fullCheck` auf, der diesen Test enthält.

## Tests

- `ProofJobsApiTest` — vollständiger technischer Lebenszyklus der Proof-Operationen.
- `JsonFileProofArtifactRepositoryBundleTest` — Bundle-Layout und Traversal-Schutz.
- `ProofConfigTest` — Konfigurationspriorität und boolesche Aliase.
- `SmtProofBridgeTest` — Potenzexpansion, `pow`-Fallback, Sophie-Germain-Obligation und Produktionskandidat.
- `ProofJobPanelBrowserFlowTest` — Sophie-Germain-Identität über UI, Queue, Scheduler und Artefaktansicht.
- `ProofDockerImageIntegrationTest` — realer Z3-Beweis und vollständiges Bundle im versionierten Proof-Image.

Die synchrone Einzelschritt-Proof-Funktion ist in [Proof Bridge](proof-bridge.md) beschrieben; ihr technischer Vertrag steht ebenfalls in Swagger/OpenAPI.
