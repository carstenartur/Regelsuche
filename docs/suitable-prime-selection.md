# Deterministische Auswahl einer geeigneten Primzahl

**Implementierungsstand: 25. August 2026**

Diese Stufe verbindet ein kanonisches primitives Polynom in `Z[x]` mit der
vollständigen Faktorisierung über einem ausdrücklich ausgewählten Primkörper.
Sie betrachtet Primzahlen in einer gebundenen, vollständig in der Policy
festgehaltenen Reihenfolge, bewahrt jeden Ablehnungsgrund und wählt die erste
Primzahl, für die der Grad erhalten bleibt und die modulare Reduktion
quadratfrei ist.

## Position in der Faktorisierungspipeline

```text
Z[x] oder Q[x]
  -> Inhalt und primitiver Teil
  -> quadratfreie Faktoren in Charakteristik null
  -> geeignete Primzahl mit Auditspur auswählen       diese Stufe
  -> vollständige Faktorisierung in F_p[x]             eingebunden
  -> Hensel-Lifting                                    noch offen
  -> ganzzahlige Faktorrekomposition                   noch offen
  -> rationale Reassemblierung                         noch offen
  -> unabhängige Vollständigkeitsprüfung in Q[x]        noch offen
```

Ein positiver Abschluss bedeutet noch nicht, dass die modularen Faktoren
bereits ganzzahlige Faktoren sind. Er stellt die nachvollziehbare modulare
Ausgangslage für das spätere Hensel-Lifting bereit.

## Eingabevertrag

Der öffentliche Einstieg lautet:

```java
SuitablePrimeSelection.selectAndFactor(
    FactorizationRequest<BigInteger> request,
    SuitablePrimeSelectionPolicy policy)
```

Die Quelle muss:

- im ausdrücklich deklarierten `BigIntegerDomain` liegen;
- genau eine Polynomvariable besitzen;
- nicht konstant sein;
- einen positiven Leitkoeffizienten besitzen;
- primitive ganzzahlige Koeffizienten mit GGT `1` besitzen;
- die request-weiten Strukturgrenzen einhalten.

Die Primitivität wird unabhängig nachgerechnet. Ein beliebiges ganzzahliges
Polynom wird nicht stillschweigend normalisiert; dafür bleibt die vorgelagerte
[Inhalts- und Primitivteilnormalisierung](univariate-content-normalization.md)
zuständig.

## Explizite Kandidatenfolge

`SuitablePrimeSelectionPolicy` bindet:

- die Algorithmus-ID;
- eine streng aufsteigende Liste tatsächlich zu betrachtender Primzahlen;
- die vollständige `FiniteFieldFactorizationPolicy` der modularen Folgestufe.

Eine Policy kann eine explizite Kandidatenliste verwenden oder einen
kanonischen Präfix aufsteigender Primzahlen erzeugen:

```java
SuitablePrimeSelectionPolicy.deterministicAscending(
    maximumPrime,
    maximumPrimes,
    finiteFieldPolicy)
```

Die erzeugte Liste selbst, nicht nur ihre Obergrenze, geht in die Evidence ein.
Damit hängt ein Lauf nicht von einer später geänderten Primzahlbibliothek oder
einer nicht dokumentierten Heuristik ab.

Die Kandidatenfolge ist auf 4096 Primzahlen begrenzt. Jeder Kandidat muss im von
der eingebetteten Primkörperfaktorisierung erlaubten Enumerationsbereich
liegen. Duplikate, fallende Folgen, zusammengesetzte Werte und widersprüchliche
Policies werden bereits beim Erzeugen der Policy abgelehnt.

## Auswahlregel

Für jede Primzahl `p` wird das Quellpolynom exakt nach `F_p[x]` reduziert. Die
Versuche bleiben in Policy-Reihenfolge erhalten.

### 1. Gradverlust

Teilt `p` den ganzzahligen Leitkoeffizienten, verschwindet der führende Term in
der modularen Reduktion. Die Primzahl wird mit

```text
LEADING_COEFFICIENT_VANISHES_MOD_PRIME
```

abgelehnt. Ein Hensel-Lifting darf nicht auf einer Reduktion aufbauen, deren
Grad bereits vom ganzzahligen Ausgangspolynom abweicht.

### 2. Nicht-quadratfreie Reduktion

Bei erhaltenem Grad wird dieselbe gebundene modulare Faktorisierungsstufe
aufgerufen. Sie prüft zunächst

