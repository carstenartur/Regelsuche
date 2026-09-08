# Gelernte Strategien gezielt fortsetzen

Der [ursprüngliche Strategievergleich](trace-strategy-transfer.md) zeigte einen
konkreten Engpass: Gelernte Programme wurden an jedem Zustand zusätzlich zu allen
Einzelregeln versucht. Dadurch entstanden erfolglose Aufrufe und parallele Wege
über dieselben Zwischenzustände. Eine nützliche Reihenfolge allein spart noch
keine Arbeit.

Der neue `TraceStrategyDispatchLearner` lernt deshalb, **wann eine Fortsetzung
die Kosten rechtfertigt**. Auf 288 neuen öffentlichen Entwicklungsaufgaben
erreicht er dieselben Scores wie die Kontrollen und benötigt 1,4 % weniger
gezählte Anwendungsarbeit als eine starke Greedy-Baseline. Die einmaligen
Lernkosten sind auf diesem Bestand noch nicht amortisiert.

## Reproduzieren und ansehen

Mit Java 25:

```bash
./gradlew :regelsuche-learning:strategyDispatchDemo
```

Mit Maven 3.9.9 oder neuer aus der 3.x-Linie einschließlich Integrationsprüfung:

```bash
mvn --batch-mode --no-transfer-progress -pl regelsuche-learning -am \
  -Dtest=TraceStrategyDispatchExampleTest \
  -Dregelsuche.strategyDispatch.output="$PWD/build/reports/strategy-dispatch" test
```

Die lokale `index.html` ist ohne Server nutzbar. Sie enthält alle Aufgaben, einen
Suchfilter, sämtliche Profile und die inneren primitiven Schritte jeder
Programmkante. Daneben liegen das eingefrorene Protokoll, Modell, alle
Trainingsversuche, alle Anwendungssuchen und ein Hashmanifest. Der Verzeichnisname
bindet auch die Darstellung und den Artefaktbestand; neue Renderer erzeugen ein
neues Paket, ohne vorhandene Artefakte zu überschreiben.

Die kleine [Referenz](generated/trace-strategy-dispatch-reference.json) bindet
über den vollständigen Berichtshash alle 1.152 Ergebniszeilen. Sie und der
[generierte Bericht](generated/trace-strategy-dispatch-reference.md) werden gegen
einen frischen Lauf geprüft. Das [Protokoll](generated/trace-strategy-dispatch-protocol.json)
wurde für Version 1 vor der ersten Ausführung dieses Anwendungsbestands
festgeschrieben. Version 2 wiederholt dieselben Eingaben mit verpflichtender
Minimalitätsprüfung bei der Formierung. Die unveränderten
[Referenzen der Version 1](generated/strategy-history-v1/trace-strategy-dispatch-reference.md)
bleiben erhalten; der erneute Lauf ist kein neuer Holdout.

## Was sich in der Ausführung ändert

1. **Vorhandene erste Schritte wiederverwenden.** Die Einzelregeln erzeugen wie
   bisher primitive Kandidaten. Deren Regel-IDs ergeben eine Bitmaske der
   verfügbaren Regeln. Die Fortsetzung beginnt hinter einem tatsächlich
   erzeugten ersten Schritt; sie berechnet diesen nicht nochmals.
2. **Nutzbare Kontexte aus TRAIN wählen.** Die Formierung verwendet die vier
   bisherigen Aufgaben und übernimmt nur [geprüfte kürzeste primitive Folgen](primitive-trace-minimality.md).
   Acht mathematisch getrennte weitere TRAIN-Aufgaben
   dienen der Auswahl. Für jede mögliche zusätzliche Route wird die gesamte
   Auswahlserie ausgeführt. Nur eine strikte Arbeitsreduktion ohne schlechteren
   Score auf irgendeiner Trainingsaufgabe wird akzeptiert. Auch abgelehnte
   Versuche und die Kosten der Kontextsammlung werden bezahlt und gespeichert.
