# Kosten der Regelauswahl auf den 24 bekannten Aufgaben

Der Versuch verwendet die veröffentlichten Aufgaben und die eingefrorene Lerngeneration des [früheren Versuchs](../results.md). Bei der Entwicklung der Vorauswahl waren die damaligen Ergebnisse bekannt. Die acht Varianten erhalten dieselben Ausdrücke, Ziele und Budgets. Die Auswahl erhält auf jeder Abfrage nur die üblichen Aufgaben- und Modelldaten. Familiennamen und Referenzlösungen sind ihr unbekannt.

## Vergleich

BASE nutzt den primitiven Standardlöser. DROP lädt die drei geprüften Schemata, behält die trainierten Prioritäten und den inkrementellen Zeitplan und entfernt bei der Anfrage nur den gelernten Anbieter. LOCAL führt denselben Anbieter mit dem bisherigen Matcher aus. INDEXED aktiviert den bereits vorhandenen Index für Wurzel- und Kindformen. SELECTED verwendet die neue Auswahl. ROUTED ergänzt SELECTED um die Erreichbarkeitsprüfung anhand der primitiven Regeln. SELECTED_DROP und ROUTED_DROP behalten den jeweiligen Solver, seine Vorbereitung und dessen Abfrageentscheidung. Sie entfernen den gelernten Anbieter erst auf dem tatsächlich gewählten Suchproblem. Damit bleibt die Vorauswahlarbeit außerhalb dieses Anbieters in seiner Vergleichsgruppe bezahlt. Ein Vergleich zwischen ROUTED und SELECTED umfasst auch den Wechsel des Zeitplans auf den Anfragen, für die der Erreichbarkeitstest alle gelernten Möglichkeiten ausschließt.

Der Erwerb umfasst die ursprünglichen Trainingssuchen, Bildung und Prüfung der Regeln, den getrennten Validierungssatz und die Ableitung der Prioritäten. Bei jeder Variante mit Modell kommt der Aufwand für die erneute Prüfung beim Laden und die konkrete Vorbereitung einmal pro Panel hinzu. Die Abfragekosten enthalten die Verifikation während der Suche und die abschließende unabhängige Wiederholung des ausgewählten Pfads. Eine separate exakte Koeffizientenprüfung vergleicht zu Forschungszwecken jede Ausgabe erneut mit der Eingabe. Sie wird zusätzlich ausgewiesen. Einheiten sind logische Arbeitszähler. Einzelheiten zu nicht logisch gezählter JVM-, JSON- und Dateiarbeit sowie zum Prioritätszähler stehen im [früheren Versuch](../results.md).

## Verwendung mit einer Lerngeneration

Ein vorhandener Snapshot kann mit der neuen Vorauswahl geöffnet und für mehrere Aufgaben verwendet werden. Die Wiederherstellung prüft die enthaltenen Regeln. Die einmaligen Kosten liefert `sessionSetupWork()`. Eine Anfrage liefert ihre eigenen Kosten über `totalWork()`.

```java
var preparation = CumulativeTaskKnowledge.MatcherPreparation.OCCURRENCE_SELECTION;
var knowledge = CumulativeTaskKnowledge.load(inventory, snapshotPath, preparation);
var result = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST);
```

Für neue Trainingsläufe nimmt `CumulativeTaskKnowledge.empty(inventory, profile, preparation)` dieselbe Einstellung an. Sie gilt auch für die Prüfung neu gebildeter Regeln und für spätere Generationen. Die Strategiewahl `AUTOMATIC` verwendet weiterhin die bei der Validierung gelernten Entscheidungen.

## Reproduktion

Im Wurzelverzeichnis dieses Stands mit JDK 25, Maven und Python 3 ausführen. Zuerst die festgelegten Lehrdaten und Modellbytes am unveränderten Ausgangsstand rekonstruieren.

```sh
study_root=$(mktemp -d)
git worktree add --detach "$study_root/before" 0939426af42a678fdfe75cc325b99994970c66d5
(
  cd "$study_root/before" &&
  mvn -B -pl regelsuche-learning -am -Dtest=LocalTraceFamilyStudyTest \
    -Dsurefire.failIfNoSpecifiedTests=false -DlocalTrace.phase=train \
    -DlocalTrace.directory="$study_root/train" test
)
```

Die neue Auswertung verlangt den historischen SHA-256-Digest von `freeze.json` und prüft die darin gebundenen Modellbytes und das Regelinventar. Jede der acht frischen JVMs setzt einen anderen Arm an die erste Stelle.

```sh
for rotation in 0 1 2 3 4 5 6 7; do
  mvn -B -pl regelsuche-learning -am -Dtest=LocalTraceFamilyStudyTest \
    -Dsurefire.failIfNoSpecifiedTests=false -DlocalTrace.phase=selection \
    -DlocalTrace.directory="$study_root/train" -DlocalTrace.rotation="$rotation" \
    -DlocalTrace.output="$study_root/selection-$rotation.json" test || break
done
python3 docs/research/local-trace-transfer-v1/selection/summarize.py "$study_root" "$study_root/rows.jsonl" > "$study_root/evidence.json"
```

Jede Rohdatei enthält die Eingaben, Ergebnisse, ggf. Herleitungen mit geprüfter Schema-ID, Kosten, CPU- und verstrichene Zeit. Das Auswertungsskript prüft die fachliche Gleichheit aller Rotationen und vergleicht die drei historischen Kontrollarme für jede Aufgabe mit den veröffentlichten Ergebnissen. Es speichert die reproduzierbaren fachlichen Zeilendigestwerte getrennt von den zeitabhängigen Rohdatei-Digests. Ein zusätzliches SHA-256-Manifest bindet die Java-Produktionsquellen der sieben Reaktormodule, ihre POMs, das Eltern-POM und die drei Java-Dateien des Experiments. Zum Vergleich einer Wiederholung das Skript aus genau dem kompilierten Quellstand ausführen.
