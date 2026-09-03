# Exakte endliche Polynom-Lückensuche v1

## Zweck

`ExactFinitePolynomialHoleSolver` ist der erste ausführbare Lückensolver für
die schematische Beweisplan-Infrastruktur aus Issue #874. Er erhält:

```text
Quellausdruck
+ vorher festgelegten algebraischen Ansatz
+ endliche exakte Domäne je Lücke
+ Grenze für zurückbehaltene Lösungen
```

Die konkrete Zielgestalt, ein historischer Name und spätere Holdout-Ergebnisse
sind keine Eingaben des Solvers. Der Ansatz selbst ist jedoch Formationseingabe
und muss bei einem wissenschaftlichen Lauf vor VALIDATION und FINAL TEST
eingefroren werden.

## Unterstützte Lücken

Version 1 unterstützt zwei Sorten:

- `COEFFICIENT`: beliebige explizit aufgezählte `ExactRational`-Werte;
- `SIGN`: ausschließlich `-1` und `1`.

Platzhalter verwenden die Syntax `${hole-id}`. Jede deklarierte Lücke muss im
Ansatz vorkommen, und jeder Platzhalter benötigt genau eine Domäne. Domänen,
Werte und Lösungen werden deterministisch nach Lücken-ID beziehungsweise
exaktem Zahlenwert geordnet.

## Ausführung

Für das kartesische Produkt aller Domänen wird jede Belegung vollständig
untersucht:

```text
Ansatz mit Lücken
  -> exakte Belegung einsetzen
  -> mit parsergebundener Zahlenprovenienz einlesen
  -> in die bestehende exakte Polynomdarstellung projizieren
  -> mit der Polynomnormalform der Quelle vergleichen
  -> nur exakte Treffer zurückbehalten
```

Der Solver verwendet `ExactResidualPolynomialArithmetic`, also dieselbe
source-exakte Grenze wie die Resttermkomposition. `double`-Rundung autorisiert
keine Gleichheit. Eine Belegung, die Division durch null, negative oder
nicht-ganzzahlige Exponenten, nichtpolynomiale Funktionen oder eine
Ressourcenüberschreitung erzeugt, wird nicht stillschweigend übersprungen:
Der gesamte Lauf schlägt fail-closed fehl.

## Grenzen

Version 1 begrenzt:

- 12 Lücken;
- 128 Werte je Lücke;
- 512 Bits je exaktem Zähler oder Nenner;
- 100.000 Belegungen insgesamt;
- 256 zurückbehaltene Lösungen;
- 16.384 Zeichen im Ansatz.

Alle Belegungen werden ausgeführt. Eine kleine Lösungsliste begrenzt nur die
persistierbare Ausgabe, nicht die Zahl der geprüften Belegungen.

## Ergebnisstatus

```text
COMPLETE_WITHOUT_SOLUTION
  vollständiges endliches Produkt geprüft, kein Treffer

COMPLETE_WITH_SOLUTIONS
  vollständiges Produkt geprüft, alle Treffer zurückbehalten

COMPLETE_SOLUTION_SET_TRUNCATED
  vollständiges Produkt geprüft, aber mehr Treffer als die Ausgabelimitierung
```

`SearchResult.contentHash()` bindet Solverrevision, Quelle, Ansatz, geordnete
Domänen, Arbeitszähler, Status und zurückbehaltene Lösungen. `replay(result)`
führt dieselbe endliche Suche erneut aus und verlangt ein identisches
Ergebnisobjekt. Das ist eine deterministische Laufreproduktion, aber noch kein
unabhängig geladenes Evidence-Artefakt oder formaler Beweis.

## Typisierte Lösungsidentität

Der menschenlesbare `Solution.bindingKey()` bleibt bewusst bei der kompakten
Darstellung `holeId=value`. Er ist eine Anzeige- und Sortierhilfe, nicht die
vollständige kryptografische Identität.

`Solution.contentHash()` verwendet seit
`regelsuche.exact-finite-polynomial-solution-identity/v2` dagegen für jede nach
Lücken-ID geordnete Belegung das vollständige Tupel

```text
holeId
HoleKind
kanonischer exakter Wert
```

und bindet anschließend den instanziierten Ausdruck sowie seine exakte
Normalform. Eine `SIGN`- und eine `COEFFICIENT`-Belegung mit derselben ID und
demselben Zahlenwert erhalten deshalb verschiedene Hashes. Benachbarte ganze
Zahlen oberhalb der `binary64`-Präzision bleiben ebenfalls verschieden.

Die Solverrevision enthält die Identitätsrevision ausdrücklich. Dadurch ändern
sich `SearchResult`, Formation Scope, Plan, Resolverlauf und Replay-Receipt
transitiv. Frühere untypisierte Hashes werden nicht als aktuelle Identitäten
neu interpretiert. Unabhängig vom Hash prüft `SearchResult` weiterhin direkt,
dass jede Belegung genau zur deklarierten Hole-Domäne und Hole-Art gehört.

## Charakterisierungen

### Quadratische Ergänzung

```text
source:
  x^2 + 6*x + 5

ansatz:
  (x + ${shift})^2 + ${constant}

shift:
  -5 .. 5

constant:
  -10 .. 10
```

Nach 231 exakten Belegungen bleibt genau

```text
shift = 3
constant = -4
```

und damit

```text
x^2 + 6*x + 5 = (x + 3)^2 - 4.
```

### Sophie-Germain-Zwischenform

```text
source:
  x^4 + 4*y^4

ansatz:
  (x^2 + ${alpha}*y^2)^2 - (${beta}*x*y)^2

alpha, beta:
  -3 .. 3
```

Nach 49 Belegungen bleiben

```text
alpha = 2, beta = -2
alpha = 2, beta =  2
```

Beide erzeugen die zielunabhängig gefundene Zwischenform

```text
(x^2 + 2*y^2)^2 - (2*x*y)^2.
```

Die anschließende Differenz-von-Quadraten-Regel bleibt ein getrennter
Transformationsschritt.

## Aussagegrenze

Dieser Slice belegt eine vollständige begrenzte Koeffizienten- und
Vorzeichenbelegung innerhalb eines vorgegebenen Ansatzes. Er belegt noch nicht:

- autonome Wahl oder Lernen der Ansatzgrammatik;
- lineares symbolisches Koeffizientenlösen ohne endliche Enumeration;
- unabhängige Evidence-Dereferenzierung;
- Kompilation in `RewriteProgram`;
- generationengetrennten Taktiktransfer;
- mathematische Neuheit.

Die nachgelagerte `ExactFinitePolynomialPlanResolver`-Stufe bindet eine
Solver-Lösung an die passenden `SchematicProofPlan`-Lücken und erzeugt eine
exakte `EQUIVALENT`-Obligation. Der Replay-Verifier führt den vollständigen Lauf
erneut aus und stellt nur bei identischer Reproduktion ein nicht ausführbares
Receipt aus. Beide Stufen bleiben von unabhängiger Byte-Evidence und formaler
Proof-Autorität getrennt.

## Reproduktion

```bash
./gradlew \
  :regelsuche-math-algorithms:test \
  --tests '*ExactFinitePolynomialHoleSolverTest' \
  --tests '*ExactFinitePolynomialSolutionIdentityTest'

./gradlew \
  :regelsuche-learning:test \
  --tests '*ExactFinitePolynomialPlanRunSubstitutionTest' \
  --tests '*ExactFinitePolynomialRevisionBindingTest'

./gradlew --no-configuration-cache ciCheck
```
