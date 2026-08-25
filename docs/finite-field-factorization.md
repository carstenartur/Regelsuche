# Deterministische Faktorisierung über endlichen Primkörpern

**Implementierungsstand: 25. August 2026**

Diese Stufe faktorisiert ein quadratfreies univariates Polynom vollständig in
der ausdrücklich deklarierten Domäne `F_p[x]`. Sie ist der erste vollständige
Faktorisierungsschritt nach der Inhalts- und Primitivteilnormalisierung. Sie
wählt noch keine geeignete Primzahl für ein ganzzahliges Polynom und hebt keine
modularen Faktoren nach `Z[x]` zurück.

## Position in der Gesamtpipeline

```text
Z[x] oder Q[x]
  -> Inhalt und primitiver Teil
  -> quadratfreie Zerlegung
  -> geeignete Primzahl auswählen                 noch offen
  -> primitive Faktoren nach F_p[x] abbilden
  -> deterministische Berlekamp-Faktorisierung    diese Stufe
  -> Hensel-Lifting                               noch offen
  -> ganzzahlige Rekombination                    noch offen
  -> rationale Reassemblierung                    noch offen
  -> unabhängige Source-Verifikation              noch offen
```

Ein vollständiges Resultat dieser Seite ist vollständig relativ zum Ring
`F_p[x]`. Daraus folgt weder, dass die gewählte Primzahl für ein bestimmtes
`Z[x]`-Polynom geeignet ist, noch dass die modularen Faktoren bereits Faktoren
des ursprünglichen ganzzahligen Polynoms sind.

## Expliziter Primkörper

`PrimeField` implementiert `ExactField<BigInteger>`. Die Java-Werte sind
kanonische Residuen, aber ihre mathematische Identität wird durch die
Koeffizientendomäne bestimmt:

```text
regelsuche.coefficients.prime-field/v1/p=<prime>
```

Damit sind beispielsweise Polynome über `F_5` und `F_7` auch dann verschiedene
Ringelemente, wenn ihre gespeicherten Residuen dieselben `BigInteger`-Werte
besitzen.

Die erste Implementierung:

- akzeptiert positive Primzahlen im `int`-Bereich;
- prüft die Primzahleigenschaft deterministisch;
- kanonisiert jede ganze Zahl modulo `p`;
- implementiert exakte Addition, Negation, Multiplikation und Division;
- lehnt zusammengesetzte Moduli und Division durch null fail-closed ab;
- bindet `p` in Ringidentität und Evidence ein.

Es gibt keine stillschweigende Interpretation eines
`SparsePolynomial<BigInteger>` als Primkörperpolynom. Der Ring muss tatsächlich
eine `PrimeField`-Domäne besitzen.

## Öffentlicher Aufrufvertrag

Der Einstieg lautet:

```java
FiniteFieldFactorization.factorSquareFree(
    FactorizationRequest<BigInteger> request,
    FiniteFieldFactorizationPolicy policy)
```

`FactorizationRequest` bleibt die einzige öffentliche Quelle für:

- das kanonische Quellpolynom;
- die angeforderte Evidence-Stärke;
- Variablen-, Grad-, Term- und Quellkoeffizientengrenzen;
- das Kandidatenbudget;
- das nicht zurücksetzbare Gesamtarbeitsbudget.

Die Stufenpolitik ergänzt ausschließlich zwei algorithmusspezifische
Ressourcengrenzen:

```java
FiniteFieldFactorizationPolicy.deterministicBerlekamp(
    maxEnumeratedFieldElements,
    maxMatrixCells)
```

- `maxEnumeratedFieldElements` begrenzt die kanonische Residuenenumeration des
  Splitters;
- `maxMatrixCells` begrenzt die dichte `n x n`-Berlekamp-Matrix vor ihrer
  Allokation.

Die Matrixzellen-Grenze ist erforderlich, weil ein Work-Budget bereits
materialisierten Speicher nicht rückwirkend begrenzen kann. Der Algorithmus
prüft `n * n` mit `long`-Arithmetik, bevor ein Matrixarray erzeugt wird. Die
Policy darf weder Source-Strukturgrenzen noch das Gesamtarbeitsbudget
duplizieren oder erweitern.

