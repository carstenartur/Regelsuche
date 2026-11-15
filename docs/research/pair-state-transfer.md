# Geprüften Paarzustand in Dreiersummen wiederverwenden

## Abgeleiteter Zustand

Aus einem geprüften Plan für eine Paarsumme übernimmt Regelsuche das
ganzzahlige Koeffizientenmuster und die belegten Zeilenstatistiken. Ein
gemeinsamer Koeffizient wird ausgeklammert. Neue Formeln werden mit dem
vorhandenen exakten Normalisierer ausgewertet. Bei einer Dreiersumme
prüft die Suche, ob die ersten oder die letzten beiden Bindungen
dasselbe Muster bilden und der verbleibende Faktor allein von der
dritten Zeile abhängt.

Für den Fall `Q(i,j)*H(k)` trägt beim Zusammenführen zweier Blöcke der
Schnitt hinter `j` genau das Produkt der Paarsumme `Q` im linken
Block und der Zeilensumme `H` im rechten Block bei. Der andere Schnitt
verwendet die dafür hergeleiteten einzelnen Zeilen- und Paarstatistiken.
Für `H(i)*Q(j,k)` wird der Paarzustand entsprechend im rechten Block
benötigt. Damit ersetzt ein gemeinsamer Paarzustand mehrere
getrennte Paarstatistiken. Die Suche übernimmt die Gruppierung nur,
wenn mindestens zwei einzelne Paarstatistiken entfallen. Beide
Platzierungen behalten die Reihenfolge der Zeilen.

Die Verifikation rekonstruiert aus den Produktfaktoren sämtliche
Koeffizienten der ursprünglichen Dreiersumme und prüft die Operanden
jedes Schnitts. Ungültige externe Pläne erhalten keinen ausführbaren
Zustand. Die Ausführung nutzt denselben Segmentbaum wie die
[Blockzustände für Punktänderungen](ordered-block-reductions.md).

## Vergleich auf acht Dreieraufgaben

Die Lernoperation erhält nur den geprüften Plan für
`(p1-p0)*(x0-x1)^2`. Sechs Dreierformeln verwenden danach das
Paarmuster mit verschiedenen Gewichten vor oder hinter dem Paar.
Eine verschränkt gewichtete Dreierformel und die orientierte
Dreiecksfläche dienen als Kontrollen. Die Eingaben werden als
Polynomausdrücke und mit eigenen Bindungen übergeben. Zwei der
positiven Formeln verwenden das Quadrat in ausmultiplizierter Form.

| Gezählte Arbeit | Einzelne Paarstatistiken | Gruppierung direkt abgeleitet | Gruppierung mit geprüftem Paarzustand |
| --- | ---: | ---: | ---: |
| Training, Erwerb, Suche und Prüfung | 10.733 | 11.200 | 10.889 |
| Aufbau der acht Bäume mit je 257 Zeilen | 856.713 | 736.068 | 736.068 |
| Je sechs Punktänderungen | 63.978 | 53.040 | 53.040 |
| Summe | 931.424 | 800.308 | 799.997 |

Alle drei Varianten erzeugten für alle acht Aufgaben die gleichen
Ergebnisse vor und nach den Punktänderungen. Ein separater Test
kontrolliert den wiederverwendeten Zustand mit einer direkten
Dreifachschleife. Ein verfälschter Koeffizient im Gruppierungsplan
wird abgewiesen, auch wenn die beigefügte normale Faktorisierung
korrekt ist.

Alle Varianten zahlen die 396 Arbeitseinheiten für den ursprünglichen
Plan. Die Wiederverwendung zahlt zusätzlich 25 Einheiten für den
Wissenserwerb. Die Tabelle umfasst die Prüfung der vollständigen
Pläne, die Fehlversuche auf den beiden Kontrollen und die dort
anschließende direkte Herleitung.

Der Vergleich mit direkt hergeleiteter Gruppierung weist **311
Arbeitseinheiten weniger Planungsarbeit** aus. Das sind 2,8 Prozent.
Aufbau und Änderungen sind mit beiden Gruppierungen gleich teuer. Wird
der Paarplan eigens für das Lernen hergeleitet, benötigt die direkte
Variante ohne diese Zusatzaufgabe 10.804 Einheiten. In diesem Kaltstart
kostet der Lernweg insgesamt 85 Einheiten mehr.
Gegenüber der bisherigen Darstellung in einzelnen Statistiken spart
die Gruppierung 14,1 Prozent über den gesamten gemessenen Ablauf.
Die Planung allein bleibt gegenüber dieser bisherigen Darstellung
teurer. Der Versuch misst Arbeitseinheiten auf einem konstruierten
Entwicklungspanel. Er untersucht unbeschränkte geordnete Dreiersummen
mit einem separierbaren Paar am Rand. Ein unabhängiges Korpus und
andere Aufgabendomänen wurden in diesem Versuch nicht ausgewertet.

## Reproduktion

Mit Java 25 aus dem Repository-Stamm ausführen.

```bash
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=OrderedPairStateTransferTest,OrderedReductionSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Der [Vergleichstest](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/OrderedPairStateTransferTest.java)
definiert die acht Eingaben und gibt die vollständigen Arbeitssummen aus.
