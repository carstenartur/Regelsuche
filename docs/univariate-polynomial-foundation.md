# Univariate Polynomgrundlage und quadratfreie Zerlegung

**Implementierungsstand: 25. August 2026**

Diese Stufe erweitert den domänenbewussten Polynomkern um die ersten allgemeinen
Algorithmen, die nicht an eine Quartikschablone gebunden sind. Sie faktorisiert
noch nicht vollständig in irreduzible Faktoren. Sie stellt aber die
algorithmische Grundlage bereit, auf der die modulare Faktorisierung über
`Z[x]` und `Q[x]` aufbauen kann.

## Architektur

```text
SparsePolynomial<C>
  -> verlustfreie UnivariatePolynomialView<C>
  -> Ableitung / exakte Division / euklidischer ggT
  -> charakteristik-0-quadratfreie Zerlegung
  -> Faktoren mit Multiplizitäten
  -> exakte Rekonstruktion und Zertifikat
```

`SparsePolynomial` bleibt die kanonische mathematische Identität. Die neue
`UnivariatePolynomialView` ist eine dichte, aufsteigend indizierte
Koeffizientenansicht für Algorithmen:

```text
[a0, a1, ..., an]  <=>  a0 + a1*x + ... + an*x^n
```

Sie ist kein zweites öffentliches Polynommodell. Die Projektion ist verlustfrei,
bindet denselben `PolynomialRing` und kann jederzeit exakt in die Sparse-IR
zurückgeführt werden.

## Explizite Koeffizientendomäne

`CoefficientDomain` deklariert jetzt zusätzlich:

- die Charakteristik;
- die kanonische Einbettung ganzer Zahlen.

Diese Angaben werden für Ableitungen und spätere endliche Körper benötigt. Ein
Algorithmus darf nicht aus dem Java-Wertetyp erraten, ob er in
Charakteristik null oder in einem endlichen Körper arbeitet.

Die erste quadratfreie Implementierung verlangt ausdrücklich:

- genau eine Polynomvariable;
- eine exakte Koeffizientenfeld-Implementierung;
- Charakteristik null.

`Z[x]` wird nicht stillschweigend nach `Q[x]` angehoben. Eine spätere
Integer-Pipeline muss Inhalt und primitiven Anteil ausdrücklich behandeln und
ihre rationale beziehungsweise modulare Arbeitsdomäne in der Evidence binden.

## Request-weite Strukturgrenzen

`FactorizationRequest` bindet jetzt neben Kandidaten- und Arbeitsbudgets
verpflichtend:

- maximale Variablenzahl;
- maximalen Gesamtgrad;
- maximale Termzahl;
- maximale Koeffizientenbitlänge.

Die Grenzen werden vom unabhängigen Verifier geprüft, bevor eine Engine den
Ausdruck inspizieren darf. Eine Überschreitung führt zu
`BUDGET_INCONCLUSIVE`, nicht zu „nicht faktorisierbar“ oder „irreduzibel“.

Damit hängen Speicher- und Strukturkosten nicht mehr nur von einem
engine-internen, zurücksetzbaren Arbeitszähler ab.

## Allgemeine univariate Operationen

`UnivariatePolynomialView` implementiert exakt:

- Sparse-/Dense-Round-trip;
- Addition, Subtraktion und Skalierung;
- Multiplikation;
- formale Ableitung;
- Normierung auf ein monisches Polynom;
- Polynomdivision mit Quotient und Rest;
- exakt geprüften Quotienten.

Die euklidische ggT-Berechnung gibt einen monischen ggT zurück und verwendet
einen nicht zurücksetzbaren, nach Stufen getrennten Arbeitszähler. Bei
Budgeterschöpfung wird kein Teilresultat als mathematischer Abschluss
ausgegeben.

## Quadratfreie Zerlegung

Für ein nichtkonstantes Polynom über einem exakten Feld der Charakteristik null
wird eine Yun-artige Zerlegung ausgeführt. Beispielsweise wird

```text
(6/5) * (x - 1)^3 * (x + 2)^2 * (x^2 + 1)
```

in den skalaren Anteil `6/5` und die drei monischen Faktoren mit den
Multiplizitäten `3`, `2` und `1` zerlegt.

Nach der Bildung werden unabhängig geprüft:

1. das Produkt aus Einheit und potenzierten Faktoren rekonstruiert exakt das
   Quellpolynom;
2. jeder ausgegebene Faktor ist tatsächlich quadratfrei, also
   `gcd(f, f') = 1`.

Das Zertifikat bindet Methode, Ring, Quellpolynom, Strukturgrenzen,
Arbeitsbilanz, Einheit, Faktoren und Multiplizitäten.

## Aussagegrenze

Eine quadratfreie Zerlegung beweist keine Irreduzibilität. Insbesondere kann
`x^2 + 1` je nach Koeffizientendomäne irreduzibel oder weiter zerlegbar sein.
Diese Stufe meldet deshalb nur eine verifizierte quadratfreie Zerlegung.

Noch offen bleiben:

- Inhalt und primitiver Anteil für `Z[x]`;
- Faktorisierung über endlichen Körpern;
- geeignete Primzahlauswahl;
- Hensel-Lifting;
- Zassenhaus- beziehungsweise spätere LLL-/van-Hoeij-Rekombination;
- unabhängige Vollständigkeits- und Irreduzibilitätszertifikate;
- rationale Reassemblierung hinter dem allgemeinen Engine-Vertrag.

## Prüfung

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.polynomial.UnivariatePolynomialViewTest \
  --tests de.regelsuche.polynomial.UnivariatePolynomialAlgorithmsTest \
  --tests de.regelsuche.polynomial.SquareFreeDecompositionTest \
  --tests de.regelsuche.polynomial.FactorizationStructuralLimitsTest
```

Der vollständige Checkout-Vertrag bleibt:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```
