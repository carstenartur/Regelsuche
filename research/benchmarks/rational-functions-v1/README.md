# Rationalfunktionen auf explizit erhaltener Originaldomäne

## Vertrag

`RationalFunctionSdk.simplify` projiziert die Originalquelle begrenzt in die
vorhandene exakte Polynomdarstellung. Exakte Division durch sichtbare
Nennerfaktoren und den Gesamtnenner kann Faktoren kürzen; konstante Nenner
werden normalisiert. Ein Vorschlag darf die expandierte arithmetische
Operationszahl nicht vergrößern. Der vorhandene Rationalfunktionsprüfer liest
Quelle, Ziel und Voraussetzungen danach unabhängig erneut ein. Ein Vorschlag,
dessen neue Nennerschreibweise nicht durch die Originalbedingungen gedeckt
wird, wird durch die Originalquelle ersetzt.

Das Ergebnis enthält Quelle, Kandidat, notwendige Nichtnullbedingungen, Status,
verbrauchte Arbeit und gegebenenfalls einen überprüfbaren Beleg. Ohne passende
Annahmen lautet der Status `REQUIRES_ASSUMPTIONS`; es wird kein gültiger Beleg
ausgestellt. Ein Beleg wird nur gegen die ausdrücklich angegebene erwartete
Originalquelle geprüft. Die gekürzte Stelle von `(x^2-1)/(x-1)` bleibt bei `x=1`
ausgeschlossen, obwohl das Ergebnis `x+1` dort einen Wert hätte.

Alle arithmetischen Operationen, erneutes Einlesen und Prüfungen benutzen das
gleiche Budget der jeweiligen SDK-Operation. Abbruch, Zeit-, Quellen-,
Koeffizienten-, Grad- und Zustandsgrenzen liefern keine mathematische Aussage.
Die bisherige Normalform-API bleibt bestehen. Ihre Nichtnullprüfung akzeptiert
nun auch rationale Polynomkoeffizienten: etwa `x != -1/3`. Beim Vergleichen der
beiden Seiten werden ihre konstanten Nenner exakt berücksichtigt.

Dies ist keine vollständige Faktorisierung oder multivariate GGT-Suche.
Unbekannte Funktionen, Verzweigungen und Schleifen bleiben ausgeschlossen.
Die Bedingungen werden konservativ normalisiert, ohne allgemeine logische
Folgerungen zwischen unterschiedlichen Polynomen zu behaupten.

## Vollständiges Fremdinventar

Die separate `readRational`-Projektion lässt variable Nenner zu. Die bisherige
`read`-Projektion sowie sämtliche Enumo-Protokolle, Budgets und Ergebnisdateien
bleiben unverändert. Beide Projektionen verwenden dieselbe begrenzte
FPCore-Leselogik. Alle Top-Level-Programme bleiben im Inventar:

| Bereits versionierte vollständige Datei | Programme | Alter Polynom-Ausschnitt | Neuer rationaler Ausschnitt |
| --- | ---: | ---: | ---: |
| Rosa | 37 | 6 | 15 |
| FPTaylor extra | 18 | 3 | 6 |
| Hamming Kapitel 3 | 28 | 0 | 2 |
| Gesamt | 83 | 9 | 23 |

Alle 23 angenommenen Fälle wurden mit expliziten Originalbedingungen erneut
geprüft. Alle 60 übrigen Zeilen behalten ihren Ablehnungsgrund. Die Dateipfade,
SHA-256, ordinalen Positionen, Namen, Quellen, Bedingungen und Ergebnisse
stehen in [results.json](results.json). Die FPCore-Gleitkommasemantik und
Metadaten-Präkonditionen werden damit **nicht** bewiesen: Gegenstand sind die
exakten rationalen Ausdrücke auf ihrer ausdrücklich beschriebenen Domäne.

## Feste Kontrolle und Befund

Die unabhängige Kontrolle mit SymPy 1.14.0 prüft die exakte Differenz jedes
nativen Kandidaten und dass sein Nenner auf der ursprünglichen Domäne keine
neue ausgeschlossene Stelle einführt. Dazu werden die quadratfreien
Nennerpolynome über den rationalen Zahlen verglichen. Alle 23 Kontrollen
bestanden.

Die feste Vergleichspolitik berechnet `cancel`, `factor` und
`factor(cancel(...))` und wählt gemeinsam mit der Originalquelle den kleinsten
gültigen Kandidaten. Potenzen werden in der Kostenmetrik als wiederholte
Multiplikationen gewertet. Alle Kandidatenbildung und Kontrollen sind in der
gemessenen Python-Phase enthalten.

| Arithmetische Operationssumme über dieselben 23 Quellen | Anzahl |
| --- | ---: |
| Originalquellen | 289 |
| Begrenzter Java-Vorschlag | 268 |
| Feste SymPy-Politik | 245 |

Der Java-Pfad verbessert vier Quellen gegenüber dem Eingang. Gegenüber der
festen Kontrolle gibt es zwölf Gleichstände und elf Fälle mit mehr Operationen;
keinen Fall mit weniger. Das ist eine Erweiterung der geprüften Fähigkeiten,
kein Beleg für eine Überlegenheit gegenüber der festen Algebra-Software.
[Vollständige feste Kontrolle](sympy-reference.json)

Die Java-Auswertung enthält Domänenerwerb, Vorschlag und einen weiteren
unabhängigen Replay in ihrer Gesamtabrechnung. Jeder dieser separat
ausgewiesenen Aufrufe hat 2.000.000 Arbeitseinheiten, 20.000 Zustandsplätze und
5.000 ms als Grenze. Das rohe Ergebnis trennt Phasen und enthält auch ihre
gemessene CPU- und Wandzeit. Diese einzelne lokale Entwicklungsauswertung
lief unter paralleler Arbeit, ohne JMH-Aufwärmung und ohne vergleichbare
Prozessstartmessung. Aus den Zeiten wird kein Laufzeitverhältnis abgeleitet.
Das Korpus war bereits bekannt; diese Auswertung ist kein neuer verblindeter
Transfertest und enthält keinen gelernten Arm.

## Reproduktion

Java 25 und die regulären Repository-Abhängigkeiten verwenden. Vom
Repository-Stamm zuerst die betroffenen Tests bauen:

```sh
./mvnw -B -ntp -pl regelsuche-experiments -am \
  -Dtest=FpcoreRationalCorpusTest,FpcorePolynomialCorpusTest,RationalFunctionPlannerTest,RationalFunctionNormalFormEquivalenceServiceTest,RationalFunctionNormalFormEquivalencePortAdapterTest,RationalFunctionSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`RationalFunctionsForeignComparison` mit den frisch erzeugten Reactor-Klassen
und den regulären Jackson-Laufzeitabhängigkeiten auf dem Klassenpfad starten:

```sh
java -cp "$REGELSUCHE_EXPERIMENT_CLASSPATH" \
  de.regelsuche.benchmark.peer.RationalFunctionsForeignComparison \
  . research/benchmarks/rational-functions-v1/results.json
python3 research/benchmarks/rational-functions-v1/sympy_reference.py \
  research/benchmarks/rational-functions-v1/results.json \
  research/benchmarks/rational-functions-v1/sympy-reference.json
```

Das Python-Programm verlangt ausdrücklich SymPy 1.14.0 und bindet seinen
vollständigen Eingang über SHA-256. Die gemessenen Zeitfelder ändern sich bei
einer Wiederholung; mathematische Entscheidungen und Operationszahlen sind
die vergleichbaren Größen.
