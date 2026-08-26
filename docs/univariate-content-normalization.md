# Univariate Inhalts- und Primitivteilnormalisierung

**Implementierungsstand: 25. August 2026**

Diese Stufe überführt einen typisierten Faktorisierungsrequest über `Z[x]` oder
`Q[x]` in eine gemeinsame kanonische Form für die modularen
Faktorisierungsalgorithmen. Sie faktorisiert den primitiven Teil nicht selbst.
Die anschließende deterministische Primzahlauswahl und vollständige
Faktorisierung einer quadratfreien Reduktion in `F_p[x]` sind inzwischen als
separate, request- und evidence-gebundene Stufen implementiert.

## Position in der Faktorisierungspipeline

```text
FactorizationRequest<BigInteger oder ExactRational>
  -> request-weite Source-Strukturgrenzen
  -> gemeinsame nicht zurücksetzbare Arbeitsbilanz
  -> Nenner-LCM und exakte Ganzzahligmachung
  -> ganzzahliger Koeffizienteninhalt
  -> Vorzeichenkanonisierung
  -> primitives SparsePolynomial<BigInteger>
  -> unabhängige Rekonstruktionsprüfung
  -> deterministische Evidence
  -> geeignete Primzahl und exakte Reduktion nach F_p[x]   implementiert
  -> vollständige quadratfreie Faktorisierung in F_p[x]   implementiert
  -> Hensel-Lifting                                       noch offen
  -> ganzzahlige Rekombination                            noch offen
  -> rationale Reassemblierung                            noch offen
```

Die Ausgabe ist für beide Quelldomänen gleich aufgebaut. Dadurch benötigen
Primzahlauswahl, Primkörperfaktorisierung, Hensel-Lifting und ganzzahlige
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
normalizeInteger(
    FactorizationRequest<BigInteger>,
    UnivariateContentPolicy)

normalizeRational(
    FactorizationRequest<ExactRational>,
    UnivariateContentPolicy)
