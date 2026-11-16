# Gewichtete projektive Übergaben

Ein affiner Vektor `(A₁/D₁,…,Aₙ/Dₙ)` lässt sich durch
`(N₁,…,Nₙ,Z)` mit positiven Gewichten `wᵢ` darstellen. Bei der affinen
Ausgabe wird jede Koordinate als `Nᵢ·(Z⁻¹)^wᵢ` berechnet. Eine Inversion
liefert die Basis für alle benötigten inversen Potenzen. Eine Übernahme des
projektiven Vektors benötigt keine Inversion.

## Suche und Nachweis

`ProjectiveHandoff` verarbeitet ganzzahlige Polynome und explizite
Quotienten ganzzahliger Polynome über gebundenen Symbolidentitäten. Die Suche
probiert vorgegebene Basen und Teilbäume aus sichtbaren Produkten und Potenzen
der Nenner. Einen ausgeschriebenen Nenner kann der Prüfer mit einer Potenz
einer gefundenen Basis vergleichen. Bei ausgeschriebenen Nennern ohne
sichtbare Basis kann der Aufrufer eine Basis vorschlagen.

Für jeden Nenner weist die Normalform `Dᵢ = ±Z^kᵢ` mit `kᵢ ≥ 0` nach.
Mindestens ein `kᵢ` ist positiv. Die gemeinsame Bedingung für alle
ursprünglichen Divisionen lautet damit, dass `Z` eine Einheit ist. Die
Identitätsprüfung vergleicht anschließend `Nᵢ·Z^kᵢ` und
`±Aᵢ·Z^wᵢ`, wobei sie die bereits geprüften gemeinsamen Einheitenpotenzen
kürzt. Der Vergleich über ganzzahligen Koeffizienten gilt für jedes
kommutative Ringsystem, in dem der Aufrufer die Ausdrücke auswertet. Die
Quell- und Zielausdrücke verwenden dieselben Symbolidentitäten.

Das Modell unterstützt bis zu 64 Koordinaten, Gewichte 1 bis 12 und
Polynome bis Gesamtgrad 32. Die gemeinsame Arbeitsmessung begrenzt auch
Termzahl, Koeffizientengröße, Laufzeit und Ausdrucksgröße. Suche und
Nachprüfung liefern bei erschöpftem Budget oder Abbruch keinen
geprüften Plan. Quellterme außerhalb der Modellform können unverändert
ausgewertet werden.

## Verwendung und Kosten

Der Aufrufer wertet Basis und Zähler aus und bindet sie mit `Verified.carry`.
Im Körper genügt eine Prüfung auf einen von null verschiedenen Wert. In
einem allgemeinen Ring prüft das Prädikat, ob die Basis invertierbar ist.
Liefert `carry` einen leeren Wert, verwendet der Aufrufer den ursprünglichen
Fehler- oder Singularitätspfad. Die Übergabe eines getragenen Zustands setzt
einen Aufrufer voraus, der den Folgeschritt für dieselbe projektive
Darstellung implementiert hat.

Im Vektormodell wird `Z=z+1` aus drei rationalen Koordinaten mit Gewichten
1, 2 und 4 erkannt. Eine Koordinate schreibt `Z²` als `z²+2z+1` aus.
Für `Z=3` und Zähler `(6,4,10)` liefert die Ausgabe
`(2,4/9,10/81)`. Die unabhängige Baseline teilt jede Koordinate durch ihre
Nennerpotenz. Der Test zählt eine Inversion bei der gemeinsamen Ausgabe.
Ein zweiter Test übernimmt die drei Zähler und die Basis in einen
Folgeschritt, multipliziert die Koordinaten mit verschiedenen Faktoren und
vergleicht die affine Endausgabe mit der direkten Rechnung.

Das Kostenmodell vergleicht die vom Aufrufer angegebene Zahl der
Inversionen der affinen Baseline mit einer Inversion je affiner
Ausgabegrenze. Es zählt die zusätzlichen Multiplikationen für Vorbereitung
und Übergabe, die nach jeder Einheitenprüfung verbleibenden
Ausgabemultiplikationen und je eine Einheitenprüfung pro geprüftem Zustand.
Im Test benutzt die Baseline einer Kette zwei Grenzen mit insgesamt fünf
Inversionen. Ein projektiver Zwischenschritt und eine affine Ausgabe
benötigen eine Inversion, 13 zusätzliche Multiplikationen und zwei
Einheitenprüfungen. Bei den relativen Kosten 12, 1 und 1 ergibt das eine
Ersparnis von 33 Kosteneinheiten, bei 2, 1 und 1 einen Mehraufwand von 7
Kosteneinheiten. Die Rechnung modelliert den regulären Pfad. Kosten einer
Alternative für Singularitäten sowie die Kosten des eigentlichen
Folgeschritts stammen aus dem jeweiligen Aufrufer.

Mit JDK 25 reproduzieren

```bash
mvn -pl regelsuche-math-algorithms -am -Dtest=ProjectiveHandoffTest -Dsurefire.failIfNoSpecifiedTests=false test
```
