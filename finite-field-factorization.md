# Deterministische Faktorisierung über endlichen Primkörpern

**Implementierungsstand: 25. August 2026**

Diese Stufe faktorisiert ein quadratfreies univariates Polynom vollständig in
der ausdrücklich deklarierten Domäne `F_p[x]`. Sie ist der erste vollständige
Faktorisierungsschritt nach Inhaltsnormalisierung, exakter Polynomarithmetik und
quadratfreier Zerlegung. Sie wählt noch keine geeignete Primzahl für ein
Polynom aus `Z[x]` und hebt keine modularen Faktoren nach `Z[x]` zurück.

## Position in der Faktorisierungspipeline

```text
Z[x] oder Q[x]
  -> Inhalt und primitiver Teil
  -> quadratfreie Zerlegung
  -> geeignete Primzahl auswählen                 noch offen
  -> nach F_p[x] reduzieren
  -> deterministische Berlekamp-Faktorisierung    diese Stufe
  -> Hensel-Lifting                               noch offen
  -> ganzzahlige Faktorrekomposition              noch offen
  -> rationale Reassemblierung                    noch offen
  -> unabhängige Prüfung im ursprünglichen Ring   noch offen
```

Ein positives Resultat dieser Stufe ist vollständig **relativ zum gebundenen
Ring `F_p[x]`**. Es sagt noch nichts darüber aus, ob `p` für ein bestimmtes
ganzzahliges Polynom geeignet ist oder ob die modularen Faktoren bereits
Faktoren des ursprünglichen Polynoms sind.

## Expliziter Primkörper

`PrimeField` implementiert `ExactField<BigInteger>`. Die gespeicherten
`BigInteger`-Werte sind kanonische Residuen; ihre mathematische Identität wird
durch die Koeffizientendomäne bestimmt:

```text
regelsuche.coefficients.prime-field/v1/p=<prime>
```

Damit gehören dieselben Java-Zahlen in `F_5` und `F_7` zu verschiedenen
Polynomringen. Die Implementierung:

- akzeptiert positive Primzahlen im unterstützten `int`-Bereich;
- prüft die Primzahleigenschaft deterministisch;
- kanonisiert ganze Zahlen modulo `p`;
- implementiert Addition, Negation, Multiplikation und exakte Division;
- lehnt zusammengesetzte Moduli und Division durch null fail-closed ab;
- bindet `p` an Domain-ID, Ringidentität und Evidence.

Ein gewöhnliches `SparsePolynomial<BigInteger>` über `Z` wird nicht
stillschweigend als Primkörperpolynom interpretiert. Sein Ring muss tatsächlich
eine `PrimeField`-Domäne besitzen.

## Öffentlicher Vertrag

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

Die Stufenpolitik ergänzt nur zwei algorithmusspezifische Ressourcengrenzen:

```java
FiniteFieldFactorizationPolicy.deterministicBerlekamp(
    maxEnumeratedFieldElements,
    maxMatrixCells)
```

`maxEnumeratedFieldElements` begrenzt die deterministische Enumeration von
Körperresiduen beim Faktorsplitting. `maxMatrixCells` begrenzt den maximalen
dichten Speicherbedarf der Berlekamp-Stufe vor der ersten großen Allokation.
Sie duplizieren weder Source-Strukturgrenzen noch das Work-Budget.

## Vorbedingungen und Fehlersemantik

Die erste Implementierung verlangt:

- genau eine Polynomvariable;
- eine deklarierte `PrimeField`-Domäne;
- ein nichtkonstantes Quellpolynom;
- mindestens einen erlaubten Ergebniskandidaten;
- eine Primzahl innerhalb der Enumerationspolitik;
- einen Grad innerhalb der Matrixzellenpolitik;
- ein quadratfreies Polynom.

Die Quadratfreiheit wird nicht als fremde Behauptung übernommen. Nach monischer
Normierung berechnet der Algorithmus exakt

```text
gcd(f, f')
```

und setzt nur bei Ergebnis eins fort. Wiederholte Faktoren oder eine in
Charakteristik `p` verschwindende Ableitung führen zu `UNSUPPORTED_SHAPE` mit
`REQUIRES_SQUARE_FREE_INPUT`. Eine ausgeschöpfte Ressourcen- oder
Arbeitsgrenze führt zu `BUDGET_INCONCLUSIVE`, nicht zu einem mathematischen
Negativclaim.

