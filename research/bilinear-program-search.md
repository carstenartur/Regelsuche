# Gemeinsame bilineare Programme aus einem endlichen Formenkatalog

## Auftrag und mathematischer Vertrag

Für Eingaben $x\in\mathbb Q^m$, $y\in\mathbb Q^n$ enthält jede Zeile von
`Tensor.outputs()` die Koeffizienten einer Ausgabe. An Position `i*n+j`
steht der Faktor von $x_i y_j$. Die Anfrage gibt zusätzlich die zulässigen
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

## Suche und Grenzen

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
  -Dtest=BilinearProgramSearchTest,BilinearProgramSdkTest,BilinearPairCandidatesTest,CertificateWorkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
