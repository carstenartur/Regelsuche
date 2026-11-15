# Wie viel Arbeit ein vorgegebener Zustand sparen kann

## Festgeschriebenes Aufgabenpanel

Das [Protokoll](../../config/benchmarks/state-choice-oracle-v1.json) lag
vor der ersten Messung im Repository. Es enthält die Quellen,
Eingaben, Arbeitsbudgets, Hinweise und die Schwelle von 20 Prozent
bei gleicher Ergebnisqualität. Die beiden Familien werden getrennt
bewertet. [Commit der Festschreibung](https://github.com/carstenartur/Regelsuche/commit/4f46544476add79d21681aa74b1afd8186f3b6a9)

Die Java-Aufgaben sind der exakte ganzzahlige Zähler der Varianz, das
dritte elementarsymmetrische Polynom und die Summe der Paarprodukte
modulo (10^9+7). Punktänderungen sind ein Versuchsaufbau über diesen
Funktionen. Der Vergleich untersucht Regelsuches Pläne. Die
Originalbibliotheken liefern die mathematischen Aufträge. [Commons Statistics](https://github.com/apache/commons-statistics/blob/2937eb2e711483d8ea9dc216af45c16fd0066b77/commons-statistics-descriptive/src/main/java/org/apache/commons/statistics/descriptive/IntVariance.java#L244-L263) · [Symja](https://github.com/axkr/symja_android_library/blob/05bebd3b169fbf138d5b903fa379548168095596/symja_android_library/matheclipse-core/src/main/java/org/matheclipse/core/reflection/system/SymmetricPolynomial.java#L26-L60) · [ABC177C](https://github.com/haruki57/atcoder-java/blob/8a3614314e4db69ffe623506e9e973c799b87238/src/main/java/ABC177C.java#L11-L26)

Die Inversionszählung wurde mit Wertfilter eingegeben. Der
Punktänderungspfad hat sie in beiden Varianten abgewiesen. ThreeSum
steht im Protokoll als Modellgrenze. Seine Bedingung über drei Werte
lässt sich im Format für benachbarte Schlüsselintervalle nicht
ausdrücken. Für diese Aufgabe wurde kein Suchlauf ausgeführt.
[Inversions](https://github.com/kevin-wayne/algs4/blob/7bb62c32f03fec900f0cc796a548ab992feffe4e/src/main/java/edu/princeton/cs/algs4/Inversions.java#L43-L83) · [ThreeSum](https://github.com/kevin-wayne/algs4/blob/7bb62c32f03fec900f0cc796a548ab992feffe4e/src/main/java/edu/princeton/cs/algs4/ThreeSum.java#L68-L88)

Die Rekurrenzfamilie umfasst alle sieben Invariantenaufgaben in ihrer
ursprünglichen Reihenfolge aus dem [publizierten historischen Korpus](https://github.com/carstenartur/Regelsuche/blob/4fa61819b871f7b6be5fcf15f9467dfdbda07ebf/config/benchmarks/historical-sympy-v1.json).
Fünf gehören zu einer parametrisierten Klasse, eine ist eine affine
Rotation und eine ergibt im geprüften Ansatzraum kein Gesetz. Die
Aufgaben sind öffentliche Entwicklungsdaten.

## Was jede Variante bezahlt

Für die Java-Reduktionen ersetzt die vorgegebene Liste von
Blockgrößen nur deren Ermittlung. Die Varianten normalisieren
dieselbe Formel, leiten die Koeffizienten daraus ab, verwenden den
gleichen exakten Schnittprüfer und betreiben denselben Baum. Für
jede der drei Reduktionen gibt es einen Lauf mit acht und einen mit
32 Zeilen. Pro Lauf werden Aufbau, eine erste Abfrage und 16
Punktänderungen verrechnet. Jeder Wert wurde durch Potenzsummen oder
eine unabhängige Koeffizientenrekurrenz kontrolliert. Der
Modulo-Grenzfall des zweiten offiziellen ABC177C-Beispiels hat
einen zusätzlichen Test außerhalb der Messung.

Das Rekurrenzziel ist ein beliebiges nichtkonstantes Gesetz des
vorgegebenen maximalen Grades. Beide Varianten stoppen nach dem
ersten gültigen Gesetz auf ihrem Suchpfad. Der Hinweis enthält
lediglich die Monome. Beide Varianten bestimmen die Koeffizienten
per exakter linearer Algebra, prüfen die Identität und den
Anfangswert und beantworten dieselben sechs Abfragen zum Gesetz.
Pro Abfrage zählt eine Einheit. Eine getrennte exakte
Trajektorienrechnung dient als Ergebniskontrolle.

Die Tabellen unterscheiden bereits verfügbare Hinweise von einer
bezahlt angenommenen Referenz. Für die zweite Variante werden das
bekannte Referenzgesetz geprüft und seine Monome exakt extrahiert.
Diese Arbeit wird vom gleichen Budget abgezogen, ehe die Suche
startet. Der Versuch setzt das Referenzgesetz voraus. Die Arbeit
zur Entdeckung eines solchen Gesetzes oder zum Lernen einer
Auswahlstrategie ist eine eigene Messgröße.

## Ergebnisse

| Aufgaben und Vergleich | Direkt | Vorgegeben | Vorgegeben mit Eingangsprüfung | Unterschied bei bereits vorliegendem Hinweis |
| --- | ---: | ---: | ---: | ---: |
| Java-Reduktionen | 7.412 | 7.388 | — | −0,32 % |
| Rekurrenzen mit gemeinsamem vollständigem Ansatzraum | 25.930 | 16.972 | 20.678 | −34,55 % |
| Rekurrenzen mit aus dem Übergang abgeleiteter Gradaufteilung | 13.968 | 13.372 | 17.078 | −4,27 % |

Jede Zahl steht für verrechnete Arbeitseinheiten in der jeweiligen
Familie. Bei den Java-Reduktionen bleiben die 474 Einheiten für
Beweise, 2.382 für den Aufbau und 4.200 für die Abfragen gleich.
Die 24 eingesparten Einheiten stammen aus der Ermittlung der
Blockgrößen. Beide Varianten liefern dieselben 102 Werte.
[Arbeitsbelege der Java-Läufe](state-choice-oracle-v1/ordered-reductions.tsv)

Bei den Rekurrenzen finden alle Varianten dieselben sechs Gesetze
und liefern dieselben 36 Werte. Der ursprüngliche Vergleich
erreicht mit Eingangsprüfung und Extraktion eine Ersparnis von
20,26 Prozent. [Arbeitsbelege des ersten Vergleichs](state-choice-oracle-v1/recurrences.tsv)

Der stärkere Vergleich kam nach dem ersten Messlauf hinzu. Bei
einer linearen Zustandsänderung ohne Verschiebung bleiben Monome
eines Grades nach der Substitution in diesem Grad. Die direkte
Variante kann deshalb jeden nichtkonstanten Grad separat lösen und
beginnt beim höchsten erlaubten Grad. Der Gradwechsel, die
Koeffizientensuche und die vollständige Identitätsprüfung bleiben
verrechnet. Bei affinen Verschiebungen wird der vollständige
Ansatzraum verwendet. Dieser Vergleich wurde mit denselben
Eingaben und in beiden Hinweismodi ausgeführt.
[Arbeitsbelege des stärkeren Vergleichs](state-choice-oracle-v1/recurrences-degree-control.tsv)

Die Gradaufteilung spart bereits **46,13 Prozent** an direkter
Arbeit. Bei gemeinsamem Einsatz der Gradaufteilung bringt der
vorgegebene Hinweis 4,27 Prozent Ersparnis. Werden seine
Eingangsprüfung und Extraktion bezahlt, kostet er **22,27 Prozent
mehr**. Die vorab festgelegte Schwelle wird gegen den stärkeren
direkten Vergleich in keiner Familie erreicht.

## Folgerung

Die Blockgrößen der gemessenen Java-Summen bieten wenig Hebel für
Lernen. Bei homogenen Rekurrenzen ist die Aufteilung nach Grad
die unmittelbar nutzbare Verbesserung. Für einen weiteren
Lernversuch eignen sich unabhängige Aufgaben mit gekoppelten
Zustandsgrößen, bei denen die direkte Suche nach strukturellen
Vereinfachungen noch einen nennenswerten Rest übrig lässt. Die
einzelne affine Rotation kann dieses nächste Panel informieren.
Die Messung bewertet logische Arbeit und liefert keine
Laufzeitmessung.

## Reproduktion

Aus dem Checkout mit Java 25 ausführen. Die Tests erzeugen die
Arbeitsbelege erneut und vergleichen sie bytegenau mit den
versionierten Dateien.

```bash
mvn -B -pl regelsuche-math-algorithms -am \
  -Dtest=OrderedStateChoiceBoundaryTest,OrderedStateChoiceExperimentTest,RecurrenceStateChoiceBoundaryTest,RecurrenceStateChoiceExperimentTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Für die Ausgabe der drei Dateien in ein separates Verzeichnis
`-DstateChoiceOracleOutput=/tmp/state-choice-oracle` hinzufügen.
