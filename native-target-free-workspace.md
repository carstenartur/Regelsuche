# Nativen target-free Trace in der Workbench öffnen

Der bestehende `TargetFreeRepresentationDiscoveryRun` besitzt einen additiven
Einstieg für kleine öffentliche BestFirst-Läufe. Er führt die tatsächliche
Suche aus und speichert ihren bisherigen `RepresentationDiscoveryRunWorkspace`
sowie das daran gebundene Kandidatenartefakt:

```java
var result = TargetFreeRepresentationDiscoveryRun.writeTargetFree(
    Path.of("runs"),
    "(x + 0) * (x + 0)",
    new SearchHeuristic(3, 12, 1, 1, 8, 8),
    repositoryCommit);
String runId = result.workspace().runId();
```

Die Klassen liegen in `de.regelsuche.discovery.representation`,
`de.regelsuche.search` und `java.nio.file`. `repositoryCommit` ist die
Repository-Revision des ausgeführten Checkouts. Die Workbench mit
`regelsuche.discovery.runs.directory=<absoluter runs-Pfad>` öffnen und über
`#run=<64-stelliger Run-Digest>` auswählen. Der vorhandene Run- und Dossier-API-Pfad
lädt die gespeicherten Dateien erneut geprüft; es gibt keine zusätzliche
Laufidentität und keinen neuen HTTP-Suchstart.

`writeTargetFree(...)` speichert Dossier und Workspace unter den bestehenden
Monitoren in der Reihenfolge Dossier-Store, dann Workspace. Wenn die
Workspace-Aufnahme fehlschlägt, nimmt der Aufruf ausschließlich seinen eigenen
neu veröffentlichten Dossier-Hardlink zurück. Der noch gehaltene temporäre
Hardlink bindet die Rücknahme an dieselbe Datei. Bereits gespeicherte Dossiers
werden auch bei Konflikten nicht gelöscht oder ersetzt; Fehler der Rücknahme
bleiben als unterdrückte Exceptions am ursprünglichen Fehler sichtbar.
Dies koordiniert die vorhandenen Repository-APIs innerhalb einer JVM. Es ist
keine Transaktion über Prozessabstürze, mehrere JVMs oder gleichzeitig von außen
veränderte Repository-Pfade.

`runTargetFree(...)` führt denselben Lauf ohne Speicherung aus. Nur der Runner
kann dessen `RunResult` erzeugen. Er enthält den tatsächlichen
`GoalSearchResult`, die unveränderliche Beobachtung und den bestehenden Workspace.
Der Quelltext erhält vor der Suche die kanonische AST-Schreibweise des bestehenden
Run-Inputs. Dadurch verwenden auch Eingaben wie `x+0` oder zusätzliche äußere
Klammern denselben tatsächlichen Suchstart, Workspace-Input und Replay-Endpunkt.

## Quellenbindung und Anzeige

Das additive Artefakt `regelsuche.target-free-search-execution/v1` bindet
`SEARCH_GRAPH`, `REPRESENTATION_CANDIDATES`, `CANDIDATE_DOSSIERS`, `PATH_REPLAY`
und `PROGRESS_LEDGER` an denselben Inhaltshash. Die bestehende Dossier-API
dispatcht zwischen diesem Schema und dem bisherigen SymPy-Brückenschema.
Alle Importe und Ladevorgänge prüfen die vollständige Workspace-Bindung und
kanonische Originalbytes. Geänderte oder fremde Artefakte werden abgewiesen.

| Aufzeichnung | Bedeutung |
| --- | --- |
| `states` | Tatsächlich besuchte native Zustände mit vollständigem `SearchStateReplay` und Ausführungswurzel |
| `generations` | Tatsächlich vom AST-Engine zurückgegebene Transformationskandidaten; noch keine Übernahme durch die Suche |
| `transitions` | Eingehende Übergänge der tatsächlich besuchten Nichtwurzel-Zustände |
| `events` | Unveränderter nativer Ereignisinhalt einschließlich Enqueue-, Verwerfungs- und Endereignissen |
| `applicationKeys` im Zustand | Lexikographisch sortierte Identitätsmenge, ausdrücklich keine Reihenfolge |
| `generationSequences` | Geordnete Referenzen aus den tatsächlich gespeicherten `Transformation`-Instanzen des Zustandspfads |

Der optionale AST-Observer erfasst die konkrete `TreePosition` während der
Erzeugung. Er bindet Quellwurzel, Quellvorkommen, ersetztes Vorkommen und Ergebnis
an dieselbe zurückgegebene Transformation. Mehrfach gleiche Subterme erhalten
die tatsächlich erzeugte Position; eine Suche nach dem ersten passenden Hash
findet nicht statt. Die Workbench zeigt diese Vorkommen zusammen mit der
ausgewählten Graphkante und der gespeicherten Schrittabfolge. Neuladen und
Auswahlwechsel behalten die Run-/Kandidaten-/Kantenbindung bei.