```text
gcd(f mod p, (f mod p)') = 1.
```

Ist die Reduktion nicht quadratfrei, wird `p` mit

```text
MODULAR_REDUCTION_NOT_SQUARE_FREE
```

abgelehnt. Das vollständige Zertifikat des modularen Negativergebnisses bleibt
im Versuch gebunden.

### 3. Auswahl

Die erste Primzahl, deren Reduktion quadratfrei ist und deren vollständige
Primkörperfaktorisierung erfolgreich rekonstruiert und auf Irreduzibilität
geprüft wurde, erhält

```text
SUITABLE_PRIME_SELECTED
```

und beendet die Kampagne.

### Beispiel

Für

```text
f(x) = 2*x^2 + 2*x + 5
```

mit der Kandidatenfolge `2, 3, 5, 7` entsteht:

| Primzahl | Ergebnis | Grund |
| ---: | --- | --- |
| 2 | abgelehnt | Leitkoeffizient verschwindet |
| 3 | abgelehnt | `2*x^2 + 2*x + 2` ist nicht quadratfrei |
| 5 | ausgewählt | `2*x*(x + 1)` ist quadratfrei und vollständig faktorisiert |
| 7 | nicht mehr betrachtet | erster geeigneter Kandidat wurde bereits ausgewählt |

## Modulare Repräsentationsgrenze

Die Quellgrenze für Koeffizientenbitlängen beschreibt die ganzzahlige Eingabe.
Kanonische nichtnegative Residuen können mehr Bits benötigen als der Betrag
eines negativen Quellkoeffizienten. Beispielsweise wird `-1` in `F_5` als `4`
gespeichert.

Die interne modulare `FactorizationRequest` verwendet deshalb exakt

```text
max(sourceCoefficientBitLimit, bitLength(p - 1))
```

als Koeffizientengrenze. Diese abgeleitete Grenze erweitert nicht die erlaubte
ganzzahlige Quelle. Sie begrenzt ausschließlich die durch die bereits gebundene
Primzahl determinierte kanonische Residuenrepräsentation und wird durch das
Zertifikat der modularen Faktorisierung sichtbar.

## Gemeinsame Arbeitsautorität

Primzahlauswahl und Primkörperfaktorisierung teilen dasselbe
`PolynomialWorkBudget`, das aus `FactorizationRequest.maxWorkUnits()` stammt.
Die modulare Folgestufe erhält den bereits belasteten Zähler. Sie kann weder
verbrauchte Arbeit vergessen noch eine größere Autorität einsetzen.

Eine abweichende Budgetinstanz wird mit

```text
SUITABLE_PRIME_WORK_BUDGET_AUTHORITY_MISMATCH
```

als technischer Vertragsfehler zurückgewiesen.

Jeder `PrimeAttempt` speichert zusätzlich die in diesem Versuch verbrauchten
Work Units. Die Gesamtbilanz bleibt als vollständiges stage-getrenntes
`PolynomialWorkLedger` im Resultat erhalten.

Auch die unabhängige erneute Reduktion der ganzzahligen Quelle wird unter den
Stufen

```text
suitable-prime.verify.source-reduction.coefficients
suitable-prime.verify.source-reduction.comparisons
```

im selben Budget verbucht. Reicht das Restbudget nach einer erfolgreichen
Primkörperfaktorisierung nicht mehr für diese Korrespondenzprüfung, bleibt der
betroffene Primversuch als `TERMINAL_INCONCLUSIVE` mit
`SOURCE_CORRESPONDENCE_WORK_BUDGET_EXCEEDED` erhalten; das Gesamtergebnis
meldet `SOURCE_CORRESPONDENCE_INCONCLUSIVE`.

## Evidence

`SuitablePrimeSelectionResult` und seine `PrimeAttempt`-Objekte besitzen keine
öffentlichen Konstruktoren. Jeder Versuch bindet:

- Primzahl und Disposition;
- exakten Detailcode;
- Hash der kanonischen modularen Quelle;
- gegebenenfalls das Zertifikat der modularen Faktorisierung;
- Versuchskosten;
- ein eigenes SHA-256-Zertifikat.

Das abschließende Zertifikat bindet zusätzlich:

- den vollständigen ursprünglichen `FactorizationRequest`;
- die vollständige Auswahl- und Faktorisierungspolicy;
- sämtliche Versuche in Reihenfolge;
- die gesamte Arbeitsbilanz;
- die ausgewählte modulare Quelle;
- das vollständige Zertifikat der ausgewählten Primkörperfaktorisierung.

