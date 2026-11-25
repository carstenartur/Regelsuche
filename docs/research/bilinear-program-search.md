# Gemeinsame bilineare Programme aus Koeffizienten und Formenkatalogen

## Auftrag und mathematischer Vertrag

Für Eingaben $x\in\mathbb Q^m$, $y\in\mathbb Q^n$ enthält jede Zeile von
`Tensor.outputs()` die Koeffizienten einer Ausgabe. An Position `i*n+j`
steht der Faktor von $x_i y_j$. Der Katalogaufruf `search` gibt zusätzlich die zulässigen
linken und rechten linearen Formen und die maximale Produktzahl vor.
Dimensionen und Ausgabeanzahl sind positiv, der Produktgrenzwert darf null
sein. Leere Formenkataloge erlauben das leere Programm für einen Nulltensor.

Ein Programm enthält je eine Zeile $U_p,V_p$ pro gemeinsamem Produkt
$z_p=(U_p x)(V_p y)$ und eine Zeile $W_k$ pro Ausgabe $W_k z$.
Der Prüfer kontrolliert Dimensionen, Produktgrenze, Zugehörigkeit jeder
Eingabeform zum jeweiligen Katalog und für **alle** $k,i,j$ die Identität

$$ T_{kij}=\sum_p W_{kp} U_{pi} V_{pj}. $$

Sie beweist die Gleichheit für alle rationalen Eingaben. Ein geänderter
Quelltensor wird durch dieselbe vollständige Prüfung erkannt.
`evaluate` prüft das Programm gegen den übergebenen Auftrag und wertet es
mit dem verbleibenden Budget exakt aus.

## Suche mit vorgegebenem Katalog

Die Suche betrachtet das kartesische Produkt der beiden Kataloge. Sie
enumeriert Teilmengen nach wachsender Größe und innerhalb einer Größe
lexikographisch. Für jede Teilmenge bilden die abgeflachten äußeren Produkte
die Spalten eines linearen Systems. Jede Ausgabe liefert eine weitere rechte
Seite. Eine exakte Gauß-Jordan-Elimination bestimmt alle Gewichte zusammen.
Linear abhängige Spaltenmengen können übersprungen werden, weil das Entfernen
einer abhängigen Spalte den verfügbaren Spannraum erhält. Die Suche prüft
jeden Fund durch die vollständige Koeffizientengleichheit.

Bei $D$ Formpaaren und Breite $r$ sind bis zu $\binom{D}{r}$ Systeme dieser
Breite möglich. Ein ausgeschöpfter Katalog, Abbruch, Zeit-, Arbeits-, Zell-
oder Bitgrenze liefert `INCONCLUSIVE` ohne Programm, Kosten oder Teilwert.
`REJECTED` bezeichnet einen konkret geprüften Verstoß eines vorgelegten
Programms. Die Suche liefert keine allgemeine Aussage über den optimalen
Tensorrang. Sie arbeitet über kleinen expliziten Katalogen. Quelle 107
motiviert die Darstellung der bilinearen Abbildung durch Tensoren. Die hier
verwendete endliche Suche liefert keine asymptotische Matrixmultiplikations-
oder Laufzeitaussage. [Quelle 107, fixierter Quellstand](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/Matrix-Multiplication-Nine-Fourths-October-2-2026/build/paper.tex)

Die Zellgrenze zählt logische Tabelleneinträge einschließlich Zeilen. Der
Suchaufruf prüft die Einträge von Quelle und Katalogen und begrenzt zusätzlich
die ausgewählte Teilmenge, die erweiterte Matrix und das Programm.
Die globale Bitgrenze beträgt 65.536 Bit für Zähler und Nenner. Die Konstruktoren
kopieren bereits vorhandene Eingabedaten in unveränderliche Listen. Deserialisierung,
Eingabeerzeugung und der interne Speicherverbrauch eines BigInteger sind außerhalb
der logischen Zellrechnung. Das SDK verwendet `maximumStates` als Zellgrenze
und reicht Arbeitsgrenze, Laufzeit und Abbruchsignal weiter. Der einzige
zurückgegebene Kandidat wird unabhängig geprüft.

## Automatische Suche und nachprüfbare Produktgrenze

