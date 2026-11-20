# Exakte Positivitätszertifikate für rationale Polynome

Für ein Polynom in einer oder zwei Variablen liefert `BernsteinPositivity`
eine rationale Unter- und Obergrenze auf einem abgeschlossenen Intervall
oder Rechteck. Durch Halbierung kann die Suche Nichtnegativität oder
strikte Positivität auf der gesamten angegebenen Box beweisen.
`GramPositivity` prüft separat einen vom Aufrufer gelieferten globalen
Nichtnegativitätsnachweis.

## Zwei Blätter für ein positives Quadrat mit Zuschlag

Für $p(x)=(x-1/2)^2+1/8=x^2-x+3/8$ auf $[0,1]$ lautet das Kontrollnetz
`[3/8, -1/8, 3/8]`. Die negative mittlere Zahl ist eine gültige, aber
ungenaue Untergrenze. Im Vergleich liefert die Suche ohne Unterteilung
`INCONCLUSIVE`. Ein Schnitt bei `1/2` erzeugt links `[3/8, 1/8, 1/8]`
und rechts `[1/8, 1/8, 3/8]`. Da die Bernstein-Basisfunktionen auf jeder
Teilbox nichtnegativ sind und Summe eins haben, gilt dort jeweils
$p\ge 1/8>0$. Entfällt der Zuschlag, ergibt derselbe Schnitt
Nichtnegativität. Die gemeinsame Grenze der beiden Hälften ist dann
tatsächlich eine Nullstelle.

```java
var polynomial = new SparsePolynomial(List.of(2), Map.of(
    new SparsePolynomial.Exponent(2, 0), ExactRational.ONE,
    new SparsePolynomial.Exponent(1, 0), ExactRational.NEGATIVE_ONE,
    new SparsePolynomial.Exponent(0, 0), ExactRational.parse("3/8")));
var request = new BernsteinPositivity.Request(polynomial, List.of(
    new BernsteinPositivity.Interval(ExactRational.ZERO, ExactRational.ONE)));
var limits = new IterationLimits(100_000, 1_000, 10_000);
var range = BernsteinPositivity.bounds(request, limits, () -> false).bounds().orElseThrow();
var proof = BernsteinPositivity.prove(request, 1, limits, () -> false).certificate().orElseThrow();
var replay = BernsteinPositivity.verify(request, proof, limits, () -> false);
```

Die Anzahl der Einträge in `degrees` gibt die Variablenzahl an. Die Grade
sind obere Schranken. Im univariaten Fall ist der zweite Exponent null.
Eine leere Termtabelle bezeichnet das Nullpolynom. Die Exponententabelle
verwirft Nullkoeffizienten. Die Achsen haben rationale, strikt geordnete
Endpunkte. Rechtecke werden in der Reihenfolge der beiden Variablen angegeben.
Der Tensor hat `(dx+1)(dy+1)` Einträge, der Index der zweiten Variablen
läuft am schnellsten.

## Erzeugung und unabhängiger Replay

Die Erzeugung multipliziert die Bernstein-Darstellung der Konstanten eins
wiederholt mit der Koordinatenfunktion auf dem Intervall. Weitere
Multiplikationen mit eins erhöhen den Darstellungsgrad auf den deklarierten
Grad. Bei zwei Variablen bildet der Kern pro Monom das Tensorprodukt, skaliert
es mit dem rationalen Koeffizienten und summiert die Netze. `bounds` liefert
das komplette Netz mit seinem Minimum und Maximum. Die tatsächlichen
Extrema können innerhalb dieser Schranken liegen.

`prove` unterteilt Netze mit negativem Eintrag durch exakte de-Casteljau-
Mittelungen. Die Suche wechselt bei zwei Achsen positiven Grades die
Schnittrichtung. Das Zertifikat speichert Splits und Blätter in Präordnung.
Der Prüfer rekonstruiert aus der erwarteten Ausgangsbox beide Hälften jedes
Schnitts und verbraucht für jedes Blatt genau eine erwartete Teilbox.
Unverbrauchte Boxen, zusätzliche Schritte und vertauschte Blätter machen
das Zertifikat ungültig. Benachbarte geschlossene Teilboxen dürfen ihren
gemeinsamen Rand teilen.

Der Prüfer führt weder die Suchentscheidung noch die Umrechnung der
Koeffizienten erneut aus. Er vergleicht auf jeder Blattbox die Potenzsumme
des ursprünglichen Polynoms mit der vorgelegten Bernstein-Summe an
`dx+1` verschiedenen rationalen Stützstellen der ersten Achse und gegebenenfalls
`dy+1` der zweiten. Sind alle Werte gleich, verschwindet die Differenz
zuerst als Polynom in der ersten Variablen bei jeder Stützstelle der zweiten Achse
und dann als Polynom in der zweiten Variablen für jedes feste erste Argument.
Bei Grad null genügt ein Punkt. Dieser Identitätsschritt begründet die
vollständige Aussage auf dem Kontinuum. Die Vorzeichenprüfung der bestätigten
Koeffizienten begründet anschließend die Ungleichung. `verifyBounds` prüft
dieselbe Identität auf der Ausgangsbox und beide angegebenen Extremkoeffizienten.