3. **Lineare Fortsetzungen einmal kompilieren.** `CompiledLinearRewriteEngine`
   übersetzt einen flachen `Source`-/`Sequence`-Plan des vorhandenen IR in eine
   begrenzte Pipeline. Die primitiven Pfade bleiben gegenüber dem Interpreter
   identisch; wiederholte Traversierungen der IR-Knoten entfallen. Branches,
   Guards, verschachtelte Programme und Theoriequellen bleiben beim allgemeinen
   Interpreter. Ein überschrittenes Kandidatenlimit wird explizit abgewiesen.
4. **Eine Fortsetzung bewusst auswählen.** Liefert eine akzeptierte Route
   vollständige Pfade, wird daraus ein Nachfolger gewählt. Sonst bleibt die
   Auswahl aus den vorhandenen Einzelregeln verfügbar. Das ist eine heuristische
   Einschränkung des Suchraums. Die Qualität auf beliebigen neuen Eingaben ist
   dadurch nicht garantiert.
5. **Restbudget vor der Auswahl beachten.** Die neue primitive Engineschnittstelle
   erhält die verbleibende Pfadtiefe. Ein Programm mit drei primitiven Schritten
   verdrängt bei nur einem verbleibenden Schritt keinen zulässigen Einzelzug.
   Suchen und exakte Prüfungen behalten sämtliche inneren Schritte.

Für wiederholte Anwendung wird das Modell einmal vorbereitet:

```java
var learner = new TraceStrategyDispatchLearner();
var policy = learner.train(formation, selectionInputs, limits);
var prepared = learner.prepare(policy,
    TraceStrategyDispatchLearner.Profile.LEARNED_DISPATCH);
var result = prepared.apply(new TraceRewriteStrategyLearner.Input(
    "new-input", "((x+y)*(x-y)+y*y)+101"));
```

`prepared` darf wiederverwendet werden. Es lernt nicht aus späteren Anwendungen
und verändert das eingefrorene Modell nicht. TRAIN-Polynome bleiben auch nach
Umformung oder Variablenumbenennung für Anwendung gesperrt. Jeder ausgewählte
primitive Schritt wird erneut exakt geprüft.

## Vergleich mit starken Kontrollen

| Profil | Bedeutung | Gezählt: Suchmechanik + exakte Prüfaufrufe |
| --- | --- | ---: |
| `FLAT_EXHAUSTIVE` | Bestehende flache Best-First-Suche unter den festen Budgets | 11.360 |
| `FLAT_GREEDY` | Jeweils bester primitiver Nachfolger, gleiche Bewertung und Budgets | 8.960 |
| `LEARNED_DISPATCH` | Gelernte Kontextauswahl, wiederverwendetes Präfix, kompilierte Fortsetzung | 8.832 |
| `UNGATED_CONTINUATIONS` | Dieselbe Fortsetzungstechnik ohne gelernte Kontextauswahl | 9.248 |

Alle Profile verwenden dasselbe feste Polynomregelinventar, keinen Zielausdruck
und dieselben Budgets. Greedy, gelernte Auswahl und ungesteuerte Fortsetzung
wählen jeweils einen Nachfolger. Die zugelassenen Kandidaten unterscheiden sich
durch die jeweilige Strategie. Die exhaustive Kontrolle bleibt sichtbar, damit
ein Gewinn durch reine Einschränkung der Suche nicht als Lerngewinn ausgegeben
wird.

Es gibt auf diesem Bestand keine Scoreverschlechterung. Der größte Anteil der
Reduktion gegenüber der flachen Suche stammt bereits aus Greedy. Der zusätzliche
Nutzen des Lernansatzes beträgt insgesamt 128 Einheiten beziehungsweise 1,4 %
gegenüber dieser stärkeren Kontrolle. In den vier Gruppen mit Offset, Skalierung,
äußerem Produkt und zusammengesetzter Basis sinkt der Aufwand jeweils von 39 auf
34 Einheiten pro Aufgabe (12,8 %).

Die Grenzen sind ebenfalls messbar: Bei passenden Regel-IDs mit falscher
Variablenbindung steigt der Aufwand von 28 auf 40 Einheiten pro Aufgabe. Ein
Beispiel ist `(x+y)*(x-y)+z*z+5000`: Differenzprodukt und Quadratregel sind
verfügbar, erlauben aber nicht die gelernte Kürzung. Die Fortsetzung scheitert,
der Rückfall bleibt korrekt, die erfolglose Arbeit bleibt gezählt. Auch weitere
Negative und bereits einfache Ausdrücke sind enthalten.