`BilinearProgramSdk.discover(tensor, maximumProducts, budget, token)` benötigt
die Quellkoeffizienten und die Produktgrenze. Die Ausgabe enthält den erzeugten
Auftrag, das geprüfte Programm mit seinen Ausführungskosten und einen separaten
Beleg für eine untere Schranke. Die Signatur von `search(request, ...)` bleibt
für benutzerdefinierte Kataloge verfügbar.

Für eine einzelne Ausgabematrix zieht der Java-Kern an jedem Nichtnullpivot ein
äußeres Produkt von der Restmatrix ab. Seine linke Form ist die Pivotspalte
geteilt durch den Pivotwert, seine rechte Form ist die ursprüngliche Pivotzeile
des jeweiligen Schritts. Über den rationalen Zahlen liefert das auch bei
rechteckigen Matrizen ein Programm mit genau so vielen Produkten wie der
Matrixrang. Ein Nulltensor hat ein leeres Programm für alle Ausgaben.

Bei mehreren Ausgaben gewinnt die Suche Eingabeformen aus allen von null verschiedenen
Zeilen und Spalten jeder Koeffizientenmatrix. Die Normierung setzt den ersten
Nichtnullkoeffizienten auf eins. Ergänzt werden Koordinaten sowie Summen und
Differenzen je zweier Koordinaten. Die Katalogsuche beginnt bei der bewiesenen
unteren Schranke und sucht gemeinsame Produktmengen für sämtliche Ausgaben.
Diese endliche Auswahl kann andere rationale Zerlegungen verfehlen. Die
Erzeugung der Kataloge, die Produktsuche und die unabhängigen Prüfungen
verwenden dieselbe Arbeitsabrechnung.

