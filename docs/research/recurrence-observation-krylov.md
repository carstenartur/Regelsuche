# Exakte Krylov-Rekurrenz für eine Beobachtung

Für einen affinen Übergang $T(x)=Ax+b$ über $\mathbb Q$ und ein
vorgegebenes Polynom $P$ bis Gesamtgrad zwei bestimmt der Java-Kern
die kleinste Ordnung $r$ einer gemeinsamen linearen Rekurrenz für
$a_n=P(T^n(x))$ mit beliebigem rationalem Anfangszustand $x$.
Der Übergang kann singulär sein und bis zu drei Koordinaten haben.
Die Ordnung gehört zur Beobachtung und zum Übergang. Ein
bestimmter Anfangszustand kann eine kürzere Folge erzeugen, etwa
die Nullfolge auf der Niveaumenge $P=0$ eines alternierenden Gesetzes.

## Herleitung, Prüfung und Abfrage

Komposition mit $T$ ist eine lineare Abbildung auf dem Raum der
Polynome bis zum Grad von $P$. Der Kern bildet die Koeffizientenvektoren
von $P,P\circ T,P\circ T^2,\ldots$ mit den typisierten Monombildern
des Übergangs. Nach jeder neuen Spalte bestimmt die vorhandene exakte
Zeilenreduktion den Nullraum und prüft ihre Zeilenoperationen durch
Wiederausführung. Bei der ersten Abhängigkeit waren alle früheren
Spalten unabhängig. Die Beziehung

$$P\circ T^r=\sum_{i=0}^{r-1}c_i(P\circ T^i)$$

hat damit universell minimale Ordnung. Für das Nullpolynom ist
$r=0$, für eine von null verschiedene Konstante $r=1$ mit $c_0=1$.
Mit drei Koordinaten gibt es höchstens zehn Basismonome.

Ein zweiter Rechenweg wertet beide Seiten entlang exakter rationaler
Trajektorien ab $0,e_i,2e_i,e_i+e_j$ für $i<j$ aus. Der Übergang wird
dabei direkt auf den Zustand angewandt. Der Wert bei $0$ fixiert den
konstanten Term. Je zwei Werte pro Achse bestimmen deren lineare und
quadratische Terme. Die übrigen Werte bestimmen die gemischten Terme.
Bei Grad eins genügen $0,e_i$, bei Grad null der Ursprung. Stimmen
beide Seiten an diesen Punkten überein, gilt die Identität als
Polynom. Komposition mit $T^n$ gibt die Rekurrenz für jeden Index.