## Kosten und Aussagegrenze

Die Formierung kostet 316 Einheiten, davon 84 für die zusätzliche
Minimalitätsprüfung. Die Auswahl einschließlich verworfener Versuche kostet
weitere 850. Damit stehen 1.166 einmalige Lernarbeitseinheiten 128
gesparten Anwendungseinheiten pro 288er-Serie gegenüber. **Lernen plus Anwendung
kostet hier 9.998 gegenüber 8.960 Einheiten der Greedy-Kontrolle.** Bei exakt
gleichem Aufgabenmix wären rechnerisch zehn solche Serien nötig, um die gezählten
Lernkosten einzuspielen. Das ist eine Extrapolation, kein ausgeführter Nachweis
über 2.880 neue Aufgaben. Die ursprüngliche Version 1 ohne diese Prüfung kostete
1.082 Lerneinheiten; ihre Anwendungswerte sind unverändert geblieben.

Die Bilanz enthält die vorhandenen mechanischen Suchereignisse, Kontextprüfungen,
Sortierung, verworfene Kandidaten und exakte Prüfaufrufe. Sie ist keine Laufzeit-
oder vollständige CPU-Bilanz; vollständige Parser-, Identitäts-, Compiler- und
BigInteger-Kosten sind weiterhin gesondert zu qualifizieren.

Die 288 Aufgaben sind neue Zusammensetzungen und Koeffizienten bekannter
algebraischer Bausteine. Sie sind öffentliche Entwicklungsdaten und keine
unabhängig zurückgehaltenen Mathematikfamilien oder der versiegelte FINAL TEST.
Der nächste größere Forschungsgewinn erfordert insbesondere bessere gelernte
Bindungsbedingungen und eine umfassendere Kostenbilanz. Der hier gelieferte
Baustein macht diesen verbleibenden Engpass sichtbar, statt jedes wiederverwendete
Makro automatisch als Erfolg zu zählen. #874, #235 und #533 bleiben damit breiter
als dieser Entwicklungsnachweis.

## Welche Bewertung trägt eine gelernte Brücke?

Bewertungen existieren bereits an mehreren Stellen: `InterestingnessScore`
ordnet Mining-Hypothesen nach Kompression, Wiederverwendbarkeit und weiteren
Eigenschaften. `SearchPolicyModel.RuleStatistics` speichert beobachtete Erfolge,
Fehlalternativen und mittlere Scoreänderungen; die empirische Suchpolicy kann
damit anwendbare Regeln priorisieren. Die vorliegende Fortsetzungsauswahl
speichert dagegen vollständige TRAIN-Versuche und eine akzeptierte Route pro
Regelkontext. Sie übernimmt diese anderen Bewertungen noch nicht als laufenden
Prioritätswert.

Ein weiterführender Nutzenwert sollte sowohl die ersparte Suche ohne die Brücke
als auch die danach erreichbaren nützlichen Darstellungen berücksichtigen. Die
Länge einer bekannten Herleitung ist zunächst eine obere Schranke für die
kürzeste primitive Verbindung. Seit Version 2 muss die Regelbildung diesen
Abstand prüfen: Sie kürzt Umwege auf die bewiesene kürzeste Folge und verwirft
ungeklärte Fälle sowie Verbindungen mit null oder einem Schritt. Der
bisherige Mining-`CompressionScore` schätzt die Pfadlänge zudem aus Pfadkennungen;
das ersetzt keine gemessene Suche mit und ohne die Regel.

Für die Priorisierung wäre deshalb ein nach Richtung und Anwendungskontext
getrennter Erwartungswert sinnvoll: beobachteter späterer Nutzen und ersparte
Sucharbeit, abzüglich Matching- und Ausführungskosten, mit ausgewiesener
Evidenzmenge. Eine längere oder zunächst kompliziertere Darstellung kann dabei
wertvoll sein, wenn sie beispielsweise Faktorisierung oder einen anderen
Lösungsweg ermöglicht. Diese fortlaufende Brückenbewertung ist durch den
vorliegenden Vergleich noch nicht implementiert oder qualifiziert.
