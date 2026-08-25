# Univariate Inhalts- und Primitivteilnormalisierung

**Implementierungsstand: 25. August 2026**

Diese Stufe überführt ein exaktes univariates Polynom aus `Z[x]` oder `Q[x]`
in eine gemeinsame kanonische Form für die späteren modularen
Faktorisierungsalgorithmen. Sie faktorisiert das Polynom noch nicht.

## Position in der Faktorisierungspipeline

```text
SparsePolynomial<BigInteger> oder SparsePolynomial<ExactRational>
  -> Source-Strukturgrenzen
  -> Nenner-LCM und exakte Ganzzahligmachung
  -> ganzzahliger Koeffizienteninhalt
  -> Vorzeichenkanonisierung
  -> primitives SparsePolynomial<BigInteger>
  -> unabhängige Rekonstruktionsprüfung
  -> deterministische Evidence
  -> spätere Faktorisierung über endlichen Körpern
```

Die Ausgabe ist für beide Quelldomänen gleich aufgebaut. Dadurch benötigen
Primzahlauswahl, endliche Körper, Hensel-Lifting und ganzzahlige
Faktorkombination keinen zweiten rationalen Polynomtyp.

## Kanonische Konvention

Für jedes unterstützte von null verschiedene Quellpolynom `f` erzeugt der
Algorithmus exakt

```text
f = scalar * primitivePart
```

mit folgenden Bedingungen:

- `scalar` ist eine von null verschiedene `ExactRational`-Zahl;
- `primitivePart` liegt in `Z[x]`;
- der größte gemeinsame Teiler aller Koeffizienten von `primitivePart` ist `1`;
- der Leitkoeffizient von `primitivePart` ist positiv;
- das Vorzeichen des ursprünglichen Leitkoeffizienten liegt vollständig in
  `scalar`;
- Variablenreihenfolge und Monomordnung bleiben erhalten.

Zusätzlich werden zwei positive ganze Zahlen aufbewahrt:

- `denominatorClearingFactor`: das kleinste gemeinsame Vielfache der
  Koeffizientennenner;
- `integerContent`: der positive größte gemeinsame Teiler der nach dem
  Nennerbeseitigen entstandenen ganzzahligen Koeffizienten.

Der Betrag von `scalar` ist damit exakt
`integerContent / denominatorClearingFactor`. Für Quellen aus `Z[x]` ist der
Nennerbeseitigungsfaktor immer `1`.

### Beispiel über `Z[x]`

```text
-6*x^3 + 12*x - 18
  = -6 * (x^3 - 2*x + 3)
```

Die Ausgabe enthält:

```text
scalar                    = -6
denominatorClearingFactor = 1
integerContent            = 6
primitivePart             = x^3 - 2*x + 3
```

### Beispiel über `Q[x]`

```text
(-4/15)*x^2 + (2/3)*x - 2/5
  = (-2/15) * (2*x^2 - 5*x + 3)
```

Die Ausgabe enthält:

```text
scalar                    = -2/15
denominatorClearingFactor = 15
integerContent            = 2
primitivePart             = 2*x^2 - 5*x + 3
```

## Öffentliche Verträge

`UnivariateContentNormalization` besitzt zwei typisierte Einstiegspunkte:

```java
normalizeInteger(SparsePolynomial<BigInteger>, UnivariateContentRequest)
normalizeRational(SparsePolynomial<ExactRational>, UnivariateContentRequest)
```

Es gibt keinen untypisierten Einstieg, der anhand eines Java-Werts errät, in
welcher mathematischen Domäne gerechnet wird.

`UnivariateContentResult` ist von außen nur lesbar. Öffentliche Aufrufer können
keinen erfolgreichen Status mit frei gewählten Koeffizienten erzeugen. Das
Zertifikat bindet:

- Methoden-ID und Quelldomäne;
- kanonisches Quellpolynom;
- sämtliche Struktur-, Zwischenwert- und Arbeitsgrenzen;
- Status und Detailcode;
- vollständige stage-getrennte Arbeitsbilanz;
- Nennerbeseitigungsfaktor und ganzzahligen Inhalt;
- exakten Skalar;
- kanonischen primitiven Teil.

## Explizite Grenzen

`UnivariateContentRequest` trennt drei Kostenarten.

