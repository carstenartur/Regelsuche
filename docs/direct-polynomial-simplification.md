# Direktes Profil für exakte Polynomvereinfachung

`POLYNOMIAL_DIRECT_V1` vereinfacht einen typisierten Ausdruck anhand seiner
eigenen Koeffizienten und Teilbäume. Das Profil verwendet die vorhandene
geprüfte Suche mit einem einzigen Konstruktionsanbieter.

```java
var solver = inventory.newTaskSolverWithProfile(TaskConstructionProfile.POLYNOMIAL_DIRECT_V1);
var expression = new ExpressionParser().parseTerm("(x+y)*(x-y)+y*y+11");
var budget = new MoveSearch.Budget(9, 9, 16_384, 256, 16_384, 24);
var result = solver.simplify(expression, List.of(), budget);
```

Das Portfolio prüft Präfixe des Arbeitsbudgets bei der Erzeugung und
Bewertung typisierter Kandidaten. Es kombiniert Umformungen der Teilbäume
mit Normalisierung, Hornerformen, Gruppierung gemeinsamer Faktoren und
beschränkter Faktorisierung. Für kleine exakte Polynome kann es außerdem
den rationalen und monomialen Inhalt ausklammern, eine Produktzerlegung
über zwei disjunkte Variablenmengen anhand proportionaler
Koeffizientenzeilen prüfen und Potenzen affiner univariater Terme anhand
aller Koeffizienten erkennen.

Eine Verbesserung wird als kostenpflichtige Konstruktion angeboten. Der
Empfänger bindet Quelle und Ergebnis an dieselbe Transformation, kontrolliert
die zulässige Syntax und prüft exakte Polynomgleichheit. Der ausgewählte
Pfad wird erneut unabhängig geprüft. `result.witness()` und
`result.withinBudget()` geben den Beleg und die Budgetqualifikation an.
Die Kosten der Suche nach Kandidaten enthalten auch verworfene Versuche.

Für vergleichende Quelltextanfragen bietet der experimentelle
`TypedExternalPolynomialComparisonWorker` zwei explizite Profile.
`POLYNOMIAL_DIRECT_V1` zählt literale Potenzen wie das reguläre
Polynomprofil. `POLYNOMIAL_DIRECT_SHARED_V1` bewertet die gemeinsame
exakte Arithmetik mit Addition, Subtraktion, Multiplikation und Division
durch von null verschiedene Konstanten. Beide verwenden das bestehende
`initialize`- und `run`-Protokoll mit einer festen Arbeitsgrenze von 16.384.