## Deterministischer Berlekamp-Algorithmus

Die Algorithmus-ID lautet:

```text
regelsuche.berlekamp-factorization/v1
```

Für ein monisches quadratfreies Polynom `f` vom Grad `n` wird die Frobenius-
Abbildung

```text
g -> g^p mod f
```

in der Basis `1, x, ..., x^(n-1)` dargestellt. Die Spalten der Berlekamp-Matrix
sind

```text
x^(p*j) mod f,  j = 0, ..., n-1
```

anschließend wird die Einheitsmatrix abgezogen.

### Verifizierter Nullraum

`BerlekampKernel` berechnet den Nullraum von `Q - I` durch deterministische
Gauß-Jordan-Elimination im deklarierten Primkörper:

- Pivotspalten werden von links nach rechts gewählt;
- Pivotzeilen werden von oben nach unten gesucht;
- jede Division erfolgt im `PrimeField`;
- freie Spalten werden in aufsteigender Reihenfolge zu Basisvektoren;
- Basisvektoren werden nach kanonischem Polynommaterial geordnet.

Die unveränderte Ausgangsmatrix bleibt für eine getrennte Prüfung erhalten,
während eine Kopie in reduzierte Zeilenstufenform überführt wird. Jeder
Basisvektor wird anschließend gegen die Ausgangsmatrix multipliziert. Erst wenn
jede Zeile exakt null ergibt, wird die Kernel-Evidence ausgestellt.

Für ein quadratfreies Polynom ist die Nullität gleich der Zahl seiner
irreduziblen Faktoren in `F_p[x]`. Das Splitting muss genau diese Faktorzahl
erreichen.

### Deterministisches Splitting

Die nichtkonstanten Kernel-Basispolynome werden in kanonischer Reihenfolge
verwendet. Für jedes Basispolynom `b` werden die Residuen

```text
0, 1, ..., p - 1
```

untersucht. Ein aktueller Faktor `h` wird durch

```text
gcd(h, b - a)
```

geteilt, wenn der GGT weder eins noch `h` ist. Die Teilfaktoren werden monisch
normalisiert und erneut kanonisch geordnet.

Wird die verifizierte Nullität trotz vollständiger erlaubter Enumeration nicht
erreicht, ist dies ein `TECHNICAL_FAILURE`. Ein interner Algorithmusfehler wird
nicht in einen gewöhnlichen Nichttreffer umgedeutet.

## Unabhängige Abschlussprüfung

Die Berlekamp-Zerlegung autorisiert ihren Abschluss nicht selbst. Vor
`COMPLETED` wird zusätzlich geprüft:

1. Der ursprüngliche Leitkoeffizient ist als separate Feldeinheit erhalten.
2. Jeder ausgegebene Faktor ist monisch und nichtkonstant.
3. Einheit und Faktoren rekonstruieren das Quellpolynom exakt.
4. Je zwei verschiedene Faktoren sind teilerfremd.
5. Jeder Faktor besteht einen unabhängigen Rabin-/Frobenius-Test.
6. Die Faktorzahl entspricht der verifizierten Berlekamp-Nullität.

### Rabin-/Frobenius-Irreduzibilitätstest

Für einen monischen Faktor `g` vom Grad `m` wird geprüft:

```text
x^(p^m) = x mod g
```

und für jeden Primteiler `q` von `m`:

```text
gcd(g, x^(p^(m/q)) - x) = 1
```

Die Frobenius-Potenzen werden exakt und budgetiert modulo `g` berechnet. Jeder
Faktor erhält ein eigenes deterministisches Irreduzibilitätszertifikat.

## Work- und Speichergrenzen

Alle arithmetischen und linearen Algebra-Schritte verwenden dasselbe
`PolynomialWorkBudget`, das aus `FactorizationRequest.maxWorkUnits()` erzeugt
wird. Stabile Stufen-IDs umfassen unter anderem:

```text
finite-field.leading-normalization
finite-field.square-free.*
berlekamp.matrix.*
berlekamp.rref.*
berlekamp.nullspace.*
berlekamp.split.*
finite-field.verify.*
```