`RecurrenceObservationKrylov.prepare(transition, polynomial, budget)`
liefert einen geprüften, unveränderlichen Plan mit Ordnung und
Koeffizienten in aufsteigender Indexreihenfolge. `plan.bind(initialState,
budget)` berechnet die ersten $r$ Werte. Derselbe Plan lässt sich
für weitere Anfangszustände binden. `valueAt(index, budget)` und
`valuesAt(indices, budget)` akzeptieren nichtnegative `BigInteger`-Indizes.
Sie berechnen $z^n \bmod (z^r-\sum_i c_i z^i)$ mit binärer Potenzierung
und kombinieren dessen Koeffizienten mit den gebundenen Werten.
Mit gewöhnlicher Polynommultiplikation kostet eine Einzelabfrage
$O(r^2\log n)$ arithmetische Schritte. Bei Ordnung eins mit
Koeffizient $0$, $1$ oder $-1$ genügen eine Fallunterscheidung und
gegebenenfalls die Parität des Index. Eine Stapelabfrage speichert
bis zu 128 binäre Potenzen innerhalb des Aufrufs.
[Fiduccia, An Efficient Formula for Linear Recurrences](https://doi.org/10.1137/0214007)

Jede Phase hat ein Arbeitsbudget und meldet den Verbrauch. Für eine
gemeinsame Obergrenze erhält die nächste Phase jeweils den Rest des
vorigen Aufrufs. Bei `BUDGET_INCONCLUSIVE` oder `ARITHMETIC_LIMIT`
enthält der Bericht keinen Teilwert. Für rationale Koeffizienten
und Zustandswerte gilt die Grenze des Übergangs von 128 gemäß
`BigInteger.bitLength()`. Die Rechnung begrenzt Beträge normierter Zähler und
Nenner auf 16.384 Bit. Vor der Zeilenreduktion werden die Nenner
zeilenweise beseitigt und die Größen der ganzzahligen Minoren
konservativ über die Zeilennormen abgeschätzt. Die Grenze kann daher
auch bei kleinen Endwerten erreicht werden.

## Arbeitsvergleich

Der direkte Vergleich verwendet binäre Potenzen des affinen
Koordinatenübergangs. Er bestimmt einmal die für $P$ nötigen
Koordinaten samt ihren transitiven Abhängigkeiten. Beim Quadrieren
verwendet er $A^2$ und $Ab+b$, berücksichtigt Nullkoeffizienten
und überspringt Identitätsabbildungen. Die binären Potenzen werden
im Stapel wiederverwendet. Für jede Abfrage wendet er die benötigten
Potenzen auf denselben gebundenen Anfangszustand an und wertet $P$ aus.
Beide Verfahren erhalten je eine Million Arbeitseinheiten für
Vorbereitung, Bindung und alle Abfragen eines Laufs.

Alle Aufgaben verwenden den Anfangszustand $(2,-3,4)$. Die
Rotationsspiegelung und die Drehung mit axialer Drift übernehmen
die rationalen Übergänge aus dem
[Panel für gekoppelte Rekurrenzen](../../config/benchmarks/coupled-recurrence-v1.json).
Mit $L=x_0+2x_1+3x_2$ wird bei der Rotationsspiegelung $L$ beobachtet
und bei der Drift $L^2+L/2$. Die beiden Kontrollen beobachten
$x_0^2+x_0+1$ bei einer Verschiebung von $x_0$ sowie die Summe aller
zehn Monome für die Diagonalmatrix mit Einträgen $(2,3,5)$. Der
Kaltstart fragt den Index 159 ab. Der Stapel nutzt 24 verschiedene
Indizes $128+((73i+31)\bmod384)$ für $0\le i<24$.

| Aufgabe | Ordnung | Krylov, 1 Abfrage | Direkt, 1 Abfrage | Krylov, 24 Abfragen | Direkt, 24 Abfragen |
| --- | ---: | ---: | ---: | ---: | ---: |
| Rotationsspiegelung, Achse | 1 | 478 | 1.053 | 524 | 4.919 |
| Axiale Drift, quadratische Beobachtung | 3 | 6.579 | 1.231 | 11.710 | 5.666 |
| Verschiebung einer beobachteten Koordinate | 3 | 2.749 | 149 | 7.880 | 1.052 |
| Zehn verschiedene Moden | 10 | 31.381 | 543 | 69.606 | 3.615 |

Beide Verfahren lieferten in allen acht Läufen dieselben exakten
Werte. Bei der Rotationsspiegelung mit Ordnung eins spart Krylov
54,6 Prozent beim Kaltstart und 89,3 Prozent im Stapel. Bei allen
drei übrigen Aufgaben kostet die direkte Rechnung weniger. Auch
der Stapel mit axialer Drift amortisiert die Vorbereitung in diesem
Vergleich nicht.

Die Tabelle enthält algorithmisch verrechnete Arbeit einschließlich
Koeffizientenbildung, Zeilenreduktion, unabhängiger Prüfung und Bindung.
Die Zähler erfassen durchlaufene Einträge und elementare Rechenschritte,
beispielsweise je eine Einheit für Prüfung, Multiplikation und Addition
eines von null verschiedenen Produkts. Die eigene Arbeitszählung der
exakten Zeilenreduktion fließt ein. Die Zähler gewichten die
Arithmetik unabhängig von der Bitlänge.
Beide Verfahren erhalten dieselben schon exakt konstruierten Eingaben.
[Einzelkosten und Wertprüfsummen](recurrence-observation-krylov/work.tsv)

## Reproduktion

Mit Java 25 vom Repository-Wurzelverzeichnis aus aufrufen.
Der Vergleichstest prüft die Gleichheit der Werte und reproduziert
den versionierten Arbeitsbeleg. Mit
`-DkrylovObservationOutput=/absoluter/pfad/work.tsv` schreibt er
den Beleg neu.

```bash
mvn -B -pl regelsuche-math-algorithms -am \
  -Dtest=RecurrenceObservationKrylovTest,RecurrenceObservationComparisonTest test
```
