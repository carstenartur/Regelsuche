# Deterministisches multifaktorielles Hensel-Lifting

**Implementierungsstand: 28. August 2026**

Diese Stufe hebt die bereits vollständig geprüfte Faktorisierung eines
primitiven ganzzahligen Polynoms modulo einer ausgewählten Primzahl `p`
deterministisch von `p` auf ein ausdrücklich gebundenes Zielmodul `p^k`.
Sie liefert für sich allein noch keine ganzzahlige Faktorisierung: Die
gehobenen Faktoren rekonstruieren die Quelle nur modulo `p^k` und bilden den
Eingang für die nachgelagerte, inzwischen implementierte
Zassenhaus-Rekombination.

## Position in der Faktorisierungspipeline

```text
Z[x] oder Q[x]
  -> Inhalt und primitiver Teil
  -> quadratfreie Faktoren in Charakteristik null
  -> geeignete Primzahl mit Auditspur
  -> vollständige Faktorisierung in F_p[x]
  -> multifaktorielles Hensel-Lifting nach p^k       diese Stufe
  -> ganzzahlige Zassenhaus-Rekombination            implementiert
  -> rationale Reassemblierung                       implementiert
  -> unabhängige Vollständigkeitsprüfung in Q[x]     noch offen
```

## Öffentlicher Vertrag

Der Einstieg lautet:

```java
HenselLifting.lift(
    FactorizationRequest<BigInteger> request,
    SuitablePrimeSelectionResult selection,
    HenselLiftingPolicy policy)
```

Der `FactorizationRequest` bleibt die einzige Quelle für das ursprüngliche
Polynom, die Strukturgrenzen, das Kandidatenbudget und die gesamte zulässige
Arbeit. `selection` muss ein issuer-eigener erfolgreicher
`SuitablePrimeSelectionResult` sein. Die Stufenpolitik ergänzt ausschließlich:

```java
HenselLiftingPolicy.linearMultifactor(
    targetExponent,
    maxModulusBitLength,
    maxIntermediateCoefficientBitLength)
```

`targetExponent = k` bindet das Zielmodul `p^k`. Die beiden Bitgrenzen
verhindern unbegrenztes Wachstum des Moduls und der ganzzahligen
Zwischenkoeffizienten.

## Nichtmonische Quellen

Die Primkörperfaktorisierung trennt den Leitkoeffizienten als Feldeinheit und
gibt monische Faktoren aus. Für das Hensel-Lifting wird diese Darstellung
kanonisch verankert:

1. Der erste modulare Faktor wird mit der Feldeinheit multipliziert.
2. Sein ganzzahliger Lift erhält den tatsächlichen Leitkoeffizienten der
   `Z[x]`-Quelle.
3. Alle weiteren Faktoren bleiben monisch.
4. Korrekturpolynome besitzen stets kleineren Grad als ihr Faktor; daher
   verändern sie keinen Leitkoeffizienten.

Damit bleibt das Produkt der Leitkoeffizienten in jeder Liftstufe exakt der
Leitkoeffizient der Quelle. Es wird keine zusätzliche ganzzahlige Einheit
versteckt oder nachträglich erraten.

## Lineare multifaktorielle Hebung

Seien

```text
f = Quellpolynom in Z[x]
h_1, ..., h_r = paarweise koprime ausgewählte Faktoren in F_p[x]
g_1, ..., g_r = aktuelle ganzzahlige Lifts
m = p^e
```

mit

```text
f = g_1 * ... * g_r  (mod m).
```

Für den Schritt von `m` nach `m*p` wird zunächst exakt berechnet:

```text
E = (f - g_1 * ... * g_r) / m  (mod p).
```

Die Division durch `m` muss für jeden Koeffizienten ohne Rest aufgehen.
Andernfalls liegt keine mathematische Nichtexistenz, sondern eine verletzte
interne Invariante vor.

Für jeden modularen Faktor wird der Kofaktor

```text
H_i = Produkt aller h_j mit j != i
```

gebildet. Da die ausgewählte Primkörperfaktorisierung quadratfrei und
vollständig ist, sind `H_i` und `h_i` koprim. Ein deterministischer erweiterter
Euklidischer Algorithmus berechnet und prüft daher

```text
H_i^(-1) mod h_i.
```

Das Korrekturpolynom lautet:

```text
delta_i = E * H_i^(-1) mod h_i.
```

Vor dem Aktualisieren der Faktoren wird unabhängig die vollständige
CRT-Gleichung geprüft:

```text
E = Summe(delta_i * H_i) in F_p[x].
```

Erst danach wird