```

Der bestehende `FactorizationRequest` bleibt damit die einzige öffentliche
Quelle für:

- das Quellpolynom;
- die geforderte Evidence-Autorität;
- request-weite Strukturgrenzen;
- Kandidatenbudget;
- request-weites Arbeitsbudget.

Es existiert keine zweite Normalisierungsrequest-Klasse mit duplizierten
Struktur- oder Arbeitsgrenzen. `UnivariateContentPolicy` ergänzt ausschließlich
die für diese Stufe neue Grenze für wachsende Zwischenkoeffizienten.

Es gibt auch keinen untypisierten Einstieg, der anhand eines Java-Werts errät,
in welcher mathematischen Domäne gerechnet wird.

`UnivariateContentResult` ist von außen nur lesbar. Öffentliche Aufrufer können
keinen erfolgreichen Status mit frei gewählten Koeffizienten erzeugen. Das
Zertifikat bindet:

- Methoden-ID und Quelldomäne;
- den vollständigen kanonischen `FactorizationRequest`;
- die Zwischenkoeffizienten-Policy;
- Status und Detailcode;
- vollständige stage-getrennte Arbeitsbilanz;
- Nennerbeseitigungsfaktor und ganzzahligen Inhalt;
- exakten Skalar;
- kanonischen primitiven Teil.

## Explizite Grenzen

Die Kostenkontrolle besteht aus dem bestehenden Faktorisierungsrequest und
einer eng begrenzten zusätzlichen Stufenpolitik.

### Strukturgrenzen der Quelle

`FactorizationRequest.StructuralLimits` begrenzt vor jeder arithmetischen
Verarbeitung:

- Variablenzahl;
- Gesamtgrad;
- Termzahl;
- Bitlänge der Quellkoeffizienten.

Eine Überschreitung übernimmt den konkreten Detailcode, beispielsweise
`MAX_TOTAL_DEGREE_EXCEEDED`.

Das Nullpolynom kann bereits keinen gültigen `FactorizationRequest` bilden. Ein
multivariater, aber innerhalb seiner Strukturgrenzen liegender Request wird von
der univariaten Stufe mit `UNSUPPORTED_SHAPE` zurückgewiesen.

### Bitgrenze für Zwischenkoeffizienten

Das Nenner-LCM kann wesentlich größer als jeder einzelne Nenner werden. Darum
enthält `UnivariateContentPolicy` verpflichtend
`maxIntermediateCoefficientBitLength`.

Der Algorithmus prüft eine sichere untere Bitlängenschranke vor einer
Multiplikation und anschließend die exakte Ergebnisbitlänge. Dadurch wird eine
bereits sicher zu große Ganzzahl nicht erst vollständig materialisiert.

Mögliche Detailcodes sind unter anderem:

- `DENOMINATOR_LCM_BIT_LENGTH_EXCEEDED`;
- `INTEGRAL_COEFFICIENT_BIT_LENGTH_EXCEEDED`;
- `PRIMITIVE_COEFFICIENT_BIT_LENGTH_EXCEEDED`.

### Nicht zurücksetzbares request-weites Arbeitsbudget

Alle GGT-, LCM-, Divisions-, Multiplikations-, Vorzeichen- und
Verifikationsschritte laufen über `PolynomialWorkBudget`. Der öffentliche
Einstieg initialisiert es exakt aus `FactorizationRequest.maxWorkUnits()`.

Für die vollständige Engine-Orchestrierung existiert zusätzlich ein
paketinterner Einstieg, der denselben bereits verwendeten
`PolynomialWorkBudget` an die Normalisierung weitergibt. Folgestufen können den
Zähler deshalb nicht zurücksetzen. Ein eigener Test führt zwei
Normalisierungen mit demselben Budget aus und weist nach, dass die zweite Stufe
am verbliebenen Restbudget scheitert.

Bei Erschöpfung lautet der Status `BUDGET_INCONCLUSIVE` mit
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

## Übergabe an die modulare Pipeline

Der kanonische primitive Teil ist der Eingabevertrag für
`SuitablePrimeSelection`. Diese Stufe:

- verlangt den hier erzeugten positiven primitiven `Z[x]`-Zustand;
- betrachtet eine vollständig gebundene Primzahlenfolge;
- bewahrt Gradverlust und nicht quadratfreie Reduktionen mit typisierten
  Gründen;
- reduziert exakt nach `PrimeField(p)[x]`;
- verwendet für die verschachtelte Primkörperfaktorisierung dasselbe nicht
  zurücksetzbare Arbeitsbudget;
- prüft, dass die retained modulare Quelle tatsächlich die Modulo-
  `p`-Reduktion der ursprünglichen ganzzahligen Quelle ist.

Die vollständige Primkörperfaktorisierung rekonstruiert das modulare Produkt,
prüft paarweise Koprimheit und zertifiziert jeden Faktor mit dem
Rabin-/Frobenius-Kriterium. Diese Evidence ist der Eingang für das noch offene
Hensel-Lifting; sie ist noch keine Faktorisierung in `Z[x]` oder `Q[x]`.

Siehe
[Deterministische Auswahl einer geeigneten Primzahl](suitable-prime-selection.md)
und
[Deterministische Faktorisierung über Primkörpern](finite-field-factorization.md).

## Status- und Claim-Grenze

| Status | Bedeutung |
| --- | --- |
| `COMPLETED` | Inhalt und primitiver Teil wurden exakt erzeugt und rekonstruiert. |
| `UNSUPPORTED_DOMAIN` | Die typisierte Methode passt nicht zur deklarierten Koeffizientendomäne. |
| `UNSUPPORTED_SHAPE` | Die Quelle liegt nicht in einem univariaten Polynomring. |
| `BUDGET_INCONCLUSIVE` | Eine Struktur-, Zwischenwert- oder Arbeitsgrenze wurde erreicht. |
| `TECHNICAL_FAILURE` | Eine interne exakte Invariante oder arithmetische Operation ist fehlgeschlagen. |

`COMPLETED` dieser Stufe bedeutet ausschließlich, dass die Normalisierung
korrekt ist. Es beweist weder Reduzibilität noch Irreduzibilität und enthält
selbst noch keine Faktorisierung. Die nachfolgenden modularen Stufen besitzen
eigene, engere Claim-Grenzen.

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

Nach der inzwischen implementierten geeigneten Primzahlauswahl und
Primkörperfaktorisierung verbleiben:

1. präzisionsgebundenes Hensel-Lifting;
2. ganzzahlige Faktorkombination, zunächst Zassenhaus;
3. später gegebenenfalls LLL-/van-Hoeij-Rekombination;
4. exakte rationale Reassemblierung;
5. unabhängige vollständige `Z[x]`-/`Q[x]`-Evidence hinter der
   Engine-/Verifier-Grenze.
