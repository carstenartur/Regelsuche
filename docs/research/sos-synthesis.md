# Rationale Quadratsummen und polynomielle Potentiale suchen

`SosSynthesis` sucht einen Nachweis für $p(x)\ge 0$ auf dem durch
$g_i(x)\ge 0$ und $h_j(x)=0$ beschriebenen reellen Bereich. Ziel und
Voraussetzungen dürfen beliebig viele benannte Variablen enthalten. Für
den freien Summanden, jeden Ungleichungsmultiplikator und jeden
Gleichungsmultiplikator gibt der Aufrufer eine eigene endliche Monombasis
an. Die gesuchten rationalen Koeffizienten bestimmt der Kern.

$$
p=z_0^TQ_0z_0+\sum_i g_i z_i^TQ_iz_i+\sum_j h_j\,c_j^Tb_j,
\qquad Q_i\succeq 0.
$$

Der Kern vergleicht die Koeffizienten aller Monome, die im Ziel oder
in der rechten Seite auftreten. Jede obere Dreieckshälfte einer
symmetrischen Matrix liefert Unbekannte, deren Nebendiagonale beim
Aufbau der Gleichungen doppelt zählt. Die Koeffizienten der
Gleichungsmultiplikatoren haben freies Vorzeichen. Nach exakter
Zeilenelimination setzt die Suche alle freien Koordinaten aus einem
endlichen rationalen Gitter ein und löst die Pivotkoordinaten durch
Rückwärtseinsetzen. Die Pivotkoordinaten können außerhalb des Gitters
liegen. Bei $x^4+3x^2+1$ und der Basis $(1,x,x^2)$ scheitert die
Wahl null für die freie Koordinate $Q_{xx}$. Der Wert eins ergibt eine
singuläre positiv semidefinite Matrix.

`SosVerifier` vergleicht die vollständige Anfrage einschließlich der
geordneten Basen, Voraussetzungen und Gitter mit der gebundenen Quelle.
Er prüft jede Matrix durch den exakten Schur-Test in `GramPositivity`.
Anschließend summiert er jedes Matrixelement in beiden Indexrichtungen
und jeden signierten Gleichungsmultiplikator in einer dünn besetzten
Polynomtabelle. Sämtliche resultierenden Koeffizienten müssen mit dem
Ziel übereinstimmen. Die Prüfung benötigt weder eine Zeilenreduktion
noch eine Gittersuche und akzeptiert auch anderweitig konstruierte,
gültige Zeugen für dieselbe Anfrage.

## Beispiel mit Gleichung und Ungleichung

Auf $x\ge0$ und $y-x=0$ ist $p=xy^2-x(y-x)$ nichtnegativ. In diesem
ausführbaren SDK-Beispiel enthalten die Multiplikatorbasen nur $y$
beziehungsweise $x$. Die Suche findet die Gram-Matrix $[1]$ für
den Faktor $x$, den Koeffizienten $-1$ für $x(y-x)$ und den
ungewichteten Gram-Eintrag null.

```java
var x = Polynomial.variable("x");
var y = Polynomial.variable("y");
var zero = new SosSynthesis.Premise(SosSynthesis.Relation.ZERO,
    y.subtract(x), List.of(Monomial.variable("x")));
var nonnegative = new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE,
    x, List.of(Monomial.variable("y")));
var domain = new SosSynthesis.Template(List.of(Monomial.constant()),
    List.of(zero, nonnegative), List.of(ExactRational.ZERO));
var request = new SosSynthesis.Request(x.multiply(y.pow(2)).subtract(x.multiply(y.subtract(x))), domain);
var budget = new OptimizationBudget(100_000, 1000, 1, 10_000);
var proof = SosSynthesisSdk.search(request, budget, CancellationToken.NONE).certificate().orElseThrow();
var replay = SosSynthesisSdk.verify(request, proof, budget, CancellationToken.NONE);
```

## Abnahme auf dem aktiven Bereich

`PolynomialPotential.Request` gibt eine simultane polynomielle Transition
$T$, den Bereich, eine Monombasis für $V$, ein endliches Gitter für
dessen Koeffizienten und ein rationales $\delta>0$ vor. Jede rechte
Seite wird im alten Zustand ausgewertet. Unbelegte Variablen behalten
ihren Wert. Die Suche erzeugt die Koeffizientenkombinationen und sucht
zwei Quadratsummenzertifikate auf demselben Bereich:

