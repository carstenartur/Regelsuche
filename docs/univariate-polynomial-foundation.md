# Univariate Polynomgrundlage und quadratfreie Zerlegung

**Implementierungsstand: 25. August 2026**

Diese Stufe erweitert den domänenbewussten Polynomkern um die ersten allgemeinen
Algorithmen, die nicht an eine Quartikschablone gebunden sind. Sie faktorisiert
noch nicht vollständig in irreduzible Faktoren. Sie stellt aber die
algorithmische Grundlage bereit, auf der die modulare Faktorisierung über
`Z[x]` und `Q[x]` aufbauen kann.

## Verantwortungs- und Modulgrenze

Die Implementierung folgt der bestehenden Modularchitektur und erzeugt keine
neue Rückabhängigkeit:

```text
regelsuche-core
  CoefficientDomain / ExactField
  PolynomialRing / SparsePolynomial
  UnivariatePolynomialView
  PolynomialWorkSink

        ↓

regelsuche-math-algorithms
  nicht zurücksetzbares Arbeitsbudget
  euklidischer Polynom-ggT
  quadratfreie Zerlegung
  algorithmische Evidence
```

`regelsuche-core` besitzt damit ausschließlich die exakten mathematischen
Werte, ihre verlustfreien Darstellungen und einen minimalen Work-Sink.
Allgemeine Lösungsverfahren und ihre Ablauf-Evidence liegen in
`regelsuche-math-algorithms`, dessen deklarierte Verantwortung reine
mathematische Algorithmen und interne Referenzverfahren ist.

Es gibt weder ein Split-Package noch delegierende Kompatibilitätsklassen am
alten Ort. Der erste Zwischenstand im Core wurde vollständig verschoben, bevor
er zu einer dauerhaften falschen Abstraktionsgrenze werden konnte.

## Repräsentationsfluss

```text
SparsePolynomial<C>
  -> verlustfreie UnivariatePolynomialView<C>
  -> Ableitung / exakte Division / euklidischer ggT
  -> charakteristik-0-quadratfreie Zerlegung
  -> Faktoren mit Multiplizitäten
  -> exakte Rekonstruktion und Zertifikat
```

`SparsePolynomial` bleibt die kanonische mathematische Identität. Die
`UnivariatePolynomialView` ist eine dichte, aufsteigend indizierte
Koeffizientenansicht für Algorithmen:

```text
[a0, a1, ..., an]  <=>  a0 + a1*x + ... + an*x^n
```

Sie ist kein zweites unabhängiges Polynommodell. Die Projektion ist verlustfrei,
bindet denselben `PolynomialRing` und kann jederzeit exakt in die Sparse-IR
zurückgeführt werden.

## Explizite Koeffizientendomäne

`CoefficientDomain` deklariert zusätzlich:

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

`FactorizationRequest` bindet neben Kandidaten- und Arbeitsbudgets
verpflichtend:

- maximale Variablenzahl;
- maximalen Gesamtgrad;
- maximale Termzahl;
- maximale Koeffizientenbitlänge.

Die Grenzen werden vom unabhängigen Verifier geprüft, bevor eine Engine den
Ausdruck inspizieren darf. Eine Überschreitung führt zu
`BUDGET_INCONCLUSIVE`, nicht zu „nicht faktorisierbar“ oder „irreduzibel“.

Damit hängen Speicher- und Strukturkosten nicht nur von einem engine-internen,
zurücksetzbaren Arbeitszähler ab. Die frühere Factory ohne explizite
Strukturgrenzen wird nicht als Kompatibilitätsoberfläche weitergeführt.

## Allgemeine univariate Operationen

`UnivariatePolynomialView` implementiert exakt:

- Sparse-/Dense-Round-trip;
- Addition, Subtraktion und Skalierung;
- Multiplikation;
- formale Ableitung;
- Normierung auf ein monisches Polynom;
- Polynomdivision mit Quotient und Rest;
- exakt geprüften Quotienten.

Die öffentlichen Operationen können ohne Work-Erfassung verwendet werden oder
einen `PolynomialWorkSink` erhalten. Der Core kennt dadurch keine konkrete
Budget- oder Evidence-Implementierung. `regelsuche-math-algorithms` stellt den
nicht zurücksetzbaren, nach Stufen getrennten Arbeitszähler bereit.

## Euklidischer Polynom-ggT

Die allgemeine ggT-Berechnung arbeitet über einem deklarierten exakten Feld,
gibt einen monischen ggT zurück und behält eine deterministische Arbeitsbilanz
sowie ein content-adressiertes Zertifikat.

Besondere Ausgänge bleiben fachlich getrennt:

- Ring- oder Formabweichung: `UNSUPPORTED_SHAPE`;
- Koeffizientendomäne ohne exakte Felddivision: `UNSUPPORTED_DOMAIN`;
- erschöpftes Arbeitsbudget: `BUDGET_INCONCLUSIVE`;
- `gcd(0, 0)`: ausdrücklich undefiniert, nicht das Nullpolynom als Erfolg.

Bei Budgeterschöpfung wird kein Teilresultat als mathematischer Abschluss
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

1. Das Produkt aus Einheit und potenzierten Faktoren rekonstruiert exakt das
   Quellpolynom.
2. Jeder ausgegebene Faktor ist tatsächlich quadratfrei, also
   `gcd(f, f') = 1`.

Das Zertifikat bindet Methode, Ring, Quellpolynom, Strukturgrenzen,
Arbeitsbilanz, Einheit, Faktoren und Multiplizitäten. Die Resultatklasse ist
issuer-owned; Aufrufer können keinen positiven Abschluss um fremde Faktoren
konstruieren.

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

Core-Repräsentation und Verifier-Grenze:

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.polynomial.UnivariatePolynomialViewTest \
  --tests de.regelsuche.polynomial.FactorizationStructuralLimitsTest
```

Allgemeine mathematische Algorithmen:

```bash
./gradlew :regelsuche-math-algorithms:test \
  --tests de.regelsuche.math.algorithms.polynomial.UnivariatePolynomialAlgorithmsTest \
  --tests de.regelsuche.math.algorithms.polynomial.SquareFreeDecompositionTest
```

Der vollständige Checkout-Vertrag bleibt:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```
