# Blockzustände für Punktänderungen an geordneten Summen

## Herleitung und Prüfung

Die Eingabe ist eine geordnete Summe mit zwei bis acht Zeilenbindungen. Jede
Bindung verweist auf dieselben Spalten. Der Summand ist ein ganzzahliges
Polynom mit Gesamtgrad bis acht. Ein Update ersetzt eine Zeile und behält
Länge und Reihenfolge der Folge bei.

Die Suche zerlegt den Summanden mit der vorhandenen exakten Faktorisierung
in Zeilenmonome. Für ein Produkt mit Faktoren $f_1,\ldots,f_k$ besteht der
Beitrag über die Grenze zweier aufeinanderfolgender Blöcke aus $k-1$
Produkten. Beim Schnitt nach Faktor $r$ gehört $f_1,\ldots,f_r$ zum
linken Block und $f_{r+1},\ldots,f_k$ zum rechten. Jeder streng aufsteigende
Indexvektor, der beide Blöcke berührt, hat genau einen solchen Schnitt.

Die Synthese speichert für die benötigten echten zusammenhängenden
Faktorfolgen jeweils ihre geordnete Teilsumme. Die gleichen Schnittregeln
berechnen diese Größen beim Zusammenfügen kürzerer Blöcke. Blöcke mit
weniger Zeilen als Faktoren tragen null bei. Der Prüfer rekonstruiert den
Summanden aus den vorgeschlagenen Faktoren und kontrolliert, dass alle
Operanden jedes Schnitts vorliegen. Die Ausführung verwendet die daraus
erzeugte unveränderliche Zusammenführungsregel. Die Zahl der abgeleiteten
Größen ist eine obere Schranke für den benötigten Zustand dieser Darstellung.

Für `(p1-p0)*(x0-x1)^2` mit festen Positionen `p[i] = i` ergibt das je
Block die Anzahl der Zeilen sowie die Summen für `p`, `x`, `p*x`, `x^2`
und `p*x^2`, zusätzlich zur Paarsumme im Block. Diese Größen ergeben sich
aus der Eingabe und der Zerlegung. Dreieraufgaben benötigen auch geordnete
Zweiertupel als Blockgrößen, die beim Zusammenfügen Kreuzprodukte erzeugen.

Ein ausgeglichener Segmentbaum speichert die Zustände. Bei festem Plan
braucht der Aufbau linear viele Blockkombinationen und ein Update
logarithmisch viele. Ein Update berechnet erst seinen gesamten neuen Pfad
und übernimmt ihn anschließend. Das erhält den alten Zustand bei
erschöpftem Budget, Abbruch und Eingabefehlern. Die Ausführung begrenzt
den Baum auf acht Millionen Zustandszellen und jede Ganzzahl auf 65.536
Bits. Schlüsselintervalle in der Tupelauswahl werden bei
der Synthese für Punktänderungen abgelehnt. [Java-SDK](../../regelsuche-optimization-sdk/FINITE_SERIES.md#fixed-length-point-updates)

## Vergleich bei Punktänderungen

Der Versuch setzt `x[i] = i % 17 - 8` und `p[i] = i` für 100.000 Zeilen.
Er ersetzt acht Werte an den Positionen 0, 74, 99.999, 65.535, 28.791,
65.536, 0 und 99.999. Eine lineare Delta-Berechnung kontrolliert jeden
neuen Wert. Die vorhandene indizierte Auswertung kontrolliert Anfangs-
und Endwert.

| Messgröße | Ergebnis |
| --- | ---: |
| Berechnete Blockkombinationen je Änderung | 17 |
| Gelesene Nachbarzeilen je Änderung im linearen Delta | 99.999 |
| Verrechnete Arbeitseinheiten für den Baumaufbau | 6.811.783 |
| Verrechnete Arbeitseinheiten für acht Baum-Updates | 3.536 |
| Verrechnete Arbeitseinheiten je vollständiger indizierter Auswertung | 4.400.019 |

Der lineare Delta-Vergleich zählt Zeilenzugriffe. Die Arbeitseinheiten
des Baums und der indizierten Auswertung verwenden ihre jeweiligen
Zähler. Der Versuch misst damit weder einen gemeinsamen
Arbeitseinheitenwert für beide Update-Verfahren noch JVM-Laufzeit.

## Wiederverwendung von einer Paar- in eine Dreieraufgabe

Die Lernoperation nimmt einen geprüften Plan für die gewichtete Paarsumme
entgegen. Sie wählt den ersten zusammengesetzten Potenzausdruck im
Summanden und normalisiert ihn exakt. Die Suche kann den Beleg anschließend
bei einer konsistenten, injektiven Umbenennung der Variablen für die
Synthese einsetzen. Beide Produktionsprüfungen normalisieren weiterhin
die Originalformel. Andere Exponenten und Zuordnungen werden über den ursprünglichen
Normalisierer behandelt. Der Beleg bleibt im Speicher und ist an die
exakte Struktur des Teilbaums gebunden.

Die 14 Dreieraufgaben werden erst der Auswertung übergeben. Zehn verwenden
passende Potenzausdrücke, drei sind Kontrollen ohne verwendbaren Beleg und
eine enthält eine unzulässige Division. Jeder Lauf erhält dieselbe
Suchgrenze. Die Vergleichsarme zählen beide die Herleitung des
Trainingsplans. Der Arm mit Wiederverwendung bezahlt zusätzlich die
Auswahl und Prüfung des Belegs sowie Versuche, ihn anzuwenden. Jede
erfolgreiche Ausgabe wird als externe Kontrolle noch einmal ohne
Wissensübergabe geprüft. Eine direkte Dreifachschleife kontrolliert die
Werte vor und nach der Änderung für beide Arme. Diese externen Kontrollen
liegen außerhalb der gemessenen Suchläufe.

| Messgröße | Suche ohne Beleg | Suche mit gelerntem Beleg |
| --- | ---: | ---: |
| Gemeinsame Herleitung des Trainingsplans | 396 | 396 |
| Auswahl und Prüfung des Belegs | 0 | 39 |
| Suche und Prüfung für 14 Dreieraufgaben | 7.584 | 7.468 |
| Gesamte gezählte Arbeit | 7.980 | 7.903 |
| Geprüfte Lösungen | 13/14 | 13/14 |

Die Ersparnis beträgt 77 Arbeitseinheiten oder knapp ein Prozent. Sie entsteht
bei der Normalisierung einer algebraischen Teilformel. Die Suche leitet
die Blockgrößen und die Zusammenführungsregel für Dreiertupel weiterhin
für jede Aufgabe her. Die Aufgaben sind für diesen Entwicklungsvergleich
konstruiert. Aussagen über unabhängige Aufgabenkorpora, Laufzeit und
Wiederverwendung der Blockzustandsstrategie erfordern eigene Messungen.

## Reproduktion

Mit Java 25 aus dem Repository-Stamm ausführen.

```bash
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=OrderedBlockReductionTest,OrderedBlockReductionComparisonTest,OrderedReductionSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Die [Vergleichstests](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/OrderedBlockReductionComparisonTest.java)
halten Eingaben, Reihenfolge und Zählweise fest. Sie geben beide
Messzeilen aus.
