# Univariate Polynomgrundlage, Inhalt und quadratfreie Zerlegung

**Implementierungsstand: 25. August 2026**

Diese Stufe erweitert den domänenbewussten Polynomkern um allgemeine
univariate Algorithmen, die nicht an eine Quartikschablone gebunden sind. Sie
stellt jetzt die gemeinsame Darstellung, Inhaltsnormalisierung, exakte
Polynomarithmetik, den euklidischen Polynom-GGT und die quadratfreie Zerlegung
bereit. Eine vollständige Zerlegung in irreduzible Faktoren ist noch nicht
implementiert.

## Verantwortungs- und Modulgrenze

Die Implementierung folgt der bestehenden Modularchitektur und erzeugt keine
Rückabhängigkeit:

```text
regelsuche-core
  CoefficientDomain / ExactField
  PolynomialRing / SparsePolynomial
  UnivariatePolynomialView
  PolynomialWorkSink

        ↓

regelsuche-math-algorithms
  nicht zurücksetzbares Arbeitsbudget
  Inhalt und primitiver Teil für Z[x] und Q[x]
  euklidischer Polynom-GGT
  quadratfreie Zerlegung
  algorithmische Evidence
```

`regelsuche-core` besitzt die exakten mathematischen Werte, ihre verlustfreien
Darstellungen und einen minimalen Work-Sink. Allgemeine Lösungsverfahren und
ihre Ablauf-Evidence liegen in `regelsuche-math-algorithms`.

Es gibt weder Split-Packages noch delegierende Kompatibilitätsklassen an alten
Paketorten. Die Algorithmen bauen auf dem gemeinsamen kanonischen
`SparsePolynomial` auf.

## Repräsentations- und Faktorisierungsfluss

```text
SparsePolynomial<BigInteger oder ExactRational>
  -> request-weite Strukturgrenzen
  -> verlustfreie UnivariatePolynomialView
  -> kanonischer Skalar + primitiver Teil in Z[x]
  -> Ableitung / exakte Division / euklidischer GGT
  -> charakteristik-0-quadratfreie Zerlegung
  -> spätere Faktorisierung über endlichen Körpern
  -> Hensel-Lifting und ganzzahlige Rekombination
  -> exakte Rekonstruktion und Verifier-Evidence
```

`SparsePolynomial` bleibt die kanonische mathematische Identität. Die
`UnivariatePolynomialView` ist eine dichte, aufsteigend indizierte
Koeffizientenansicht für Algorithmen:

```text
[a0, a1, ..., an]  <=>  a0 + a1*x + ... + an*x^n
```

Sie ist kein zweites unabhängiges Polynommodell. Die Projektion bindet denselben
`PolynomialRing` und kann exakt in die Sparse-IR zurückgeführt werden.

## Explizite Koeffizientendomäne

`CoefficientDomain` deklariert:

- eine stabile Domänen-ID;
- die Charakteristik;
- die kanonische Einbettung ganzer Zahlen;
- exakte Arithmetik und kanonische Textdarstellung.

Ableitungs-, Feld- und spätere endliche-Körper-Algorithmen dürfen diese
Eigenschaften nicht aus einem Java-Wertetyp erraten.

Die gegenwärtige quadratfreie Implementierung verlangt:

- genau eine Polynomvariable;
- eine exakte Koeffizientenfeld-Implementierung;
- Charakteristik null.

`Z[x]` wird nicht stillschweigend nach `Q[x]` angehoben. Stattdessen erzeugt die
Inhaltsnormalisierung für beide Quelldomänen eine explizite gemeinsame
ganzzahlige Arbeitsform.

## Request-weite Strukturgrenzen

`FactorizationRequest` bindet verpflichtend:

- maximale Variablenzahl;
- maximalen Gesamtgrad;
- maximale Termzahl;
- maximale Koeffizientenbitlänge;
- Kandidaten- und Arbeitsbudgets.

Der unabhängige Verifier prüft die Strukturgrenzen, bevor eine Engine den
Ausdruck inspizieren darf. Eine Überschreitung führt zu
`BUDGET_INCONCLUSIVE`, nicht zu „nicht faktorisierbar“ oder „irreduzibel“.

Algorithmen mit möglichem Zwischenwertwachstum ergänzen diese Quellgrenzen um
eine eigene verpflichtende Zwischenkoeffizienten-Bitgrenze. Sie ist nicht als
versteckter Faktor aus dem Quellbudget abgeleitet.