$$
V(x)\ge 0,\qquad D(x)=V(x)-V(T(x))-\delta\ge 0.
$$

Der Prüfer bindet die Transition und die geforderte Mindestabnahme,
prüft den Kandidatenraum und rekonstruiert unabhängig die Identität
$D+\delta+V(T)=V$. Beide Teilbeweise durchlaufen den Positivitätsprüfer.
Für $x\ge1$, $T(x)=x-1$, $\delta=1$ und die Kandidatenbasis
$(x^2)$ liefert das Gitter $(0,1,2)$ das Potential $V=x^2$ mit
$D=2(x-1)$. Beim simultanen Schritt $(x,y)\mapsto(y,x-1)$ auf
aktiven Zuständen mit $x,y\ge0$ findet die Suche $V=x+y$.

`terminationUnderContract()` setzt die ausdrückliche Bestätigung des
Aufrufers voraus, dass jeder aktive Zustand die Voraussetzungen
erfüllt und jeder tatsächliche Schritt der angegebenen Transition
folgt. Dann widerspräche eine unendliche Ausführung der unteren
Schranke null und dem einheitlichen Abfall um $\delta$. Ohne diese
Bestätigung bescheinigt der Prüfer die beiden polynomialen Aussagen.
Erreichbarkeit und Invarianz sind vom Aufrufer zu begründen.

## Befund und Reproduktion

Das vollständige [Java-25-Potentialbeispiel](../../examples/external-consumers/SosPotential.java)
ist mit dem aktuellen Quell-SDK ausführbar; Aufruf und Verfügbarkeit stehen
in der [Beispielübersicht](../demo-gallery.md#neue-ausführbare-beispiele-im-quell-sdk).

Der unabhängige Test für $ax^2+bxy+cy^2$ verwendet alle 144 Tripel
$a,c\in\{0,1,2,3\}$ und $b\in\{-4,\ldots,4\}$.
Die Suche mit Basis $(x,y)$ liefert genau für die 72 Fälle mit
$4ac\ge b^2$ ein Zertifikat, darunter elf mit Determinante null.
Für jedes gelieferte Zertifikat prüft der Test separat beide
Diagonalkoeffizienten und die Summe der Nebendiagonalen. Die übrigen
72 Anfragen liefern `INCONCLUSIVE`. Weitere Tests decken drei
Variablen, Gleichungs- und Polynom-Ungleichungsmultiplikatoren,
nichtlineare Transitionen, manipulierte Quellen, unvollständige
Basen und Ressourcenstopps ab.

```sh
mvn -pl regelsuche-optimization-sdk -am \
  -Dtest=SosSynthesisTest,PolynomialPotentialTest,SosSynthesisSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Die Basen und die rationalen Gitter begrenzen die Suche. Ihr Umfang
wächst exponentiell mit der Zahl der freien Koordinaten oder
Potentialkoeffizienten. Bei erschöpftem Gitter gibt die Suche
`INCONCLUSIVE` aus. Arbeitslimit, Abbruch, Thread-Unterbrechung,
Zeitlimit, Exponentenüberlauf und die Grenze von 65.536 Bits je
Zählerbetrag oder Nenner lassen Suche und Prüfung offen. Die
Zellengrenze (höchstens eine Million) gilt unter anderem für die
augmentierte Koeffiziententabelle mitsamt ihren Monomen, für
Zwischenpolynome und für die Einträge der Gram-Blöcke und
Gleichungsmultiplikatoren. Beim Potential müssen beide Gram-Beweise
gemeinsam in die Zellengrenze passen. Konstruktion der unveränderlichen
Eingabeobjekte erfolgt vor dem budgetierten Aufruf. Die Arbeit des
verschachtelten Gram-Prüfers wird dem Gesamtbudget belastet.

Die Motivation für überprüfbare Ungleichungen kommt aus Familie 119,
den [Rechenkonventionen des Hellinger-Zertifikats](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/Hellinger-contraction-with-arbitrary-Boolean-output-bias-September-24-2026/build/sections/arithmetic.tex#L6-L48).
Die rationale Matrixprüfung knüpft an Familie 266 an, [Abschnitt 5 der
Fourier-Zertifikate](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/Exact-Fourier-certificates-for-complex-Hadamard-matrices-of-order-six-September-24-2026/build/sections/05-verification.tex#L327-L342).
Die begrenzte Gram- und Potentialsuche ergänzt den im Projekt bereits
vorhandenen [Bernstein- und Gram-Prüfer](polynomial-positivity.md).
