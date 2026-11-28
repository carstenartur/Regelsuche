# Kosten der Regelauswahl auf den 24 bekannten Aufgaben

Der Versuch verwendet die veröffentlichten Aufgaben und die eingefrorene Lerngeneration des [früheren Versuchs](../results.md). Bei der Entwicklung der Vorauswahl waren die damaligen Ergebnisse bekannt. Die acht Varianten erhalten dieselben Ausdrücke, Ziele und Budgets. Die Auswahl erhält auf jeder Abfrage nur die üblichen Aufgaben- und Modelldaten. Familiennamen und Referenzlösungen sind ihr unbekannt.

## Ergebnisse

Alle acht Varianten lösten dieselben 16 Aufgaben. LOCAL, INDEXED, SELECTED und ROUTED verwendeten dieselbe geprüfte Regel in 16 ausgewählten Schritten auf zwölf Aufgaben. SELECTED und ROUTED lieferten für alle 24 Aufgaben dieselben Endausgaben, Statuswerte und mathematischen Herleitungen wie LOCAL. Alle Varianten bestanden den separaten Koeffizientenaudit mit jeweils 1.512 zusätzlichen Arbeitseinheiten.

| Variante | Erwerb | Laden und Vorbereitung | Abfragen für 24 Aufgaben | Gesamt |
|---|---:|---:|---:|---:|
| BASE · primitive Suche | 0 | 0 | 4.540 | 4.540 |
| DROP · bisheriger Solver ohne gelernte Regeln | 13.543 | 1.398 | 5.724 | 20.665 |
| LOCAL · bisheriger Solver mit gelernten Regeln | 13.543 | 1.398 | 6.656 | 21.597 |
| INDEXED · bisheriger Formindex | 13.543 | 1.678 | 7.572 | 22.793 |
| SELECTED · neue Vorauswahl | 13.543 | 1.751 | 5.952 | 21.246 |
| ROUTED · Vorauswahl mit Erreichbarkeitsprüfung | 13.543 | 2.092 | 5.668 | 21.303 |
| SELECTED_DROP · Vorauswahl ohne gelernte Anbieter | 13.543 | 1.751 | 6.340 | 21.634 |
| ROUTED_DROP · Vorauswahl und Routing ohne gelernte Anbieter | 13.543 | 2.092 | 6.164 | 21.799 |

Nach Familien summierte Abfragekosten. Jede Familie enthält dieselben vier Werte aus FINAL.

| Familie | DROP | LOCAL | SELECTED | SELECTED_DROP | ROUTED | ROUTED_DROP |
|---|---:|---:|---:|---:|---:|---:|
| Summe zweier Kompensationen | 2.328 | 2.296 | 2.108 | 2.460 | 2.144 | 2.496 |
| Quadrierter Kontext | 988 | 940 | 964 | 1.096 | 988 | 1.120 |
| Rationaler Quotient | 924 | 808 | 852 | 1.032 | 884 | 1.064 |
| Gemeinsamer Faktor | 428 | 748 | 600 | 520 | 400 | 400 |
| Ähnlicher Rest | 768 | 1.480 | 1.028 | 860 | 1.056 | 888 |
| Irreduzible Summe | 288 | 384 | 400 | 372 | 196 | 196 |

Acht frische JVMs bestätigten für alle 192 Kombinationen aus Aufgabe und Variante dieselben Ausgaben, ausgewählten Schritte und logischen Zähler. Das sind 1.536 Auswertungen auf 24 verschiedenen Aufgaben. Die [Einzelergebnisse mit Quelldigest und Zeitbeobachtungen](evidence.json) ermöglichen die Prüfung jeder Familie. Die Eingabefamilien sind öffentlich und teilweise absichtlich verwandt. Mehrere Implementierungsvarianten wurden auf denselben Fällen entwickelt. Die Messung belegt daher das Verhalten auf diesem Panel.

### Gewinn und Amortisation

Die Vorauswahl allein (SELECTED) benötigt 5.952 Einheiten. Das ergibt 228 Einheiten mehr als beim historischen DROP. Erst mit zusätzlicher Erreichbarkeitsprüfung sinkt die Arbeit des kombinierten Verfahrens auf diesem Panel unter die historische Vergleichszahl.

ROUTED benötigt für die 24 Abfragen 56 Einheiten weniger als das historische DROP und 988 Einheiten weniger als der ursprüngliche Lernarm LOCAL. Die zwölf Fälle mit ausgewählter Regel und die übrigen zwölf tragen gegenüber dem historischen DROP −224 und +168 Einheiten zum Unterschied bei. Gegen den eigenen, mit allen neuen Prüfungen vorbereiteten Arm ROUTED_DROP betragen die entsprechenden Differenzen −664 und +168 Einheiten.

Einschließlich Erwerb, Laden und Vorbereitung verbraucht ROUTED auf einem Panel 638 Einheiten mehr als das historische DROP. Die Vorbereitung von ROUTED kostet 694 zusätzliche Einheiten. Bei einer Einsparung von 56 Einheiten je identischem Panel und einmaliger Vorbereitung überstiege die kumulierte Einsparung diese Mehrkosten nach 13 Panels. Das ist eine Hochrechnung für wiederholte identische Last. Der primitive Standardlöser benötigt 4.540 Einheiten für dieselben 16 Lösungen. Gegenüber ihm liegen die Abfragekosten von ROUTED um 1.128 Einheiten höher.

Alle [192 normalisierten Fallzeilen](rows.jsonl) enthalten die vollständigen Ausgaben mit Arbeit und den ausgewählten Schritten. Die zum jeweiligen Schritt gehörende Verifikation und die unabhängige Endwiederholung sind getrennt ausgewiesen.