Ein nativer Schritt lässt sich zusätzlich mit
`#run=<Run-Digest>&candidate=<Zustands-ID>&artifact=PATH_REPLAY&generation=<Sequenz>`
öffnen. `generation` bezeichnet die globale, bei **0** beginnende
Erzeugungssequenz im gespeicherten Lauf, nicht die sichtbare Schrittnummer.
Die Sequenz muss im `generationSequences`-Pfad dieses Kandidaten enthalten sein.
Vor/Zurück sowie die Schrittschaltflächen behalten den Endkandidaten bei; auch
eine frühere Kante wird über ihren exakten gespeicherten Präfixzustand gebunden.
Die Graphansicht öffnet diese Kante und führt zurück zum selben Replay-Schritt.
Vorkommen werden ausschließlich aus dessen gespeicherter `occurrencePath`
angezeigt, auch bei identischen Subtermen.

Ein zusätzliches `edge` muss genau zu dieser Generierung und diesem Pfad passen.
Fremde Generierungen, widersprüchliche Kanten oder ungültige Sequenzschreibweisen
werden sichtbar abgewiesen. Alte Links ohne `generation` behalten ihre bisherige
Bedeutung: eine explizite Kante muss zum Endkandidaten führen. Beim Wechsel des
Kandidaten werden Schritt und Kante gelöscht; Rollenwechsel und Neuladen behalten
die Auswahl. Verspätete Antworten dürfen einen neueren Run oder Auswahlschritt
nicht ersetzen. Das ältere SymPy-Brückendossier enthält keine adressierbaren
nativen Generierungen und akzeptiert diesen zusätzlichen Auswahlparameter nicht.

`executionHash` bezeichnet die kanonische Ausführungsprovenienz. Er ist kein
formaler Proof Root. Die primitive Ausführung erzeugt keine formalen
Beweisobligationen; `PROOF_OBLIGATIONS` bleibt `NOT_PRODUCED`.

## Arbeit, Status und Replay

Der native `GoalStatus` bleibt wörtlich `UNTARGETED`, auch bei einer verbleibenden
Frontier oder ausgeschöpften Suchgrenze. Der Workspace meldet lediglich, dass
die native Ausführung zurückgekehrt ist. Er behauptet keine vollständige
Closure und keine Zielerreichung. Die nativen Zähler und das letzte
Frontier-Ereignis bleiben sichtbar.

`configuredWork` und `consumedWork` zählen hier zurückgegebene
Transformationskandidaten. Die Obergrenze ist
`maxVisitedExpressions * maxCandidatesPerState`; gezählt wird jede Erzeugung
einmal. Der separate Arbeitsvektor der erzeugten Kandidaten und die
Pfadprovenienz bleiben erhalten. Gemeinsame Pfadpräfixe werden nicht aufsummiert.
Diese Einheit wird im Dossier explizit angezeigt.

Observer, Telemetrie, Integritätsprüfungen, Serialisierung und Speicherung
verursachen zusätzliche Arbeit. Sie ist nicht als Laufzeit gemessen und nicht
in den Kandidaten-/Pfadzählern enthalten. Es folgt kein Geschwindigkeits- oder
Vergleich bei gleicher Gesamtarbeit.

Die UI öffnet die gespeicherte Schrittabfolge. Ein expliziter Java-Aufruf
`result.artifact().replay()` führt eine neue native Suche aus und vergleicht den
vollständigen kanonischen Inhalt einschließlich Ereignissen, Positionen,
Annahmen, Zählern und Konfiguration. Geladene Bytes können dieses unabhängige
Ergebnis nicht bereitstellen. Auch neu gehashte falsche Telemetrie scheitert
an diesem Replay.

Die Admission umfasst höchstens 2.048 Quellzeichen, Tiefe 8, 64 besuchte
Zustände, 32 Kandidaten je Zustand, 8 expandierende Schritte und Beam-Breite 64.
Das vollständige Artefakt muss zusätzlich in die bestehende Grenze von 1 MiB
passen; andernfalls wird es nicht gespeichert. Suchparameter werden nicht
stillschweigend gekürzt.

## Verbleibender Umfang

Dieser Einstieg verwendet den nativen BestFirst-Runner mit CORE-Inventar und
R1-Informationsgrenze. Andere Policies, Inventare und Studien werden dadurch
nicht qualifiziert. Die bisherigen frozen v1 Studienformate, Renderer,
mechanischen Arbeitsverträge und Default-Aufrufer bleiben unverändert.

Das Stufenmodell aus `RepresentationSalienceCaseAudit` benötigt zusätzlich
tatsächliche Referenz-, Formation-, Retention-, Recognition-, Ranking- und
Expertenevidence. Eine Klassifikation der ersten verlorenen Stufe wird aus
diesem Trace nicht erfunden; `stageAssessment=NOT_EVALUATED`. Formale Proof
Roots, Rule Radar, Novelty, nachgelagerte Stufenqualifikation und empirische
Gewinneraussagen bleiben außerhalb dieser Integration.