## Inhalts- und Primitivteilnormalisierung

`UnivariateContentNormalization` verarbeitet typisiert entweder ein Polynom in
`Z[x]` oder ein Polynom in `Q[x]`. Beide Pfade erzeugen:

```text
source = scalar * primitivePart
```

Dabei gilt:

- `scalar` ist eine exakte von null verschiedene rationale Zahl;
- `primitivePart` liegt in `Z[x]`;
- der Koeffizienten-GGT von `primitivePart` ist `1`;
- sein Leitkoeffizient ist positiv;
- Variablenreihenfolge und Monomordnung bleiben unverändert.

Für rationale Quellen werden zunächst die Nenner durch ihr kleinstes
gemeinsames Vielfaches beseitigt. Der positive ganzzahlige Inhalt wird danach
aus den ganzzahligen Koeffizienten extrahiert. Das Vorzeichen des
Leitkoeffizienten wird in den Skalar verschoben.

Vor einem positiven Abschluss prüft der Algorithmus unabhängig:

1. den GGT der primitiven Koeffizienten;
2. den positiven Leitkoeffizienten;
3. die unveränderte Monomunterstützung;
4. die Rekonstruktion der ganzzahligen Zwischenform;
5. die exakte Rekonstruktion des ursprünglichen `Z[x]`- oder
   `Q[x]`-Polynoms.

Die vollständige Konvention, Budgetsemantik und Beispiele stehen unter
[Univariate Inhalts- und Primitivteilnormalisierung](univariate-content-normalization.md).

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
Budget- oder Evidence-Implementierung.

## Euklidischer Polynom-GGT

Die allgemeine GGT-Berechnung arbeitet über einem deklarierten exakten Feld,
gibt einen monischen GGT zurück und behält eine deterministische Arbeitsbilanz
sowie ein content-adressiertes Zertifikat.

Besondere Ausgänge bleiben getrennt:

- Ring- oder Formabweichung: `UNSUPPORTED_SHAPE`;
- Koeffizientendomäne ohne exakte Felddivision: `UNSUPPORTED_DOMAIN`;
- erschöpftes Arbeitsbudget: `BUDGET_INCONCLUSIVE`;
- `gcd(0, 0)`: ausdrücklich undefiniert.

Bei Budgeterschöpfung wird kein Teilresultat als mathematischer Abschluss
ausgegeben.

## Quadratfreie Zerlegung

Für ein nichtkonstantes Polynom über einem exakten Feld der Charakteristik null
wird eine Yun-artige Zerlegung ausgeführt. Beispielsweise wird

```text
(6/5) * (x - 1)^3 * (x + 2)^2 * (x^2 + 1)
```

in den skalaren Anteil `6/5` und drei monische Faktoren mit den
Multiplizitäten `3`, `2` und `1` zerlegt.

Nach der Bildung werden unabhängig geprüft:

1. Einheit und potenzierte Faktoren rekonstruieren exakt das Quellpolynom.
2. Für jeden ausgegebenen Faktor gilt `gcd(f, f') = 1`.

Das Zertifikat bindet Methode, Ring, Quelle, Strukturgrenzen, Arbeitsbilanz,
Einheit, Faktoren und Multiplizitäten. Öffentliche Aufrufer können keinen
positiven Algorithmusabschluss mit fremden Faktoren konstruieren.

## Aussagegrenze

Eine Inhaltsnormalisierung beweist keine Reduzibilität. Eine quadratfreie
Zerlegung beweist keine Irreduzibilität. Insbesondere kann `x^2 + 1` abhängig
von der Koeffizientendomäne irreduzibel oder weiter zerlegbar sein.

Noch offen bleiben:

- Faktorisierung über endlichen Körpern;
- geeignete Primzahlauswahl mit dokumentierten Ablehnungsgründen;
- Hensel-Lifting;
- Zassenhaus- und später gegebenenfalls LLL-/van-Hoeij-Rekombination;
- rationale Faktorreassemblierung;
- unabhängige Vollständigkeits- und Irreduzibilitätszertifikate.

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
  --tests de.regelsuche.math.algorithms.polynomial.UnivariateContentNormalizationTest \
  --tests de.regelsuche.math.algorithms.polynomial.UnivariatePolynomialAlgorithmsTest \
  --tests de.regelsuche.math.algorithms.polynomial.SquareFreeDecompositionTest
```

Der vollständige Checkout-Vertrag bleibt:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```