## Rationale Gram-Matrizen

`checkMatrix` prüft Form und exakte Symmetrie einer nichtleeren quadratischen
Matrix. Ein positiver Diagonaleintrag $a$ erlaubt den Schur-Schritt
$C-uu^T/a$. Ein negativer Pivot widerlegt positive Semidefinitheit. Bei
Pivot null muss die gesamte restliche Pivotzeile null sein. Andernfalls
hat eine zweireihige Hauptuntermatrix Determinante $-u_i^2<0$. Eine
Nullzeile lässt sich entfernen und belegt Singularität. Sind alle Pivots
positiv, ist die Matrix positiv definit. `POSITIVE_SEMIDEFINITE` bezeichnet
in dieser Klassifikation den singulären positiv semidefiniten Fall.

Für eine Polynombehauptung benötigt `verifyPolynomial` die erwartete
Quelle sowie ein Zertifikat mit Quelle, Monomvektor $z$ und Matrix $Q$.
Der Prüfer fordert eine positiv semidefinite oder positiv definite Matrix und
vergleicht sämtliche Koeffizienten von $z^TQz$ mit dem Quellpolynom,
einschließlich gemischter und sich aufhebender Terme. Erst dann folgt
globale Nichtnegativität. Zum Beispiel belegt `Q=[[1]]` mit `z=[x]`
die globale Nichtnegativität von $x^2$. Die Matrix ist positiv definit und das
Polynom verschwindet bei null. Für den Nachweis ist keine Gram-Matrix-Suche
und kein numerischer Eigenwertlöser erforderlich.

## Grenzen und Reproduktion

Die Suche beendet ein Blatt beim ersten nichtnegativen Netz. Sie kann daher
eine schwächere Aussage als die tatsächlich geltende strikte Positivität
liefern. Das Erreichen der Unterteilungstiefe bei einem weiterhin
vorzeichenbehafteten Netz lässt die Ungleichung offen. Eine systematische
Widerlegung, automatische SOS-Suche, komplexe Hermitesche Matrizen und
mehr als zwei Variablen liegen außerhalb des Eingabevertrags.

Kern und SDK prüfen das Abbruchsignal, Thread-Unterbrechung, Laufzeit,
Arbeitsbudget und die Bitgrenze des vorhandenen exakten Rechenkerns
(65.536 Bits je Zählerbetrag und Nenner). Ein Ressourcenstopp liefert
kein Teilzertifikat. `maximumTerms` bzw. SDK-`maximumStates` begrenzt
unter anderem die Zahl der Quellterme, der Matrixeinträge und die Summe
der Kontrollnetzgrößen aller bearbeiteten Boxen. Für diese Größen
gilt zusätzlich eine Million als feste Obergrenze. Die erstmalige
Konstruktion der unveränderlichen Eingabeobjekte liegt vor dem budgetierten
Aufruf. Prüfung und Suche können unterschiedliche Budgets benötigen.
Die ausgewiesene Arbeit zählt belastete algorithmische Operationen und
Wortgrößen, keine gemessenen Maschineninstruktionen.

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=BernsteinPositivityTest,GramPositivityTest,PolynomialPositivitySdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Die Kontrollen vergleichen 64 rationale Rechtecke mit unabhängig bestimmten
quadratischen Polarformen und alle 729 symmetrischen 3×3-Matrizen mit
Einträgen aus `{-1,0,1}` mit allen Hauptminoren. Weitere Fälle verändern
Behauptung, Quelle, Teilboxen, Koeffizienten oder Gram-Basis und prüfen
Abbruch und Ressourcenerschöpfung. Diese endlichen Prüfungen begründen
keine allgemeine Aussage über Laufzeitvorteile.

Die Methoden sind klassisch. Der konkrete Transfer der rationalen
Bernstein-Schranken und Halbierung folgt den [Rechenkonventionen des
Hellinger-Zertifikats](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/Hellinger-contraction-with-arbitrary-Boolean-output-bias-September-24-2026/build/sections/arithmetic.tex#L6-L48).
Die rationale Schur-Prüfung findet sich in
[Abschnitt 5 der Fourier-Zertifikate](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/Exact-Fourier-certificates-for-complex-Hadamard-matrices-of-order-six-September-24-2026/build/sections/05-verification.tex#L327-L342).
Der Java-Kern ergänzt den singulären Fall und die explizite Prüfung der
polynomiellen Gram-Identität. Beide Links sind auf die untersuchte Revision festgelegt.