Das Kopieren der Matrix für die RREF-Arbeit wird vor jeder Zeilenkopie als
`berlekamp.rref.matrix-copy-cells` abgerechnet. Ein paketinterner Einstieg kann
dasselbe bereits belastete Budget an spätere Orchestrierungsschritte
weiterreichen. Eine abweichende Budgetautorität wird mit
`FINITE_FIELD_WORK_BUDGET_AUTHORITY_MISMATCH` zurückgewiesen.

Das Work-Budget begrenzt Rechenarbeit, aber nicht bereits materialisierten
Speicher. Daher wird der dichte Spitzenbedarf getrennt vorab beschränkt. Für
Grad `n` können gleichzeitig vorhanden sein:

1. die ursprüngliche `n x n`-Berlekamp-Matrix;
2. ihre `n x n`-RREF-Arbeitskopie;
3. bis zu `n x n` Koeffizientenzellen der Nullraumbasis.

Die Policy prüft deshalb ohne Überlauf:

```text
n * n <= maxMatrixCells / 3
```

Eine Ablehnung an der Enumerations- oder Matrixzellenpolitik erfolgt vor der
betroffenen Arbeit und besitzt daher null verbrauchte Work Units.

## Resultat und Evidence

`FiniteFieldFactorizationResult` besitzt keinen öffentlichen Konstruktor. Ein
positiver Zustand kann nur von der Algorithmusstufe ausgestellt werden. Das
abschließende Zertifikat bindet:

- die vollständige `FactorizationRequest`;
- die Algorithmus- und Ressourcenpolitik;
- die Primkörper-Domain-ID;
- die vollständige Arbeitsbilanz;
- Feldeinheit und kanonisch geordnete Faktoren;
- Berlekamp-Nullität und Kernel-Zertifikat;
- die Irreduzibilitätszertifikate aller Faktoren.

| Status | Bedeutung |
| --- | --- |
| `COMPLETED` | Vollständige, rekonstruierte und irreduzibel geprüfte Faktorisierung im gebundenen `F_p[x]`. |
| `UNSUPPORTED_DOMAIN` | Der Ring verwendet keine deklarierte `PrimeField`-Domäne. |
| `UNSUPPORTED_SHAPE` | Die Quelle ist nicht univariat, konstant oder nicht quadratfrei. |
| `BUDGET_INCONCLUSIVE` | Struktur-, Kandidaten-, Enumerations-, Matrixzellen- oder Work-Grenze wurde erreicht. |
| `TECHNICAL_FAILURE` | Eine Algorithmus-, Matrix-, Rekonstruktions- oder Evidence-Invariante ist verletzt. |

## Claim-Grenze

`COMPLETED` autorisiert genau den Claim:

> Das gebundene Polynom wurde im gebundenen Primkörper `F_p[x]` vollständig in
> irreduzible Faktoren zerlegt.

Nicht autorisiert sind:

- die Eignung von `p` für ein gegebenes ganzzahliges Polynom;
- die Faktorisierung des ursprünglichen `Z[x]`- oder `Q[x]`-Polynoms;
- Hensel-Lifting;
- Zassenhaus- oder LLL-/van-Hoeij-Rekombination;
- Faktorisierung über Erweiterungskörpern;
- multivariate Faktorisierung;
- eine allgemeine Überlegenheit gegenüber etablierten CAS.

## Prüfung

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.polynomial.PrimeFieldTest

./gradlew :regelsuche-math-algorithms:test \
  --tests de.regelsuche.math.algorithms.polynomial.FiniteFieldFactorizationTest

./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Nächster geschlossener Schritt

Als Nächstes folgt die nachvollziehbare Auswahl geeigneter Primzahlen für
primitive Polynome in `Z[x]`. Ihre Evidence muss mindestens binden:

- alle betrachteten Primzahlen in kanonischer Reihenfolge;
- Ablehnungen wegen verschwindendem Leitkoeffizienten;
- Ablehnungen wegen nicht-quadratfreier modularer Reduktion;
- verbrauchte Arbeit pro Kandidat;
- die ausgewählte Primzahl und die zugehörige modulare Faktorisierung.

Erst danach kann Hensel-Lifting auf einer nachweislich geeigneten modularen
Zerlegung aufbauen.