## Vorbedingungen

Die erste Stufe verlangt:

- genau eine Polynomvariable;
- eine deklarierte `PrimeField`-Koeffizientendomäne;
- ein nichtkonstantes Quellpolynom;
- mindestens ein erlaubtes Faktorisierungsergebnis;
- eine Primzahl innerhalb der expliziten Enumerationspolitik;
- eine Berlekamp-Matrix innerhalb der expliziten Zellenpolitik;
- ein quadratfreies Polynom.

Die Quadratfreiheit wird nicht als Eingabeannahme übernommen. Nach monischer
Normierung berechnet der Algorithmus exakt

```text
gcd(f, f')
```

und fährt nur fort, wenn das Ergebnis eins ist. Ein wiederholter Faktor oder
eine in Charakteristik `p` verschwindende Ableitung führt zu
`UNSUPPORTED_SHAPE` mit `REQUIRES_SQUARE_FREE_INPUT`.

## Deterministischer Berlekamp-Algorithmus

Die erste Algorithmus-ID lautet:

```text
regelsuche.berlekamp-factorization/v1
```

Für ein monisches quadratfreies Polynom `f` vom Grad `n` wird die lineare
Abbildung

```text
g -> g^p mod f
```

in der Basis `1, x, ..., x^(n-1)` dargestellt. Die Berlekamp-Matrix enthält die
Spalten

```text
x^(p*j) mod f,  j = 0, ..., n-1
```

und anschließend wird die Einheitsmatrix abgezogen.

### Exakter Nullraum

`BerlekampKernel` berechnet den Nullraum von `Q - I` durch deterministische
Gauß-Jordan-Elimination im deklarierten Primkörper:

- Pivotspalten werden von links nach rechts gewählt;
- Pivotzeilen werden von oben nach unten gesucht;
- jede Division erfolgt durch `PrimeField.divide`;
- freie Spalten werden in aufsteigender Reihenfolge zu Basisvektoren;
- die Basis wird abschließend nach kanonischem Polynommaterial geordnet.

Jeder erzeugte Basisvektor wird unabhängig gegen die ursprüngliche Matrix
multipliziert. Erst wenn jede Zeile exakt null ergibt, wird eine
Kernel-Evidence ausgestellt.

Für ein quadratfreies Polynom ist die Nullität gleich der Zahl seiner
irreduziblen Faktoren über `F_p`. Das Faktorsplitting muss deshalb genau diese
Faktorzahl erreichen.

### Deterministisches Faktorsplitting

Die nichtkonstanten Kernel-Basispolynome werden in kanonischer Reihenfolge
verwendet. Für jedes Basispolynom `b` werden die Residuen

```text
0, 1, ..., p - 1
```

untersucht. Ein aktueller Faktor `h` wird durch

```text
gcd(h, b - a)
```

geteilt, wenn der GGT weder eins noch `h` ist. Beide Teilfaktoren werden monisch
normalisiert und erneut kanonisch sortiert.

Die Stufe endet erst, wenn die Zahl der Faktoren der verifizierten
Berlekamp-Nullität entspricht. Wird diese mathematische Invariante trotz
vollständiger erlaubter Enumeration nicht erreicht, ist das ein
`TECHNICAL_FAILURE`, kein gewöhnlicher Nichttreffer.

## Unabhängige Abschlussprüfung

Die Berlekamp-Zerlegung darf ihren eigenen Abschluss nicht allein autorisieren.
Vor `COMPLETED` werden zusätzlich geprüft:

1. Der ursprüngliche Leitkoeffizient ist als separate Einheit erhalten.
2. Jeder ausgegebene Faktor ist monisch und nichtkonstant.
3. Einheit und Faktoren rekonstruieren das Quellpolynom exakt.
4. Je zwei verschiedene Faktoren sind teilerfremd.
5. Jeder Faktor besteht den Rabin-/Frobenius-Irreduzibilitätstest.
6. Die Faktorzahl entspricht der Berlekamp-Nullität.

### Rabin-/Frobenius-Test

Für einen monischen Faktor `g` vom Grad `m` wird geprüft:

```text
x^(p^m) = x mod g
```

und für jeden Primteiler `q` von `m`:

```text
gcd(g, x^(p^(m/q)) - x) = 1
```

Die benötigten Frobenius-Potenzen werden exakt und budgetiert modulo `g`
berechnet. Jeder Faktor erhält ein eigenes deterministisches
Irreduzibilitätszertifikat.

## Work Accounting

Alle Stufen verwenden dasselbe `PolynomialWorkBudget`, das aus
`FactorizationRequest.maxWorkUnits()` erzeugt wird:

```text
finite-field.leading-normalization
finite-field.square-free.*
berlekamp.matrix.*
berlekamp.rref.*
berlekamp.nullspace.*
berlekamp.split.*
finite-field.verify.*
```

Ein paketinterner Einstieg akzeptiert für spätere Orchestrierung dasselbe
bereits belastete Budget. Eine abweichende Budgetautorität wird mit
`FINITE_FIELD_WORK_BUDGET_AUTHORITY_MISMATCH` zurückgewiesen. Ein Folgeschritt
kann verbrauchte Arbeit daher weder vergessen noch das Requestbudget erhöhen.

Speicher- und Enumerationsgrenzen werden vor der jeweiligen Arbeit geprüft.
Sie erscheinen deshalb mit null verbrauchten Work Units, wenn die Quelle bereits
an der Policy-Grenze abgelehnt wird.

## Statussemantik

| Status | Bedeutung |
| --- | --- |
| `COMPLETED` | Vollständige, rekonstruierte und irreduzibel geprüfte Faktorisierung im deklarierten `F_p[x]`. |
| `UNSUPPORTED_DOMAIN` | Der Ring verwendet keine deklarierte `PrimeField`-Domäne. |
| `UNSUPPORTED_SHAPE` | Die Quelle ist nicht univariat, konstant oder nicht quadratfrei. |
| `BUDGET_INCONCLUSIVE` | Struktur-, Kandidaten-, Feldenumerations-, Matrixzellen- oder Gesamtarbeitsgrenze wurde erreicht. |
| `TECHNICAL_FAILURE` | Eine Algorithmus-, Matrix-, Rekonstruktions- oder Zertifikatsinvariante ist verletzt. |

`FiniteFieldFactorizationResult` besitzt keinen öffentlichen Konstruktor.
Request, Policy, Quelldomäne, Arbeitsbilanz, Einheit, Faktoren,
Kernel-Zertifikat und Irreduzibilitätszertifikate werden in das abschließende
SHA-256-Zertifikat gebunden.

## Claim-Grenze

`COMPLETED` autorisiert genau den Claim:

> Das gebundene Polynom wurde im gebundenen Primkörper `F_p[x]` vollständig in
> irreduzible Faktoren zerlegt.

Nicht autorisiert sind:

- Eignung der Primzahl für ein gegebenes ganzzahliges Polynom;
- Faktorisierung des ursprünglichen `Z[x]`- oder `Q[x]`-Polynoms;
- Hensel-Lifting;
- Zassenhaus- oder LLL-/van-Hoeij-Rekombination;
- allgemeine Faktorisierung über Erweiterungskörpern;
- multivariate Faktorisierung;
- eine allgemeine Überlegenheit gegenüber etablierten CAS.

## Prüfung

Fokussierte Tests:

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.polynomial.PrimeFieldTest

./gradlew :regelsuche-math-algorithms:test \
  --tests de.regelsuche.math.algorithms.polynomial.FiniteFieldFactorizationTest
```

Vollständiger Checkout-Vertrag:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Nächste Stufe

Der nächste geschlossene Produktionsschritt ist eine deterministische oder
seed-gebundene Auswahl geeigneter Primzahlen für primitive Polynome in `Z[x]`.
Sie muss mindestens dokumentieren:

- betrachtete Primzahlen in kanonischer Reihenfolge;
- Ablehnungen wegen verschwindendem Leitkoeffizienten;
- Ablehnungen wegen nicht-quadratfreier modularer Reduktion;
- verbrauchte Arbeit pro Kandidat;
- die ausgewählte Primzahl und die gebundene modulare Faktorisierung.

Erst danach kann Hensel-Lifting auf einer nachvollziehbar geeigneten modularen
Zerlegung aufbauen.