```text
g_i' = g_i + m * delta_i
```

gebildet. Untere Koeffizienten werden kanonisch als zentrierte Restklassen
modulo `m*p` gespeichert; der Leitkoeffizient bleibt unverändert.

Nach jedem Schritt prüft die Implementierung erneut:

- jeden Lift reduziert modulo `p` auf genau seinen gebundenen Ausgangsfaktor;
- Faktoranzahl, Grad und Leitkoeffizienten bleiben unverändert;
- das Produkt aller aktualisierten Faktoren ist kongruent zur Quelle modulo
  `p^(e+1)`;
- sämtliche Zwischenwerte halten die Stufenpolitik ein.

Die Implementierung unterstützt eine beliebige positive Anzahl modularer
Faktoren und gemischte Faktorgrade innerhalb der gebundenen Struktur- und
Arbeitsgrenzen. Der Einfaktorfall verwendet denselben Vertrag; daraus wird kein
Irreduzibilitätsclaim über `Z[x]` abgeleitet.

## Gemeinsame Arbeitsautorität

Die Auswahlkampagne und alle eingebetteten Primkörperprüfungen haben bereits
Arbeit verbraucht. Der öffentliche Hensel-Einstieg rekonstruiert deshalb einen
Zähler mit exakt demselben Requestlimit und übernimmt jede bestehende
Stage-Buchung unverändert. Ein paketinterner Einstieg akzeptiert nur einen
Zähler, dessen Limit und Ledger beim Eintritt exakt mit `selection.work()`
übereinstimmen.

Ein leerer oder größerer neuer Zähler wird mit

```text
HENSEL_WORK_BUDGET_AUTHORITY_MISMATCH
```

als technischer Vertragsfehler abgelehnt. Die Stufe kann weder frühere Arbeit
vergessen noch das Budget erweitern.

Die allgemeine native Engine kann mehrere nichtlineare quadratfreie Schichten
unter derselben Request-Autorität nacheinander bearbeiten. In diesem Fall kann
das Auswahlledger einer späteren Schicht bereits abgeschlossene
`hensel.*`-Buchungen einer früheren Schicht enthalten. Während des aktuellen
Lifts dürfen ausschließlich solche bereits vorhandenen `hensel.*`-Stages
monoton wachsen. Auswahl-, Berlekamp-, Zassenhaus- und alle anderen
Präfixbuchungen müssen gegenüber `selection.work()` exakt unverändert bleiben.
Damit werden ehrliche wiederholte Lifts zugelassen, ohne fremde Arbeit
nachträglich dem Hensel-Lifting zuschlagen zu können.

Neue Arbeit wird unter stabilen Präfixen gebucht, darunter:

```text
hensel.target-modulus.*
hensel.verify.source-reduction.*
hensel.modular-anchor.*
hensel.crt.factor-<i>.*
hensel.step-<e>.error.*
hensel.step-<e>.corrections.*
hensel.step-<e>.verify-corrections.*
hensel.step-<e>.factor-<i>.*
hensel.step-<e>.verify-lifted-product.*
```

Budgeterschöpfung bleibt `BUDGET_INCONCLUSIVE`. Ein Teilpräfix erfolgreich
abgeschlossener Liftstufen bleibt in der Evidence erhalten, autorisiert aber
kein Zielmodulergebnis.

## Repräsentationsgrenzen

`maxModulusBitLength` wird vor und nach jeder Modulusmultiplikation geprüft.
`maxIntermediateCoefficientBitLength` gilt für:

- Quellkoeffizienten;
- anfängliche ganzzahlige Lifts;
- Produkte und Partialsummen;
- den exakt durch `p^e` dividierten Fehler;
- skalierte Korrekturen;
- zentrierte Liftkoeffizienten.

Die Multiplikation verwendet vor der Materialisierung eine sichere untere
Bitlängenschranke und prüft anschließend die exakte Ergebnisbitlänge. Die
Grenze ist bewusst konservativ: Auch ein Zwischenwert, der sich später
wegkürzen könnte, wird nicht außerhalb der gebundenen Repräsentation erzeugt.

## Evidence

`HenselLiftingResult` und `HenselLiftStep` besitzen keine öffentlichen
Konstruktoren. Ein erfolgreicher Abschluss bindet:

- den vollständigen ursprünglichen `FactorizationRequest`;
- das Zertifikat der geeigneten Primzahlauswahl;
- die vollständige Hensel-Policy;
- die ausgewählte Primzahl und das Zielmodul;
- die fortgeschriebene stage-getrennte Arbeitsbilanz;
- alle gehobenen Faktoren in gebundener Reihenfolge;
- jede erfolgreiche Liftstufe;
- ein SHA-256-Zertifikat des Gesamtergebnisses.