Gemessen wurde der [veröffentlichte Implementierungsstand](https://github.com/carstenartur/Regelsuche/commit/8fed85672ff73b7e9039959e7278e69447619b52) mit Temurin 25.0.5+8 und Maven 3.9.16 unter Linux. Die acht erfolgreichen Maven-Aufrufe verwendeten `JAVA_TOOL_OPTIONS="-Xms32m -Xmx384m -XX:ActiveProcessorCount=2"`. Bei einem früheren Versuch beendete das System den ersten Testprozess unter paralleler Rechnerlast, bevor eine Ergebnisdatei vorlag. Die dokumentierte Serie begann danach mit einem leeren Ausgabeverzeichnis. Das Zeitfenster pro Arm umfasst Aufbau, Abfragen und den separaten Koeffizientenaudit. Es beginnt nach dem Training und schließt den JVM-Start und das Schreiben der Gesamtausgabe aus. Eine Aussage über reale Laufzeitvorteile erfordert weitere Messungen.

## Vergleich

BASE nutzt den primitiven Standardlöser. DROP lädt die drei geprüften Schemata, behält die trainierten Prioritäten und den inkrementellen Zeitplan und entfernt bei der Anfrage nur den gelernten Anbieter. LOCAL führt denselben Anbieter mit dem bisherigen Matcher aus. INDEXED aktiviert den bereits vorhandenen Index für Wurzel- und Kindformen. SELECTED verwendet die neue Auswahl. ROUTED ergänzt SELECTED um die Erreichbarkeitsprüfung anhand der primitiven Regeln. SELECTED_DROP und ROUTED_DROP behalten den jeweiligen Solver, seine Vorbereitung und dessen Abfrageentscheidung. Sie entfernen den gelernten Anbieter erst auf dem tatsächlich gewählten Suchproblem. Damit bleibt die Vorauswahlarbeit außerhalb dieses Anbieters in seiner Vergleichsgruppe bezahlt. Ein Vergleich zwischen ROUTED und SELECTED umfasst auch den Wechsel des Zeitplans auf den Anfragen, für die der Erreichbarkeitstest alle gelernten Möglichkeiten ausschließt.

Der Erwerb umfasst die ursprünglichen Trainingssuchen, Bildung und Prüfung der Regeln, den getrennten Validierungssatz und die Ableitung der Prioritäten. Bei jeder Variante mit Modell kommt der Aufwand für die erneute Prüfung beim Laden und die konkrete Vorbereitung einmal pro Panel hinzu. Die Abfragekosten enthalten die Verifikation während der Suche und die abschließende unabhängige Wiederholung des ausgewählten Pfads. Eine separate exakte Koeffizientenprüfung vergleicht zu Forschungszwecken jede Ausgabe erneut mit der Eingabe. Sie wird zusätzlich ausgewiesen. Einheiten sind logische Arbeitszähler. Einzelheiten zu nicht logisch gezählter JVM-, JSON- und Dateiarbeit sowie zum Prioritätszähler stehen im [früheren Versuch](../results.md).

## Verwendung mit einer Lerngeneration

Ein vorhandener Snapshot kann mit der neuen Vorauswahl geöffnet und für mehrere Aufgaben verwendet werden. Die Wiederherstellung prüft die enthaltenen Regeln. Die einmaligen Kosten liefert `sessionSetupWork()`. Eine Anfrage liefert ihre eigenen Kosten über `totalWork()`.

```java
var preparation = CumulativeTaskKnowledge.MatcherPreparation.OCCURRENCE_SELECTION_WITH_ROUTING;
var knowledge = CumulativeTaskKnowledge.load(inventory, snapshotPath, preparation);
var result = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST);
```

Der Modus `OCCURRENCE_SELECTION` entspricht SELECTED ohne die zusätzliche Erreichbarkeitsprüfung. Die Studie ergänzt die primitive TRAIN-Rangfolge. Einzelne Abfragekosten über den Standardaufruf können daher abweichen. Für neue Trainingsläufe nimmt `CumulativeTaskKnowledge.empty(inventory, profile, preparation)` dieselbe Einstellung an. Sie gilt auch für die Prüfung neu gebildeter Regeln und für spätere Generationen. Die Strategiewahl `AUTOMATIC` verwendet weiterhin die bei der Validierung gelernten Entscheidungen.

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
export JAVA_TOOL_OPTIONS='-Xms32m -Xmx384m -XX:ActiveProcessorCount=2'
for rotation in 0 1 2 3 4 5 6 7; do
  mvn -B -pl regelsuche-learning -am -Dtest=LocalTraceFamilyStudyTest \
    -Dsurefire.failIfNoSpecifiedTests=false -DlocalTrace.phase=selection \
    -DlocalTrace.directory="$study_root/train" -DlocalTrace.rotation="$rotation" \
    -DlocalTrace.output="$study_root/selection-$rotation.json" test || break
done
python3 docs/research/local-trace-transfer-v1/selection/summarize.py "$study_root" "$study_root/rows.jsonl" > "$study_root/evidence.json"
```

Jede Rohdatei enthält die Eingaben, Ergebnisse, ggf. Herleitungen mit geprüfter Schema-ID, Kosten, CPU- und verstrichene Zeit. Das Auswertungsskript prüft die fachliche Gleichheit aller Rotationen und vergleicht die drei historischen Kontrollarme für jede Aufgabe mit den veröffentlichten Ergebnissen. Es speichert die reproduzierbaren fachlichen Zeilendigestwerte getrennt von den zeitabhängigen Rohdatei-Digests. Ein zusätzliches SHA-256-Manifest bindet die Java-Produktionsquellen der sieben Reaktormodule, ihre POMs, das Eltern-POM und die drei Java-Dateien des Experiments. Zum Vergleich einer Wiederholung das Skript aus genau dem kompilierten Quellstand ausführen.
