# Hunter-Schranke und endliche Beweis-DAGs

Im Fenster \(W=\{0,\dots,29\}\) lässt sich aus den Einzel- und Paarschnitten der Vielfachen von 2, 3 und 5 beweisen, dass mindestens sieben Zahlen frei bleiben. Allein die Einzelsummen ergeben null. Eine vollständige Zählung liefert acht. Die Konstruktion nutzt die klassische Baumungleichung von David Hunter für eine endliche Menge mit Zählmaß. [Hunter, *An upper bound for the probability of a union* (1976)](https://doi.org/10.2307/3212481)

## Endlicher Vertrag

Für \(0\le k<N\) bezeichnet \(A_i\) die Indizes, deren Wert \(n_k=a+sk\) sowohl \(n_k\ge t_i\) als auch \(n_k\equiv r_i\pmod {m_i}\) erfüllt. Es gelten \(s,m_i>0\) und \(N\ge0\). Aktivierungsschwellen sind inklusive. Die Eingabe bewahrt Reihenfolge und Wiederholungen. Jede Regelposition ist ein eigener Baumknoten, auch wenn sie keinen Index trifft. Normalisierung und verallgemeinerter CRT verwenden dieselben ganzzahligen Rechenschritte wie die exakte Vereinigung. [Kongruenzsemantik](congruence-coverage.md)

Für jeden Spannbaum \(T\) auf den Regelpositionen gilt

\[
 |W\setminus\bigcup_i A_i| \;\ge\;
 \max\!\left(0,\;N-\sum_i |A_i|+
                    \sum_{\{i,j\}\in T}|A_i\cap A_j|\right).
\]

**Beweis.** Ein Index, den \(h>0\) Regeln treffen, induziert auf den entsprechenden Knoten einen Wald mit höchstens \(h-1\) Kanten. Er trägt also zur Summe der Einzelgrößen abzüglich der Baumschnitte mindestens eins bei. Ein freier Index trägt null bei. Summieren über die Indizes liefert eine obere Schranke für die überdeckte Anzahl. Die Anzahl der freien Indizes ergibt sich durch Abziehen der überdeckten Anzahl von \(N\). Bei null Regeln sind alle \(N\) Indizes frei, bei einer Regel ist \(N-|A_0|\) exakt.

**Konstruktion.** Die Suche erweitert den Baum jeweils um eine Kante größten Gewichts zwischen den schon aufgenommenen und den übrigen Knoten (Prim). Die Schnitt-Eigenschaft liefert einen Baum mit maximaler Gewichtssumme. Bei \(r\) Regeln zählt sie genau \(\binom r2\) Paarschnitte, speichert aber pro äußerem Knoten nur die bisher beste Verbindung. Der zusätzliche Speicher und die Zahl der Zertifikatseinträge wachsen linear mit \(r\). Die Paarzahl ist eine Operationszählung. Die tatsächlichen Kosten jeder CRT-Rechnung hängen von den Größen der ganzen Zahlen ab.

**Prüfung.** Der Prüfer erhält die erwartete Progression und Regelliste separat. Er normalisiert die Regeln neu, prüft jede Einzelgröße und zählt die Schnitte der vorgelegten Baumkanten nach. Für \(r>0\) genügen \(r-1\) Kanten und Zyklenfreiheit für den Nachweis eines Spannbaums. Schließlich berechnet er die Schranke einschließlich der Nullbegrenzung neu. Ein Baum mit kleinerem Gewicht kann einen gültigen schwächeren Beleg liefern. Die verifizierte Aussage ist die numerische Untergrenze für genau diese Quelle.

## Typisierte Ableitungen

Ein DAG verarbeitet genau eine extern vorgegebene Quelle. Die Knoten stehen in topologischer Reihenfolge. Jede Prämisse verweist mit ihrem nullbasierten Index auf einen früheren Knoten. Geteilte Prämissen sind zulässig. Der Prüfer kontrolliert alle Knoten und gibt die Aussage des letzten Knotens aus.

| Knoten | Voraussetzungen | Abgeleitete Aussage |
| --- | --- | --- |
| `ExactLeaf` | Geprüftes exaktes Überdeckungszertifikat | Mindestens \(N-\mathrm{coveredCount}\) freie Indizes der erwarteten Progression |
| `HunterLeaf` | Geprüfter Spannbaum für die erwarteten Regeln | Mindestens so viele freie Indizes wie die Hunter-Schranke |
| `HallLeaf` | Geprüftes Maximum-Matching im erwarteten bipartiten Graphen | Jedes Matching lässt mindestens \(|L|-|M|\) linke Knoten unbelegt |
| `Maximum(i,j)` | Zwei Untergrenzen derselben Quelle | Das Maximum beider Werte ist eine Untergrenze |
| `Exists(i)` | Strikt positive geprüfte Untergrenze | Mindestens ein freier Index, bei einem Graphen mindestens ein unbelegter linker Knoten in jedem Matching |

Beim Hall-Schluss kann der unbelegte Knoten vom Matching abhängen. Für eine fachliche Blockierungsdeutung begründet der Consumer die vollständige Kantenmenge und Kapazität eins. Die Ableitungen betreffen ausschließlich die angegebene endliche Quelle. Zeitliche Annahmen eines Greedy-Verfahrens und Aussagen über Primzahlen gehören zum jeweiligen Anwendungsbeweis. [Vertrag des Hall-Zertifikats](bipartite-matching.md)

Kern und SDK nehmen Arbeits-, Speicherzustands- und Zeitgrenzen sowie ein Abbruchsignal entgegen. Beim Hunter-Verfahren begrenzt `maximumTerms` die Zahl der Regelpositionen. Die Arithmetik verwendet das 65.536-Bit-Limit der endlichen Kongruenzverfahren. Beim DAG begrenzt `maximumTerms` auch die Knotenzahl, und jedes Blatt hat zusätzlich die Grenzen seines Prüfverfahrens. Alle Blätter teilen ein Arbeitsbudget und eine Wallclock-Frist. Ein Abbruch der Konstruktion liefert ein leeres Zertifikatsfeld, ein gescheiterter DAG-Replay ein leeres Aussagenfeld.

## Transport im SDK

`FiniteProofDagJson.write(proof)` erzeugt ein JSON-Objekt mit `version` 1 und `proof.nodes`. Der Typ jedes Knotens steht in `kind`. Die unterstützten Werte sind `exact`, `hunter`, `hall`, `maximum` und `exists`. Prämissen sind Indizes, Ganzzahlen bleiben exakt. Beim Schreiben prüft der Encoder jeden Textabschnitt vor dem Anfügen auf die Gesamtgrenze von zwei Millionen UTF-16-Codeeinheiten. Der Decoder prüft die Textlänge vor dem Parsen und begrenzt Zahlentoken auf 20.000 Zeichen. Ein empfangenes Objekt erhält seine Bedeutung durch anschließende Prüfung gegen die separat beschaffte erwartete Quelle.

```java
var packet = FiniteProofDagJson.write(proof);
var decoded = FiniteProofDagJson.read(packet);
var replay = FiniteProofDagSdk.verify(expected, decoded, budget, cancellation);
var checkedClaim = replay.conclusion().orElseThrow();
```

## Reproduktion

Mit Java 25 und Maven im Repository-Stamm laufen die Kern- und SDK-Kontrollen sowie das [ausführbare Zahlenbeispiel](../../examples/HunterBound.java).

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=HunterCoverageTest,FiniteProofDagTest,FiniteProofDagSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
java -cp regelsuche-math-algorithms/target/classes examples/HunterBound.java
```

Das Beispiel gab folgende Werte aus.

```text
singleton counts = [15, 10, 6]
tree = [Edge[left=0, right=1, intersectionCount=5], Edge[left=0, right=2, intersectionCount=3]]
union lower bound = 0
Hunter lower bound = 7
exact survivors = 8
finite existence = VERIFIED
```

Die drei Paarschnitte sind 5, 3 und 2. Die beiden größten ergeben \(30-31+5+3=7\). Der Baum mit den Kantengewichten 5 und 2 belegt sechs. Die Kerntests prüfen auch diesen Beleg und vergleichen 220 kleine, deterministisch erzeugte Fälle mit direkter Punktzählung, einer Enumeration möglicher Baum-Wachstumsreihenfolgen und Bonferroni erster Ordnung. Gegenproben verändern Quellen, Einzelzahlen, Kanten, Graphzeugnisse, Prämissen und Ressourcen. Das SDK prüft die JSON-Rundreise für beide Quelldomänen und Zahlen oberhalb der Gleitkommapräzision.