Jeder `HenselLiftStep` bindet:

- Ausgangs- und Zielexponent;
- Ausgangs- und Zielmodul;
- Hash des Fehlerpolynoms `E`;
- geordnete Hashes aller Korrekturpolynome;
- Hash des verifizierten neuen Faktorprodukts;
- die in dieser Stufe verbrauchten Work Units;
- ein eigenes Zertifikat.

Die Ergebnisprüfung rekonstruiert unabhängig:

1. die Quellkorrespondenz der geeigneten Primzahlauswahl;
2. die verankerten modularen Faktoren;
3. die feste Reduktion jedes gehobenen Faktors modulo `p`;
4. die Leitkoeffizientenkonvention;
5. die aufeinanderfolgenden Moduli `p, p^2, ..., p^k`;
6. die Produktkongruenz am Zielmodul;
7. die exakte Übernahme aller Nicht-Hensel-Auswahlbuchungen und ausschließlich
   monotones Wachstum bereits vorhandener `hensel.*`-Stages.

## Terminale Ergebnisse

| Status | Bedeutung |
| --- | --- |
| `COMPLETED` | Die gebundenen modularen Faktoren wurden bis `p^k` gehoben und das Produkt wurde modulo `p^k` verifiziert. |
| `UNSUPPORTED_DOMAIN` | Die Quelle liegt nicht im exakten ganzzahligen Koeffizientenring. |
| `UNSUPPORTED_SHAPE` | Quelle oder Auswahl besitzen nicht die erforderliche univariate beziehungsweise abgeschlossene Form. |
| `BUDGET_INCONCLUSIVE` | Arbeits-, Modulus- oder Zwischenkoeffizientengrenze reicht nicht aus. |
| `TECHNICAL_FAILURE` | Quell-, CRT-, Kongruenz-, Ring- oder Evidence-Invariante wurde verletzt. |

## Claim-Grenze

`COMPLETED` autorisiert genau:

> Die gebundenen Faktoren reduzieren modulo `p` auf die ausgewählte
> Primkörperfaktorisierung, behalten ihre gebundenen Grade und
> Leitkoeffizienten und rekonstruieren das ursprüngliche primitive
> ganzzahlige Polynom modulo dem ausdrücklich gebundenen Zielmodul `p^k`.

Durch dieses Hensel-Ergebnis allein nicht autorisiert sind:

- eine Zerlegung in tatsächliche Faktoren über `Z[x]`;
- die Behauptung, ein einzelner gehobener Faktor teile die Quelle in `Z[x]`;
- ein Abschlussclaim der nachgelagerten Zassenhaus-, LLL- oder
  van-Hoeij-Rekombination;
- eine bereits ausreichende Rekonstruktionspräzision für jeden denkbaren
  Koeffizientenbound;
- rationale Faktorreassemblierung;
- unabhängige Vollständigkeit oder Irreduzibilität der ursprünglichen
  `Z[x]`- oder `Q[x]`-Quelle;
- multivariate Faktorisierung.

Die allgemeine native Engine darf diese engeren Grenzen erst durch die
separaten, nachgelagerten Rekombinations-, Reassemblierungs- und
Produktprüfungsverträge erweitern.

## Prüfung

Fokussierte Tests:

```bash
./gradlew :regelsuche-math-algorithms:test \
  --tests de.regelsuche.math.algorithms.polynomial.HenselLiftingTest \
  --tests de.regelsuche.math.algorithms.polynomial.HenselLiftingEvidenceTest
```

Vollständiger Checkout-Vertrag:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Nachgelagerte Stufe

Die implementierte allgemeine native Engine übergibt das abgeschlossene
Hensel-Ergebnis an die gebundene deterministische Zassenhaus-Rekombination.
Diese leitet eine ganzzahlige Koeffizientenschranke her, verlangt ein dafür
ausreichendes Zielmodul, enumeriert Faktorpartitionen in stabiler Reihenfolge
und akzeptiert Kandidaten ausschließlich nach exakter Teilbarkeit in `Z[x]`.
Erst diese nachgelagerte Stufe darf tatsächliche ganzzahlige Faktoren ausgeben.
Die rationale Engine reassembliert anschließend Einheit und Faktoren exakt in
`Q[x]`.

Siehe [Domänenbewusste Polynomfaktorisierung](domain-aware-polynomial-factorization.md)
für den vollständigen Pipeline- und Claim-Vertrag.
