# Identitäten geordneter Polynome

**EXPERIMENTAL.** Der Aufrufer kann zwei Formeln der freien assoziativen
Algebra über den rationalen Zahlen vergleichen. Beispielsweise unterscheidet
der Prüfer `xy` und `yx`, bestätigt aber `x(a+y) = xa+xy` für einen rationalen
Skalar `a`. Eine Beurteilung der Gleichheit auf Matrizen einer vorgegebenen
Dimension, bei Maschinenüberlauf, in endlicher Charakteristik oder mit
Gleitkommadaten erfordert einen eigenen Semantikvertrag.

## Satz und Transfer

Familie 116 behandelt Formelbäume mit Skalar- und Variablenblättern sowie
binären Additionen und geordneten Multiplikationen. Jedes Blatt und jedes
Gatter zählt für die Größe $s$. Über jedem Körper der Charakteristik null
erkennt ein von $n,s$ abhängiges rationales Matrizentupel jedes von null
verschiedene Polynom dieser Klasse. Die Konstruktion zeigt bereits für die
erste Spalte $f(T)e_0$ einen von null verschiedenen Eintrag. Für $ns\ge2$ ist
$d=2ns^2$. Der Sonderfall $n=s=1$ verwendet $T_1=[1]$.

Für den Vergleich bildet der Kern $f=L-R$. Jede Subtraktion, einschließlich
der Wurzel, zählt wie $L+((-1)R)$ mit drei zusätzlichen Knoten neben beiden
Teilbäumen. Dadurch gilt die Größenhypothese des Satzes unmittelbar.
Mehrfach referenzierte Teilbäume zählen an jeder Verwendungsstelle.
Konstantenformeln werden direkt über den rationalen Zahlen in Dimension eins
ausgewertet. Die einzige Division im Algorithmus erfolgt durch eine bekannte
positive ganze Zahl. Der Formeltyp enthält keine Division.

Die [Einleitung](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/One-Rational-Matrix-Hitting-Point-for-Noncommutative-Formulas-September-24-2026/build/introduction.tex)
legt den Formelvertrag fest. Die [Konstruktion](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/One-Rational-Matrix-Hitting-Point-for-Noncommutative-Formulas-September-24-2026/build/construction.tex)
leitet die Aussage zur ersten Spalte aus der
[Formelrepräsentation](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/One-Rational-Matrix-Hitting-Point-for-Noncommutative-Formulas-September-24-2026/build/formula-systems.tex)
und der [Multiplizitätsschranke](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/One-Rational-Matrix-Hitting-Point-for-Noncommutative-Formulas-September-24-2026/build/multiplicity.tex)
ab. Laut [Lean-Dokumentation](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/lean/docs/116.md)
ist die universelle Treffer-Eigenschaft formalisiert. Die angegebenen
Dimensions- und Bitkomplexitätsschranken stammen aus dem Manuskript.
Die Java-Tests führen die Lean-Formalisierung nicht aus.

## Auswertung und SDK

Jeder Eintrag in `variableOrder` ist eine ganzzahlige Variablenkennung.
Die Listenposition bestimmt den Matrixindex $i=1,\dots,n$. In Zeile $r$,
Spalte $q$ ist $T_i[r,q]=(-1)^{r-q-1}/(r i^{r-q})$ für $q<r$ und sonst null.
Aus $(z+i)b'=a$ und $b_0=0$ folgt für die Vektoranwendung

$$
b_r=\frac{a_{r-1}-(r-1)b_{r-1}}{ri},\qquad 1\le r<d.
$$

Das Produkt wendet zuerst den rechten Faktor auf den Vektor an. Eine Summe
wendet beide Operanden auf denselben Eingabevektor an und addiert die Spalten.
Skalare wirken als Vielfache der Einheitsmatrix. Der Kern verwendet
`ExactRational` und legt weder dichte Matrizen noch Wortlisten an.

```java
var x = new NoncommutativeFormula.Variable(10);
var y = new NoncommutativeFormula.Variable(20);
var result = NoncommutativeIdentitySdk.compare(
    NoncommutativeFormula.multiply(x, y), NoncommutativeFormula.multiply(y, x),
    List.of(10, 20), NoncommutativeIdentity.Limits.defaults(), CancellationToken.NONE);
```