### Strukturgrenzen der Quelle

Die bestehenden `FactorizationRequest.StructuralLimits` begrenzen vor jeder
arithmetischen Verarbeitung:

- Variablenzahl;
- Gesamtgrad;
- Termzahl;
- Bitlänge der Quellkoeffizienten.

Eine Überschreitung übernimmt den konkreten Detailcode, beispielsweise
`MAX_TOTAL_DEGREE_EXCEEDED`.

### Bitgrenze für Zwischenkoeffizienten

Das Nenner-LCM kann wesentlich größer als jeder einzelne Nenner werden. Darum
ist `maxIntermediateCoefficientBitLength` eine eigene verpflichtende Grenze.
Der Algorithmus prüft eine sichere untere Bitlängenschranke vor einer
Multiplikation und anschließend die exakte Ergebnisbitlänge. Dadurch wird eine
bereits sicher zu große Ganzzahl nicht erst vollständig materialisiert.

Mögliche Detailcodes sind unter anderem:

- `DENOMINATOR_LCM_BIT_LENGTH_EXCEEDED`;
- `INTEGRAL_COEFFICIENT_BIT_LENGTH_EXCEEDED`;
- `PRIMITIVE_COEFFICIENT_BIT_LENGTH_EXCEEDED`.

### Nicht zurücksetzbares Arbeitsbudget

Alle GGT-, LCM-, Divisions-, Multiplikations-, Vorzeichen- und
Verifikationsschritte laufen über ein gemeinsames `PolynomialWorkBudget`.
Unteraufrufe können den Zähler nicht zurücksetzen. Bei Erschöpfung lautet der
Status `BUDGET_INCONCLUSIVE` mit
`CONTENT_NORMALIZATION_WORK_BUDGET_EXCEEDED`.

## Unabhängige Prüfung

Ein erfolgreicher Abschluss wird erst ausgegeben, nachdem der Algorithmus
zusätzlich geprüft hat:

1. Der Koeffizienten-GGT des primitiven Teils ist exakt `1`.
2. Sein Leitkoeffizient ist positiv.
3. Seine Monomunterstützung ist identisch zur ganzzahlig gemachten Quelle.
4. `signedContent * primitivePart` rekonstruiert jeden ganzzahligen
   Zwischenkoeffizienten.
5. `scalar * primitivePart` rekonstruiert jeden Koeffizienten des ursprünglichen
   `Z[x]`- oder `Q[x]`-Polynoms.

Ein Fehler in diesen Invarianten wird nicht als mathematisches Negativergebnis,
sondern als `TECHNICAL_FAILURE` ausgegeben.

## Status- und Claim-Grenze

| Status | Bedeutung |
| --- | --- |
| `COMPLETED` | Inhalt und primitiver Teil wurden exakt erzeugt und rekonstruiert. |
| `UNSUPPORTED_DOMAIN` | Die aufgerufene typisierte Methode passt nicht zur deklarierten Koeffizientendomäne. |
| `UNSUPPORTED_SHAPE` | Die Quelle ist nicht univariat oder ist das Nullpolynom. |
| `BUDGET_INCONCLUSIVE` | Eine Struktur-, Zwischenwert- oder Arbeitsgrenze wurde erreicht. |
| `TECHNICAL_FAILURE` | Eine interne exakte Invariante oder arithmetische Operation ist fehlgeschlagen. |

`COMPLETED` bedeutet ausschließlich, dass die Normalisierung korrekt ist. Es
beweist weder Reduzibilität noch Irreduzibilität und enthält noch keine
vollständige Faktorisierung.

## Prüfung

Die fokussierten Tests laufen mit:

```bash
./gradlew :regelsuche-math-algorithms:test \
  --tests de.regelsuche.math.algorithms.polynomial.UnivariateContentNormalizationTest
```

Der vollständige Checkout-Vertrag bleibt:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Nächste algorithmische Stufe

Der kanonische primitive Teil ist die Eingabe für:

1. deterministische oder seed-gebundene Faktorisierung über endlichen Körpern;
2. Auswahl geeigneter Primzahlen mit dokumentierten Ablehnungsgründen;
3. Hensel-Lifting;
4. ganzzahlige Faktorkombination;
5. exakte rationale Reassemblierung hinter der Engine-/Verifier-Grenze.