Für die Untergrenze betrachtet `BilinearRankBound` den dreidimensionalen
Koeffiziententensor aus drei Richtungen. Für jede dieser Abflachungen trägt
ein Produkt höchstens Matrixrang eins bei. Ein Nichtnullminor der Ordnung
$r$ belegt daher, dass jede Zerlegung in rationale bilineare Produkte
mindestens $r$ Produkte braucht. Der Prüfer rekonstruiert den Minor aus den
ursprünglichen Koeffizienten. Die Programme dürfen beliebige lineare Formen
auf den zwei vorgegebenen Eingabeseiten verwenden. Eine Optimalitätsaussage
erstreckt sich ausschließlich auf die Zahl dieser bilinearen Produkte.
Technischer Bezug sind die Aufteilungen und Rangargumente in
[Familie 379](https://github.com/openai/math/blob/f5702854040e97fa7675c21ba37a9608f5f92dd3/preprints/VP-and-VNP-are-different-November-23-2026/build/Paper.tex).

`computeRankBound` und `verifyRankBound` stehen unabhängig von einem
Programmfund zur Verfügung. `certifyProductBounds` prüft Programm und
Minor am selben Auftrag unter einem gemeinsamen Budget und liefert das
Intervall zwischen bewiesener Untergrenze und ausführbarer Obergrenze.
Gleiche Grenzen belegen Produktoptimalität. Im Test erreicht die
Zweiterm-Faltung das Intervall $[3,3]$. Bei der komplexen Multiplikation
über rationalen Komponenten liefert dieselbe Suche drei Produkte und
die Abflachung eine Untergrenze von zwei.

### Rationale Schranke mit Determinantenbüschel

Der zusätzliche SDK-Einstieg `computeRationalRankBound` liefert den Abflachungsminor und bei passenden $2 \times 2 \times 2$-Tensoren einen zweiten Beleg. Für die beiden Ausgabematrizen $A,B$ bildet er

$$q(s,t)=\det(sA+tB)=as^2+bst+ct^2.$$

Hätte der Tensor höchstens zwei rationale Produkte, ließe sich die Matrix als $U\,\mathrm{diag}(l_1(s,t),l_2(s,t))\,V^T$ schreiben. Die Spalten von $U,V$ wären die jeweiligen Eingabeformen, die $l_i$ rationale lineare Formen aus den Ausgabegewichten. Damit wäre $q$ das Produkt zweier rationaler Linearformen mal einer Konstanten oder identisch null. Für $(us+vt)(ws+zt)$ ist die Diskriminante $(uz-vw)^2$. Eine rationale Diskriminante $b^2-4ac$ ohne rationale Quadratwurzel schließt folglich jede Zwei-Produkt-Zerlegung aus. Bei einem Quadrat einschließlich null verwendet die Implementierung weiterhin den Minor. Höhere Dimensionen verwenden ebenfalls den Minor.

`verifyRationalRankBound` rekonstruiert den Minor und gewinnt die drei Polynomkoeffizienten nochmals aus $\det(A)$, $\det(B)$ und $\det(A+B)$. Er prüft die Diskriminante über normierten rationalen Zahlen samt Bit-, Arbeits- und Speichergrenzen. Bei der komplexen Multiplikation gilt $q=-s^2-t^2$, die Diskriminante beträgt $-4$. Der stärkere Nachweis und der ausgeführte Drei-Produkt-Plan ergeben mit `certifyRationalProductBounds` das Intervall $[3,3]$. `searchWithRationalBound` prüft einen vorgelegten Befund am aktuellen Auftrag, bevor die endliche Katalogsuche kleinere Ränge überspringt.

## Übertragung gelernter Produktformen

`trainProducts` gewinnt die **Paare** linker und rechter Eingabeformen aus
einem geprüften Programm einer Trainingsquelle. `reuseProducts` ermittelt
die Ausgabegewichte für die neue Aufgabe erneut durch exakte Elimination.
Der Prüfer kontrolliert alle neuen Koeffizienten. Bei übereinstimmenden Schranken kann der Plan sofort zurückgegeben werden.
Bleibt eine Lücke, sucht der Kern im generierten Katalog nach einem günstigeren Plan.
Ein Fund ersetzt den übernommenen Plan. Bei vollständig ausgeschöpftem Katalog
bleibt der geprüfte übernommene Plan mit offener Schranke erhalten.
Bei unpassenden Formen oder Dimensionen läuft die reguläre Suche im verbleibenden Budget. Ein Abbruch an einer Ressourcen-
oder Zeitgrenze beendet den gesamten Aufruf.

Die Pivotzerlegung, die Formenerzeugung und die Suche sind implementierte
allgemeine Verfahren. Gelernt werden die konkreten Produktpaare der
Trainingsquelle. Ein Lernvergleich muss Erwerb, erfolglose Anpassungen,
Prüfung und Rückfall zur regulären Suche mitrechnen. Das
[Java-Benchmarkprojekt](../../research/benchmarks/bilinear-form-transfer/)
vergleicht zusätzlich den ausführbaren Rechenplan in einem Verbraucher.

### Geprüfter Wechsel der Eingabebasen

`applyInputBases(sourceRequest, sourceProgram, bases, targetTensor, budget, cancellation)` übernimmt einen bereits vorliegenden Rechenplan. Der Aufrufer gibt zwei quadratische rationale Matrizen $L,R$ mit der Konvention $x_{alt}=Lx_{neu}$ und $y_{alt}=Ry_{neu}$ an. Jede frühere Zeilenform $u,v$ wird zu $uL,vR$. Reihenfolge und Ausgabegewichte bleiben Bestandteil der Behauptung. Der Kern prüft den Ausgangsplan, beide Determinanten und alle Tensorkoeffizienten des verlangten Ziels. `verifyInputBases` prüft fremde Ergebnisse mit den erwarteten Quellen, Matrizen und Zielkoeffizienten. Die ausgegebene `Transfer`-Struktur hält Ausgangsauftrag, Ausgangsprogramm, Matrizen, Zielauftrag und geprüften Zeugen zusammen. Der Aufruf berichtet auch die Arbeit eines zurückgewiesenen Transfers.

Der [deterministische Vergleich mit zwei festen Kontrollen](bilinear-input-basis-study.md) verwendet sechs von den Erwerbsquellen getrennte Koordinatendarstellungen. Alle 90 Auswertungen stimmen überein; die klassische Drei-Produkt-Formel benötigt dort die geringste gezählte Ausführungsarbeit.

## Kosten des ausführbaren Programms

Die feste Reihenfolge bildet die linke Form, die rechte Form und ihr Produkt,
dann alle Ausgaben. Alle Produkte bleiben bis zum Ende verfügbar. Eine
Nullkoordinate verursacht keine arithmetische Operation. Eine Eins übernimmt
den Wert direkt, jeder andere Faktor einschließlich −1 zählt als eine
Konstantenmultiplikation. Eine Form mit $s>0$ nicht verschwindenden
Koeffizienten kostet $s-1$ Additionen. Wiederholte Eingabeformen werden
erneut berechnet. Die Kostenzahl gilt für diesen Auswertungsplan.

`peakTemporaries` ist die maximale Anzahl zugleich belegter skalarer
Zwischenspeicher. Eingangswerte, Konstanten und fertige Ausgaben sind
ausgenommen. Ein berechneter Zwischenwert einer Form belegt einen Platz,
bis er verbraucht oder als fertige Ausgabe abgelegt ist. Ein skalierter
Summand kann einen weiteren Platz beanspruchen. Eine Operation darf einen
verbrauchten Platz überschreiben. Der Wert beschreibt das skalare Programm,
nicht den JVM-Heap oder die Suchkosten.

## Ausführungspläne für ganzzahlige Operanden

`BilinearProgramSdk.selectInteger` vergleicht mehrere Zerlegungen eines ganzzahligen
Quelltensors. Der Aufrufer gibt für jede linke und rechte Eingabe eine Betragsbreite
$b$ mit $|x|<2^b$ an. Der direkte Vergleichsplan berechnet jedes benötigte
Koordinatenprodukt einmal für alle Ausgaben. Er nimmt teil, sofern die Produktgrenze
ihn zulässt. Wer eine Optimierung gegen den ursprünglichen Rechenweg abwägen möchte,
sollte diese Grenze entsprechend wählen.

Die Suche untersucht über den rationalen Zahlen unabhängige Produktmengen aus dem
erzeugten Formenkatalog. Sie teilt ganzzahlige Zeilen und Spalten durch ihren
vorzeichenbehafteten größten gemeinsamen Teiler. So bleibt etwa die Richtung
$(2,3)$ ohne Division in der Ausführung darstellbar. Die Ausgabegewichte werden
für diese Formen neu bestimmt und müssen ebenfalls ganzzahlig sein.
Zerlegungen mit abhängigen Zusatzprodukten oder exakten Divisionen können außerhalb
dieser Suche liegen. Für jeden aufgenommenen Kandidaten
prüft sie die Identität am Quelltensor und erzeugt einen ausführbaren Plan. Dieser
kombiniert schmale Terme zuerst, führt Vorzeichen bis zur nächsten Addition,
Subtraktion oder Ausgabe mit und verwendet identische Zwischenergebnisse mehrfach.
Auch ein Fund aus `discover`, `search` oder `reuseProducts` lässt sich über
`scheduleInteger` gegen seinen Auftrag prüfen und in dieser Form ausführen.
`evaluateInteger` kontrolliert die Betragsgrenzen vor der Auswertung.

Das Kostenmodell schätzt arithmetische Arbeit und Schreibaufwand in 32-Bit-Wörtern.
Für Multiplikationen verwendet es das Produkt der Operandenlängen in Wörtern.
Es zählt die Ergebnisobjekte und die Wörter ihrer Magnituden getrennt.
JIT-Optimierung, unterschiedliche Multiplikationsverfahren im JDK und die anschließende
Reduktion modulo $p$ gehen in die JVM-Messung ein, soweit der Verbraucher sie ausführt.
Der Aufrufer kann anhand der Modellwerte zwischen zwei Zielen wählen.

| SDK-Ergebnis | Wahl unter den geprüften Kandidaten |
| --- | --- |
| `selected()` | Geringste geschätzte Wortarbeit. Bei Gleichstand entscheiden Ergebniswörter, Ergebnisobjekte und schließlich variable Produkte. |
| `preferredWithoutAllocationIncrease()` | Geringste geschätzte Wortarbeit unter den Kandidaten, deren Ergebniswörter **und** Ergebnisobjekte jeweils höchstens den Wert des direkten Plans erreichen. Ohne zulässigen Direktvergleich bleibt das Ergebnis leer. |

Beide Entscheidungen sind an das angegebene Profil gebunden. Der Teilmengengrenzwert
und `budget.maximumCandidates()` begrenzen die Auswahl. Bei Erreichen einer
solchen Grenze kann die Suche den besten bisher geprüften Plan mit `complete() == false`
liefern. Ein Abbruchsignal oder eine harte Arbeits-, Zell- oder Zeitgrenze ergibt
`INCONCLUSIVE` ohne Plan. Auch `complete() == true` gilt nur für die unterstützte
Suche im endlichen Katalog. Den [Vergleich von Modell, JMH und gemessener Allokation](../../research/benchmarks/bilinear-integer-selection/)
gibt es für Ganzzahlfaltung und die 381-Bit-Feldrechnung aus Bouncy Castle.

## Ausführbares SDK-Beispiel

Eine vollständige Java-Datei ist [BilinearSearch.java](../../examples/external-consumers/checked-math-java25/src/main/java/example/BilinearSearch.java).
Sie benötigt den aktuellen Quell-SDK-Stand; die API ist noch nicht in Release 0.5.3 enthalten.

Mit den SDK- und Kernklassen im Klassenpfad lässt sich das Beispiel in JShell
ausführen. Die drei Quellzeilen sind die Koeffizienten der Faltung von
$(a,b)$ und $(c,d)$. Beide Kataloge enthalten auch die Summe der beiden
Koordinaten. Die gemeinsamen Ausgangsprodukte und ihre Ausgabegewichte
bestimmt die Suche.

```java
import de.regelsuche.math.algorithms.algebra.BilinearProgram.*;
import de.regelsuche.sdk.optimization.*;
import de.regelsuche.sdk.optimization.algebra.BilinearProgramSdk;
import de.regelsuche.scalar.ExactRational;
import java.util.*;
List<ExactRational> row(long... a) { return Arrays.stream(a).mapToObj(ExactRational::integer).toList(); }
var forms = List.of(row(1,0), row(0,1), row(1,1));
var tensor = new Tensor(2,2,List.of(row(1,0,0,0),row(0,1,1,0),row(0,0,0,1)));
var request = new Request(tensor, forms, forms, 3);
var budget = OptimizationBudget.DEFAULT;
var token = CancellationToken.NONE;
var witness = BilinearProgramSdk.search(request, budget, token).value().orElseThrow();
System.out.println(witness.cost());
System.out.println(BilinearProgramSdk.evaluate(request, witness.program(), row(2,3), row(4,5), budget, token).value().orElseThrow());
// [8, 22, 15]
```

Mit demselben Tensor ist auch der Aufruf ohne vorgegebene Formen möglich.
Die beiden Prüfer lassen sich auf die übertragenen Daten erneut anwenden.

```java
var discovered = BilinearProgramSdk.discover(tensor, 3, budget, token).value().orElseThrow();
var interval = BilinearProgramSdk.certifyProductBounds(discovered.request(),
    discovered.witness().program(), discovered.lowerBound(), budget, token).value().orElseThrow();
System.out.println(interval.productOptimal()); // true
```

Der direkte Plan für $(ac,ad+bc,bd)$ verwendet vier variable Produkte und
eine Addition. Das gefundene Programm verwendet drei variable Produkte,
vier Additionen, zwei explizite Vorzeichenfaktoren und maximal fünf skalare
Zwischenspeicher. Der Vergleich erfasst die arithmetischen Operationen und
enthält keine Laufzeitmessung. Das vorhandene `BilinearPairCandidates`
verarbeitet weiterhin das spezielle Ausdruckspaar $(ac-bd,ad+bc)$.
Automatische Java-Umschreibungen brauchen unverändert die Prüfung durch den
jeweiligen Verbraucher und dessen Zahlensemantik.

Die Tests vergleichen die Faltung auf allen 81 Eingaben aus
$\{-1,0,1\}^4$ mit der direkten Formel. Weitere Vergleiche prüfen einen
asymmetrischen rationalen Tensor und zwei dichte Ausgaben auf 64 rationalen
Eingabepaaren durch direkte Tensor-Kontraktion. Sie prüfen außerdem Nulltensor,
beschränkten Misserfolg, manipulierte Quellen und Programme, Kataloge,
Dimensionen, Abbruch und Ressourcenüberschreitung.

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=BilinearProgramSearchTest,BilinearFormDiscoveryTest,BilinearRankBoundTest,BilinearProductLearningTest,BilinearInteger*Test,BilinearProgramSdkTest,BilinearPairCandidatesTest,CertificateWorkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