Das verschachtelte `FiniteFieldFactorizationResult` weist außerdem einen
issuer-eigenen `sourcePolynomialHash` aus. Der Quellausdruck war bereits über
den vollständigen `FactorizationRequest` in der v1-Zertifikats-ID gebunden;
die neue Projektion ändert diese Identität nicht. Ein abgeschlossener
`SuitablePrimeSelectionResult` verlangt, dass der exponierte Hash exakt mit dem
Hash der ausgewählten modularen Quelle übereinstimmt. Dadurch kann ein formal
gültiges Faktorisierungszertifikat für ein anderes Polynom desselben
Primkörpers nicht in das äußere Ergebnis eingesetzt werden.

Darüber hinaus rekonstruiert die Ergebnisprüfung aus der ursprünglichen
`Z[x]`-Quelle und der ausgewählten Primzahl nochmals unabhängig das kanonische
Polynom in `F_p[x]`. Dieses Polynom muss exakt der bewahrten modularen Quelle
entsprechen. Damit wird auch ein in sich konsistentes Paar aus Modularpolynom
und passendem Faktorisierungszertifikat abgelehnt, wenn es nicht tatsächlich
die Reduktion der gebundenen ganzzahligen Quelle ist.

## Terminale Ergebnisse

| Status | Bedeutung |
| --- | --- |
| `COMPLETED` | Eine gradtreue, quadratfreie Primzahl wurde ausgewählt und die modulare Quelle vollständig faktorisiert. |
| `UNSUPPORTED_DOMAIN` | Die Quelle liegt nicht im deklarierten ganzzahligen Koeffizientenring. |
| `UNSUPPORTED_SHAPE` | Die Quelle ist nicht univariat, konstant oder nicht kanonisch primitiv. |
| `BUDGET_INCONCLUSIVE` | Struktur-, Arbeits- oder modulare Ressourcen reichen nicht aus oder kein Kandidat der gebundenen Liste war geeignet. |
| `TECHNICAL_FAILURE` | Eine interne oder verschachtelte Vertragsinvariante wurde verletzt. |

Ein ausgeschöpfter Kandidatenvorrat meldet

```text
NO_SUITABLE_PRIME_WITHIN_POLICY
```

und ist kein Beweis, dass keine geeignete Primzahl existiert. Eine
Ressourcenüberschreitung der modularen Faktorisierung oder der anschließenden
Quellkorrespondenzprüfung ist terminal und wird nicht fälschlich als
mathematische Ablehnung der betreffenden Primzahl weitergezählt.

## Claim-Grenze

`COMPLETED` autorisiert genau:

> Für das gebundene primitive ganzzahlige Polynom wurde aus der gebundenen
> Kandidatenfolge die erste gradtreue Primzahl mit quadratfreier Reduktion
> gewählt; diese Reduktion wurde im gebundenen `F_p[x]` vollständig und
> irreduzibel faktorisiert.

Nicht autorisiert sind:

- eine bereits abgeschlossene Faktorisierung in `Z[x]` oder `Q[x]`;
- die Behauptung, jeder modulare Faktor hebe einzeln zu einem ganzzahligen
  Faktor;
- Hensel-Lifting oder eine ausreichende Liftpräzision;
- Zassenhaus-, LLL- oder van-Hoeij-Rekombination;
- unabhängige Vollständigkeitsevidence für das ursprüngliche rationale
  Polynom;
- multivariate Faktorisierung.

## Prüfung

Fokussierte Tests:

```bash
./gradlew :regelsuche-math-algorithms:test \
  --tests de.regelsuche.math.algorithms.polynomial.SuitablePrimeSelectionTest \
  --tests de.regelsuche.math.algorithms.polynomial.SuitablePrimeSelectionEvidenceTest
```

Vollständiger Checkout-Vertrag:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Nächste Stufe

Der ausgewählte Primkörper, die vollständig gebundene modulare Faktorisierung
und die erhaltene Gradidentität bilden den Eingang für ein explizit
präzisionsgebundenes Hensel-Lifting. Die nächste Stufe muss insbesondere die
Liftmoduli, Zwischengrößen, Faktorprodukte und den Übergang von `p^k` zu einer
für die ganzzahlige Rekonstruktion ausreichenden Schranke dokumentieren.