Ein `Completed` enthält die geprüfte Differenzformel, eine unveränderliche
Kopie der Variablenreihenfolge, die gezählte Baumgröße und die gesamte exakte
erste Spalte. Bei `DISTINCT` ist mindestens eine Koordinate von null
verschieden. `IDENTICAL` stützt sich bei Variablen auf die Nullspalte und den
obigen Satz. Diese Daten belegen die Auswertung im Prozess. Für eine erneute
Prüfung übergeben Verbraucher ihre erwarteten Formeln und ihre eigene
Variablenreihenfolge erneut an `compare`. Der skalare Optimierer verwendet
diesen separaten Formeltyp und diesen Einstieg nicht automatisch.

## Grenzen und Vergleichsrechnung

`Limits.defaults()` erlaubt 32 deklarierte Variablen, 1.023 expandierte
Knoten, 64 Ebenen im Eingabe-AST, Dimension 4.096, 8.192 Bit je Betrag von
Zähler und Nenner sowie 200.000 Einheiten $sd$. Die maximale anforderbare
AST-Tiefe ist 64. Der Aufrufer kann die anderen Budgets passend zu seinem
Speicher- und Zeitrahmen setzen. Schon die Konstruktion rationaler
Eingabeblätter durch den Aufrufer liegt vor dieser Prüfung. Fehlende oder
mehrfach deklarierte Variablen führen zu `UNSUPPORTED`.

Die Vorprüfung merkt sich Baumgröße und Höhe pro Objekt und addiert pro
Referenz die ganze Teilbaumgröße. Sie prüft $sd$ und die Dimension vor der
Vektorallokation. Die Auswertung braucht $O(sd)$ rationale Operationen
und bis zu $O(d\cdot\mathrm{Tiefe})$ gleichzeitig gehaltene Koordinaten.
Das Bitbudget gilt für Eingaben, ganzzahlige Rekurrenzfaktoren und jedes
normierte rationale Teilergebnis. Eine einzelne exakte Operation darf intern
Produkte zweier schon zugelassener Operanden und deren Summe bilden.
Diese internen Ganzzahlen können bis zu $2B+1$ Bit haben, wenn das gesetzte
Bitbudget $B$ beträgt. Das Arbeitspaket $sd$ ist keine Laufzeit in
Bitoperationen. Der Abbruch wird beim Einlesen der Ordnung, bei der
Baumprüfung und während der Vektorauswertung abgefragt. `BUDGET_EXCEEDED`
und `CANCELLED` enthalten keine Teilspalte und kein Gleichheitsurteil.

Die unabhängige Testreferenz multipliziert Wortlisten durch Konkatenation und
sammelt gleiche Wörter mit ihren rationalen Koeffizienten. Bei 20 kleinen
Formelpaaren (Seed 116) werden die Urteile verglichen. Für den Kommutator
prüft ein zweiter Vergleich die ersten acht Koordinaten durch explizite
dichte Matrixprodukte. Wegen der unteren Dreiecksform genügen dafür die
linken oberen $8\times8$-Blöcke.

| Geordnete Formel, zwei Variablen | Verschiedene Wörter der Referenz | Baumgröße $s$ | Dimension $d$ | Prüfer mit Standardbudget |
| --- | ---: | ---: | ---: | --- |
| $(x+y)^3$ | 8 | 11 | 484 | `DISTINCT` gegenüber null |
| $(x+y)^9$ | 512 | 35 | 4.900 | `BUDGET_EXCEEDED` vor der Vektorauswertung |

Die Potenzen stehen hier für explizite geordnete Produkte. Für diese kleinen
Fälle bleibt die Wortreferenz praktikabel, während die quadratische Dimension
das Standardbudget bereits bei neun Faktoren überschreitet. Ein
Geschwindigkeitsvorteil ist durch diese Funktionsvergleiche nicht belegt.

Mit Java 25 und Maven vom Repository-Stamm:

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=NoncommutativeIdentityTest,NoncommutativeIdentitySdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
