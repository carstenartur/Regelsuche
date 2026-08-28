# Domänenbewusste Polynomfaktorisierung

**Implementierungsstand: 28. August 2026**

Regelsuche behandelt Polynomfaktorisierung nicht als Sonderfall eines einzelnen
Quartikoperators. Der mathematische Kern trennt Koeffizientendomänen,
Polynomringe, kanonische Polynome, allgemeine univariate Algorithmen,
Faktorisierungsanfragen, untrusted Engine-Ausgaben und von Regelsuche
ausgestellte Evidence.

Die Architektur ist absichtlich nicht rückwärtskompatibel zu den früheren
verschachtelten Polynom- und Kandidatentypen. Historische Evidence behält ihre
eingefrorenen Identitäten; aktuelle Java-APIs werden nach mathematischer
Verantwortung gestaltet.

## Aktueller Integrationsstand

| Baustein | Stand |
| --- | --- |
| Domänen, Polynomringe und kanonische Sparse-Polynome | implementiert |
| Backendneutrale Engine-/Verifier-Grenze | implementiert |
| Begrenzte binäre Quartikengine | implementiert |
| Verlustfreie univariate Koeffizientenansicht | implementiert |
| Parsergebundene direkte `ExactParsedTerm`-zu-`Q[x]`-Pipeline | implementiert |
| Inhalt und primitiver Teil für `Z[x]` und `Q[x]` | implementiert |
| Euklidischer Polynom-GGT über einem exakten Feld | implementiert |
| Quadratfreie Zerlegung in Charakteristik null | implementiert |
| Explizite Primkörperdomäne `F_p` | implementiert |
| Vollständige Faktorisierung quadratfreier Polynome in `F_p[x]` | implementiert |
| Deterministische geeignete Primzahlauswahl | implementiert |
| Lineares Multifaktor-Hensel-Lifting bis zu einem expliziten `p^k` | implementiert |
| Begrenzte deterministische Zassenhaus-Rekombination in `Z[x]` | implementiert |
| Integrierte native allgemeine Engine für `Z[x]` und `Q[x]` | implementiert |
| Exakte Produktrückprüfung durch `FactorizationVerifier` | implementiert |
| Exaktes Faktorenrendering mit Reparse-/Ringrekonstruktion am Wurzelvorkommen | implementiert |
| Verschachtelte occurrence-preserving Suchintegration | noch offen |
| Unabhängige Vollständigkeits-/Irreduzibilitätszertifikate | noch offen |
| LLL-/van-Hoeij-Rekombination für größere Fälle | noch offen |
| Automatische Integration der allgemeinen Engine in Suche und Workbench | noch offen |

Die native Engine liefert damit eine vollständige **Backend-Zerlegung** im
begrenzten unterstützten Fragment. Regelsuche rekonstruiert jedes ausgegebene
Produkt exakt. Der allgemeine `FactorizationVerifier` beweist jedoch nicht
unabhängig, dass die Faktoren irreduzibel sind oder dass kein weiterer Faktor
existiert. Deshalb bleiben positive Vollständigkeits- und
Irreduzibilitätsaussagen als Backend-Claims sichtbar.

## Gesamtfluss

```text
Quelltext
  -> ExpressionParser.parseExactTerm
  -> ExactParsedTerm mit node-identischer Literalprovenienz
  -> ExactParsedUnivariatePolynomialView
  -> PolynomialRing<ExactRational>
  -> kanonisches SparsePolynomial<ExactRational>
  -> FactorizationRequest
       -> request-weite Strukturgrenzen
       -> Evidence-Anforderung
       -> Kandidaten- und Arbeitsbudget

NativeUnivariateFactorizationEngine für Z[x] oder Q[x]
  -> Inhalt und primitiver Teil
  -> Ableitung / GGT / quadratfreie Zerlegung
  -> geeignete Primzahl aus gebundener Kandidatenfolge
  -> exakte kanonische Reduktion nach F_p[x]
  -> vollständige Berlekamp-Faktorisierung in F_p[x]
  -> lineares Multifaktor-Hensel-Lifting bis p^k
  -> deterministische Zassenhaus-Rekombination in Z[x]
  -> exakte rationale Reassemblierung für Q[x]
  -> untrusted Proposal / BackendClaim / Work Ledger
  -> FactorizationVerifier
  -> exakte Produktrückprüfung und verifier-ausgestellte Evidence

Verifier-issued candidate am Wurzelvorkommen
  -> vorautorisierte Quell-/Literal-Evidence-Prüfung
  -> deterministisches exaktes Faktorenrendering
  -> ExpressionParser.parseExactTerm
  -> erneute ExactParsedUnivariatePolynomialView
  -> exakte Ring- und Polynomgleichheit
  -> content-addressed transformation evidence
```

Extraktion, Engine, Produktrückprüfung, Quellbelegprüfung, Rendering und
Rekonstruktion werden unter derselben ursprünglichen Arbeitsautorität
ausgeführt. Ein erschöpftes Kandidaten-, Präzisions-, Repräsentations- oder
Arbeitsbudget bleibt `BUDGET_INCONCLUSIVE` und wird nie zu einer
Irreduzibilitätsaussage umgedeutet.

## Getrennte Identitäten

Die Darstellung trennt vier Identitäten:

1. **Quellidentität:** konkrete Schreibweise, Stellenbereiche und
   Literalprovenienz;
2. **mathematische Polynomidentität:** Koeffizientendomäne, geordnete
   Variablen beziehungsweise strukturelle Atome, Monomordnung und kanonische
   Terme;
3. **Backendprovenienz:** Engine-ID, Engine-Zertifikate, Rohresultat-Hash,
   Backend-Claim und ausgeführte Engine-Arbeit;
4. **Regelsuche-Evidence:** vollständig gebundener Request, rekonstruierte
   Ergebnisse, Verifier- oder Algorithmuszertifikat, Gesamtarbeit und
   autorisierte Claim-Stärke.

Eine andere Quellschreibweise verändert nicht automatisch das mathematische
Polynom. Eine andere Koeffizientendomäne, Variablenordnung oder Monomordnung
verändert dagegen den Ring und damit die mathematische und algorithmische
Identität.

## Koeffizientendomänen

`CoefficientDomain<C>` definiert exakte kanonische Arithmetik:

- stabile Domain-ID;
- Charakteristik;
- kanonische Einbettung ganzer Zahlen;
- Null und Eins;
- Addition, Negation und Multiplikation;
- kanonische Textdarstellung;
- Größenmaß für Budgets.

Zusätzliche algebraische Fähigkeiten besitzen getrennte Verträge:

- `ExactField<C>` für exakte Division durch Nichtnullwerte;
- `GcdDomain<C>` für GGT und geprüfte exakte Division.

Implementiert sind:

```text
regelsuche.coefficients.integer/v1
regelsuche.coefficients.rational/v1
regelsuche.coefficients.prime-field/v1/p=<p>
```

Die rationale Implementierung verwendet den autoritativen
`ExactRational`-Typ. Es existiert keine zweite Brucharithmetik und kein
`double`-Einstieg in die exakte Domäne.

`PrimeField` speichert kanonische `BigInteger`-Restklassen und bindet die
deterministisch geprüfte Primzahl in die Domain-ID. Ringe über
unterschiedlichen Primzahlen sind daher nicht gleich. Endliche
Erweiterungskörper und algebraische Zahlkörper bleiben spätere Domänen hinter
denselben Fähigkeiten.

## Polynomring und kanonisches Sparse-Polynom

`PolynomialRing<C>` bindet:

- die Koeffizientendomäne;
- eine deterministische, geordnete Variablenliste;
- eine explizite Monomordnung.

Implementiert sind:

```text
LEXICOGRAPHIC
GRADED_LEXICOGRAPHIC
GRADED_REVERSE_LEXICOGRAPHIC
```

Eine Variable kann ein gewöhnlicher Variablenname oder die stabile Identität
eines vollständigen AST-Teilbaums sein. Anzeige und AST-Vorkommen liegen in
einem separaten View-Objekt; sie gehören nicht zur mathematischen
Polynomidentität.

`SparsePolynomial<C>` speichert ausschließlich von null verschiedene Terme:

```text
Monomial(exponent vector) -> canonical coefficient
```

Der Typ stellt insbesondere bereit:

- exakte Addition, Subtraktion, Skalierung und Multiplikation;
- Potenzen durch wiederholtes Quadrieren;
- Gesamt- und Variablengrade;
- Termzahl und maximale Koeffizientenbitlänge;
- Homogenitätsprüfung;
- explizite Homogenisierung durch Erweiterung des Rings;
- kanonisches Material für Evidence und Deduplication.

Ein Polynom aus einem anderen Ring kann nicht stillschweigend addiert oder
multipliziert werden. Monom-Arity, Domain-ID, Variablenordnung und Monomordnung
werden am Konstruktor beziehungsweise vor binären Operationen geprüft.

## Exakte Syntaxgrenze

`ExpressionParser.parseExactTerm` erzeugt ein `ExactParsedTerm`, das den
ursprünglichen Quelltext, den gewöhnlichen Syntax-AST und für jedes numerische
Literal eine parserausgestellte exakte Evidence enthält. Die Zuordnung erfolgt
über die Identität des konkret vom Parser erzeugten `NumberExpr`-Knotens.
Wertgleiche, später erzeugte Knoten erben diese Provenienz nicht.

`ExactParsedUnivariatePolynomialView` löst Koeffizienten und Exponenten nur über
diese node-identische Evidence auf und erzeugt direkt ein
`SparsePolynomial<ExactRational>` in einem expliziten Ring. Unterstützt werden
begrenzt Addition, Subtraktion, kommutative Multiplikation, exakte Division
durch Konstanten und nichtnegative ganzzahlige Potenzen. Mehrere Variablen,
Funktionen, variable Divisoren, provenance-freie Zahlknoten und überschrittene
Darstellungs- oder Arbeitsgrenzen werden fail-closed abgelehnt.

`ExactParsedFactorizationPipeline` übergibt nach der Extraktion ausschließlich
das verbleibende ursprüngliche Budget an `FactorizationRequest`, Engine und
`FactorizationVerifier`. Es gibt keinen Render-/Reparse-Zwischenschritt und
keine Wiedergewinnung exakter Werte aus `double`.

Damit bleibt beispielsweise

```text
9007199254740993*x^4
```

auch oberhalb der exakten IEEE-754-Ganzzahlgrenze korrekt. Ein fremd erzeugter
numerischer AST-Knoten erbt keine Source-Evidence. Synthetische Nullknoten aus
der Unary-Minus-Darstellung werden nur als strukturelle Nullmarker akzeptiert,
nie als allgemeine Koeffizientenautorität.

Nach einer verifier-ausgestellten Zerlegung rendert
`ExactFactorizationExpressionRenderer` rationale Werte ausschließlich als
Ganzzahlen oder explizite Brüche. `ExactFactorizationTransformationPipeline`
parst dieses Ergebnis erneut exakt, extrahiert das Polynom unabhängig und
autorisiert eine `transformedExpression` nur bei exakter Gleichheit im
ursprünglichen Ring. Weitere Details stehen in
[Exakte Parser-zu-Faktorisierungs-Pipeline](exact-parsed-factorization-pipeline.md)
und
[Exakte Faktorisierungs-Transformationspipeline](exact-factorization-transformation-pipeline.md).

## Faktorisierungsanfrage und request-weite Grenzen

`FactorizationRequest<C>` enthält:

- das kanonische Quellpolynom;
- `EvidenceRequirement.VERIFIED_DECOMPOSITION` oder
  `EvidenceRequirement.INDEPENDENT_COMPLETE`;
- verpflichtende `StructuralLimits`;
- das maximale Kandidatenbudget;
- ein nicht zurücksetzbares Gesamtbudget in kanonischen Work Units.

Die Strukturgrenzen umfassen:

- maximale Variablenzahl;
- maximalen Gesamtgrad;
- maximale Termzahl;
- maximale Koeffizientenbitlänge.

`FactorizationVerifier` und die issuer-owned Algorithmusstufen prüfen diese
Grenzen vor einem positiven Abschluss. Inhaltsnormalisierung,
quadratfreie Zerlegung, Primzahlauswahl, Primkörperfaktorisierung,
Hensel-Lifting und Rekombination teilen dieselbe bereits belastete
`PolynomialWorkBudget`-Autorität.

Ein Algorithmus mit zusätzlichem Zwischenwertwachstum darf eine eng begrenzte
Stufenpolitik ergänzen, aber keine zweite Request-Oberfläche mit duplizierten
Struktur- oder Arbeitsbudgets einführen.

Ein Backend-Claim erfüllt `INDEPENDENT_COMPLETE` niemals allein. Eine Engine,
die keine algorithmisch unabhängige Vollständigkeits- oder
Irreduzibilitätsevidence liefern kann, muss einen solchen Request als nicht
unterstützt behandeln.

## Allgemeine univariate Darstellung

`UnivariatePolynomialView<C>` projiziert ein kanonisches univariates
`SparsePolynomial<C>` verlustfrei auf aufsteigend indizierte Koeffizienten:

```text
[a0, a1, ..., an]  <=>  a0 + a1*x + ... + an*x^n
```

Die View ist keine zweite mathematische Polynomidentität. Sie bindet denselben
Ring, entfernt kanonisch führende Nullkoeffizienten und kann exakt in die
Sparse-Darstellung zurückgeführt werden.

Sie implementiert:

- Addition, Subtraktion und Skalierung;
- Multiplikation;
- formale Ableitung;
- monische Normierung über einem exakten Feld;
- Polynomdivision mit Quotient und Rest;
- einen exakt geprüften Quotienten.

Der Core kennt dafür nur `PolynomialWorkSink`. Konkrete Budgets und
Algorithmus-Evidence liegen in `regelsuche-math-algorithms`.

## Inhalt und primitiver Teil

`UnivariateContentNormalization` verarbeitet einen
`FactorizationRequest<BigInteger>` oder
`FactorizationRequest<ExactRational>` und erzeugt kanonisch:

```text
source = scalar * primitivePart
```

mit:

- `scalar` als exakter rationaler Zahl;
- `primitivePart` in `Z[x]`;
- Koeffizienten-GGT `1`;
- positivem Leitkoeffizienten;
- unveränderter Variablenreihenfolge, Monomordnung und Monomunterstützung.

Rationale Nenner werden exakt über ihr kleinstes gemeinsames Vielfaches
beseitigt. Danach wird der positive ganzzahlige Inhalt extrahiert und das
Leitvorzeichen in den Skalar verschoben.

Vor einem positiven Abschluss werden der primitive Koeffizienten-GGT, der
Leitkoeffizient, die ganzzahlige Zwischenform und die vollständige
`Z[x]`- beziehungsweise `Q[x]`-Quelle rekonstruiert. Das issuer-owned Resultat
bindet Request, Stufenpolitik, Work Ledger und exakte Ausgabe in ein
deterministisches Zertifikat.

## Polynom-GGT und quadratfreie Zerlegung

`UnivariatePolynomialAlgorithms.gcd` berechnet über einem deklarierten exakten
Feld einen monischen euklidischen Polynom-GGT. `gcd(0, 0)` bleibt ausdrücklich
undefiniert. Unsupported Domain, Unsupported Shape, Budgeterschöpfung und
technischer Fehler sind getrennte Ausgänge.

`SquareFreeDecomposition` führt über einem exakten Feld der Charakteristik null
eine Yun-artige Zerlegung aus. Sie bewahrt Faktorvielfachheiten, rekonstruiert
das Quellpolynom und prüft für jeden ausgegebenen Faktor `gcd(f, f') = 1`.

Quadratfrei bedeutet nicht irreduzibel. Weder ein GGT-Ergebnis noch eine
quadratfreie Zerlegung autorisiert allein einen Vollständigkeits- oder
Irreduzibilitätsclaim.

## Vollständige Faktorisierung in `F_p[x]`

`FiniteFieldFactorization` verarbeitet ein nichtkonstantes, quadratfreies
univariates Polynom über einem ausdrücklich deklarierten `PrimeField`.

Der deterministische Ablauf umfasst:

1. Monisierung und bewahrte Feldeinheit;
2. exakte Quadratfreiheitsprüfung;
3. Aufbau der Berlekamp-Matrix `Q - I`;
4. deterministische RREF- und Nullraumberechnung;
5. unabhängige Prüfung jedes Nullraumvektors;
6. deterministisches Splitting nach Basisreihenfolge und Restklassen;
7. exakte Rekonstruktion der modularen Quelle;
8. paarweise Koprimheitsprüfung;
9. Rabin-/Frobenius-Irreduzibilitätsprüfung jedes Faktors;
10. Abgleich von Faktoranzahl und Berlekamp-Nullität.

Die Stufenpolitik begrenzt die enumerierten Feldelemente und prüft vor der
Allokation die konservative Peak-Schranke

```text
3 * degree² <= maxMatrixCells
```

für Ausgangsmatrix, RREF-Kopie und Nullraumbasis. Alle Teilstufen teilen das
nicht zurücksetzbare Requestbudget.

`COMPLETED` bedeutet eine vollständige Faktorisierung im gebundenen `F_p[x]`.
Dieser Claim gilt nicht automatisch im ursprünglichen `Z[x]` oder `Q[x]`.

## Geeignete Primzahl und exakte modulare Reduktion

`SuitablePrimeSelection` verarbeitet ein kanonisches primitives Polynom in
`Z[x]` mit positivem Leitkoeffizienten. Die Policy bindet eine streng
aufsteigende Kandidatenfolge und die vollständige
`FiniteFieldFactorizationPolicy`.

Für jede Primzahl wird die Quelle exakt nach `F_p[x]` reduziert. Die Stufe
bewahrt:

- Gradverlust durch einen verschwindenden Leitkoeffizienten;
- nicht quadratfreie modulare Reduktionen;
- terminale Ressourcen- oder Technikfehler;
- Versuchskosten, Modularquellhash und verschachtelte Zertifikats-ID.

Ausgewählt wird die erste gradtreue Primzahl mit quadratfreier und vollständig
faktorisierter Reduktion. Ein ausgeschöpfter Kandidatenpräfix bleibt
`BUDGET_INCONCLUSIVE` und ist kein Beweis, dass keine geeignete Primzahl
existiert.

Der positive Abschluss prüft drei getrennte Bindungen:

1. der ausgewählte Versuch benennt die retained modulare Quelle;
2. das verschachtelte Primkörperzertifikat wurde für genau diese Quelle
   ausgestellt;
3. die retained modulare Quelle ist tatsächlich die kanonische Reduktion der
   gebundenen ganzzahligen Quelle modulo der ausgewählten Primzahl.

## Lineares Multifaktor-Hensel-Lifting

`HenselLifting` hebt die vollständig faktorisierten, paarweise koprimen
modularen Faktoren von `p` bis zu einem expliziten `p^k`.

Der Ablauf:

1. bindet Quelle, geeignete Primzahl und modulare Faktorzerlegung;
2. verankert die modulare Einheit am ersten Faktor;
3. berechnet die CRT-Kofaktoren und ihre geprüften modularen Inversen;
4. rekonstruiert in jedem Schritt das durch den aktuellen Modulus teilbare
   Fehlerpolynom;
5. berechnet und prüft die Korrekturgleichung;
6. hält die Leitkoeffizienten der Liftfaktoren fest;
7. zentriert die Koeffizienten modulo des jeweils nächsten Modulus;
8. prüft nach jedem Schritt Quellkongruenz und feste Reduktionen modulo `p`;
9. bewahrt eine Folge issuer-owned `HenselLiftStep`-Zertifikate.

Der Zielmodulus wird nicht heuristisch gewählt. Die Zassenhaus-Stufe berechnet
eine explizite ganzzahlige Faktor-Koeffizientenschranke `B`, und die Pipeline
fordert

```text
p^k > 2 * B
```

bevor zentrierte Restklassen eindeutig als ganzzahlige Koeffizienten
interpretiert werden.

Modulusbitlänge, Zwischenkoeffizienten und Gesamtarbeit sind explizit
begrenzt. Unzureichende Präzision bleibt `BUDGET_INCONCLUSIVE`.

## Deterministische Zassenhaus-Rekombination

`ZassenhausRecombination` verarbeitet genau eine primitive, quadratfreie
ganzzahlige Schicht.

Die Stufe:

- normalisiert die Hensel-Faktoren modulo `p^k` auf monische Faktoren;
- enumeriert echte Teilmengen in stabiler Größen- und lexikographischer
  Reihenfolge;
- überspringt bei gleich großen Komplementen die doppelte Hälfte;
- enumeriert positive Teiler des aktuellen Leitkoeffizienten;
- bildet zentrierte ganzzahlige Kandidaten modulo `p^k`;
- verwirft Konstanten, das Gesamtpolynom, nichtprimitive Kandidaten und
  Kandidaten außerhalb der Koeffizientenschranke;
- akzeptiert einen Kandidaten erst nach exakter Langdivision in `Z[x]`;
- setzt die Suche auf Quotient und verbleibenden modularen Gruppen fort;
- bewahrt Faktorpartitionen und einen deterministischen Candidate-Audit-Hash.

Vor Ausgabe werden unabhängig innerhalb der Stufe geprüft:

- vollständige und disjunkte Zuordnung der modularen Faktoren;
- Übereinstimmung jedes ganzzahligen Faktors mit seiner modularen Partition;
- Primitivität und positiver Leitkoeffizient;
- exakte Rekonstruktion der gebundenen `Z[x]`-Quelle.

Die Suchmenge wächst exponentiell mit der Zahl modularer Faktoren. Daher
begrenzen Policy und Request Teilmengenkandidaten, Teiler, Faktorzahl,
Modulusbitlänge, Zwischenkoeffizienten und Work Units. Ein erschöpfter
Präfix ist keine Irreduzibilitätsevidence.

## Integrierte native Engine für `Z[x]` und `Q[x]`

`NativeUnivariateFactorizationEngine` besitzt typisierte Fabriken:

```text
NativeUnivariateFactorizationEngine.boundedIntegers()
NativeUnivariateFactorizationEngine.boundedRationals()
```

Die gemeinsame Pipeline:

1. normalisiert die Quelle nach `scalar * primitivePart`;
2. zerlegt den primitiven Teil quadratfrei in Charakteristik null;
3. überführt jede Schicht wieder kanonisch nach `Z[x]`;
4. faktorisiert lineare Schichten direkt;
5. faktorisiert höhere Schichten über Primzahlauswahl, Hensel-Lifting und
   Zassenhaus-Rekombination;
6. bewahrt die quadratfreien Multiplizitäten;
7. führt alle skalaren Einheiten exakt zusammen;
8. materialisiert die Faktoren im ursprünglichen Integer- oder Rationalring;
9. gibt genau ein vollständiges Proposal oder einen fail-closed Ausgang aus.

Ein Resultat, das nur einen zur Quelle assoziierten Faktor enthält, wird nicht
als Zerlegung ausgegeben. Die Engine bewahrt dann einen
`BACKEND_CLAIMED_IRREDUCIBLE`-Claim; der Verifier macht daraus ohne
unabhängiges Zertifikat keinen `IRREDUCIBLE`-Status.

## Untrusted Engine-SPI und unabhängiger Verifier

`FactorizationEngine<C>` arbeitet ausschließlich auf dem mathematischen
Polynom. Parser, AST und Benutzeranzeige gehören nicht zur
Engine-Schnittstelle. Native Algorithmen und externe Backendadapter können
dadurch dieselben Requests bedienen.

Eine Engine gibt ein `EngineResult<C>` zurück mit:

- `Outcome`;
- `detailCode`;
- stage-getrenntem `PolynomialWorkLedger`;
- null oder mehreren `Proposal<C>`;
- optionalem `BackendClaim`;
- Engine-Result-Hash.

Ein `Proposal<C>` enthält:

- eine skalare Einheit;
- kanonisch sortierte nichtkonstante Faktoren mit positiven Multiplizitäten;
- einen optionalen nichtkonstanten ungelösten Rest oder exakt eins;
- ein Engine-Zertifikat.

`FactorizationVerifier.execute` prüft mindestens:

- request-weite Strukturgrenzen vor dem Engine-Aufruf;
- Übereinstimmung von Engine- und Koeffizientendomänen-ID;
- Übereinstimmung der deklarierten und zurückgegebenen Engine-ID;
- Einhaltung von Work- und Kandidatenbudget;
- Ringgleichheit sämtlicher Faktoren und des ungelösten Restes;
- exakte Rückmultiplikation von Einheit, Faktorpotenzen und Rest;
- Trennung von Backend-Claim und angeforderter unabhängiger Evidence.

Erst danach entstehen issuer-owned Typen:

```text
FactorizationVerifier.VerifiedCandidate<C>
FactorizationVerifier.Report<C>
```

## Status und Claim-Stärken

| Status | Bedeutung |
| --- | --- |
| `COMPLETE_FACTORIZATION` | vollständige Faktorisierung mit unabhängig autorisierter Evidence |
| `IRREDUCIBLE` | unabhängig autorisierte Irreduzibilität im deklarierten Ring |
| `PARTIAL_FACTORIZATION` | Produktgleichheit geprüft, Vollständigkeit nicht unabhängig belegt |
| `NO_FACTORIZATION_FOUND` | kein Kandidat innerhalb des Enginevertrags |
| `UNSUPPORTED_DOMAIN` | Koeffizientendomäne nicht unterstützt |
| `UNSUPPORTED_REQUEST` | Form oder verlangte Evidenzstärke nicht unterstützt |
| `BUDGET_INCONCLUSIVE` | Struktur-, Kandidaten- oder Arbeitsbudget reicht nicht aus |
| `TECHNICAL_FAILURE` | Vertragsverletzung, ungültige Engineausgabe oder technischer Fehler |

Die Claim-Stärke bleibt separat sichtbar:

```text
NONE
VERIFIED_DECOMPOSITION
BACKEND_CLAIMED_COMPLETE
BACKEND_CLAIMED_IRREDUCIBLE
INDEPENDENTLY_CERTIFIED_COMPLETE
INDEPENDENTLY_CERTIFIED_IRREDUCIBLE
```

`BACKEND_CLAIMED_COMPLETE` bedeutet: Das Backend hat Vollständigkeit behauptet
und Regelsuche hat das ausgegebene Produkt exakt rekonstruiert. Es autorisiert
weder `COMPLETE_FACTORIZATION` noch einen `INDEPENDENT_COMPLETE`-Request.

## Kanonisches Work Accounting

`PolynomialWorkLedger` trennt Arbeit nach stabilen Stage-IDs, unter anderem:

```text
content.denominator-lcm.gcd
square-free.initial-gcd.iterations
berlekamp.matrix.frobenius-powers
suitable-prime.modular-reduction
hensel.step-<n>.corrections
zassenhaus.candidate.attempts
verify.factor-product-multiplications
transform.source-evidence-text-validation
render.inspected-polynomial-terms
transform.exact-reparse-input-code-units
```

Die Stage-Abbildung wird kanonisch sortiert. Verschachtelte Stufen übernehmen
den bereits belasteten Ledger. Ein Prefix muss monoton erhalten bleiben:
Wenn eine spätere Stufe unter derselben Stage-ID weitere Arbeit ausführt, darf
deren Zähler steigen, aber nie sinken. Diese Regel ist insbesondere relevant,
wenn die Zassenhaus-Koeffizientenschranke vor dem Lift bestimmt und am
öffentlichen Rekombinationseinstieg erneut geprüft wird.

Work Units sind deterministische algorithmische Zähler. Sie sind weder
Wandzeit noch CPU-Zyklen und dürfen nicht als plattformübergreifender
Performancewert interpretiert werden.

## Erste Spezialengine: binäre homogene Quartiken

Die ältere Spezialengine besitzt die ID:

```text
regelsuche.factorization.binary-quartic-2x2/v1
```

Sie löst exakt die Koeffizientenbedingungen einer
quadratisch-mal-quadratisch-Zerlegung binärer homogener Quartiken:

```text
(a*A^2 + b*A*B + c*B^2)
*
(d*A^2 + e*A*B + f*B^2)
```

Die Engine bleibt als spezialisierter Kontrollpfad und für bestehende
Suchintegration erhalten. Ihr Performancewert ist kein Stellvertreter für die
allgemeine native univariate Engine.

## Ausdrucksadapter, Suche und Workbench

`PolynomialDecompositionSynthesisOperator` integriert weiterhin die binäre
Quartikengine als spezialisierten historischen Kontrollpfad.

Für die allgemeine univariate Engine ist jetzt der exakte Wurzelpfad
implementiert:

```text
ExactParsedTerm
  -> ExactParsedFactorizationPipeline
  -> FactorizationVerifier.VerifiedCandidate
  -> ExactFactorizationExpressionRenderer
  -> exaktes Reparse und Ringrekonstruktion
  -> ExactFactorizationTransformationPipeline.Result
```

Die Pipeline bewahrt Quell-/Literalprovenienz, unterscheidet Backend-Claims von
unabhängiger Evidence, verlangt bei mehreren Kandidaten eine explizite Auswahl
und gibt eine Ersatzdarstellung nur nach vollständiger Rekonstruktion aus.
Sie ist noch nicht automatisch in einem Suchprofil aktiv.

`PolynomialTheorySubsumptionClassifier` klassifiziert gefundene Identitäten
gegen verifier-ausgestellte Faktorisierungsevidence. Positiv subsumierte
Instanzen können nur in den begrenzten `PolynomialDerivedMacroCache`; sie
werden nicht als neue Kernelgesetze behandelt.

Als nächster Produktabschnitt müssen verschachtelte Vorkommen die bestehende
`TreePosition`-/Stalenessschutz- und Local-Rewrite-Infrastruktur wiederverwenden.
Danach benötigt die Integration in Vorbereitung, Suche, Replay und Lernen eine
gesonderte Auswahl- und Explainability-Policy, damit teure algebraische
Makrooperationen nicht unkontrolliert jede Suchfront erweitern.

## Capability-matched Vergleich mit SymPy

Das Modul `regelsuche-math-sympy` stellt dieselben typisierten
`FactorizationRequest`s einem eingebetteten SymPy/GraalPy-Backend zur
Verfügung.

Der primäre allgemeine Benchmark verwendet einen eingefrorenen gemeinsamen
Korpus über `Z[x]` und `Q[x]` mit:

- dichten und dünn besetzten Polynomen;
- kleinen und größeren Koeffizienten;
- ganzzahligem und rationalem Content;
- reduzierbaren und irreduziblen Fällen;
- quadratfreien und wiederholten Faktoren;
- Graden 2 bis 6.

Native und SymPy erhalten pro Fall denselben Request. Backend-only- und
End-to-End-Spuren trennen Enginearbeit von der gemeinsamen
Produktrückprüfung. Der frühere binäre Quartikfall bleibt ausdrücklich nur
`SPECIALIZED_BINARY_QUARTIC_CONTROL`.

Laufzeiten sind umgebungsbezogene Engineering-Diagnostik. Es gibt kein
relatives Winner-Gate und keine universelle Aussage über Computer-Algebra-
Systeme.

## Nächste Ausbaustufen

Issue #763 verfolgt nach dem exakten Wurzel-Transformationspfad insbesondere:

1. verschachtelte occurrence-preserving Faktorisierung über die vorhandene
   Pfad- und Stalenessschutz-Infrastruktur;
2. dieselbe verifier-autorisierte Transformationsidentität für On-Demand-Suche,
   Replay und abgeleitete Cache-Makros;
3. den eingefrorenen Vergleich von keiner Faktorisierung, On-Demand-Ausführung
   und verifier-gebundenem Cache unter gleicher sichtbarer Information und
   kanonischer Arbeit;
4. algorithmisch unabhängige Vollständigkeits- und
   Irreduzibilitätszertifikate für `Z[x]` und `Q[x]`;
5. stärkere Rekombination, zunächst LLL-/van-Hoeij-artig, für Fälle mit vielen
   modularen Faktoren;
6. breitere gehaltene und adversarielle Korpora mit abgestuften Budgets;
7. spätere multivariate und algebraische Koeffizientendomänen hinter
   demselben Domain-, Ring-, Engine- und Verifiervertrag.

## Prüfung aus dem Checkout

Fokussierte Kern- und Algorithmusprüfungen:

```bash
./gradlew :regelsuche-core:test \
  --tests 'de.regelsuche.polynomial.*' \
  --tests de.regelsuche.parse.ExactExpressionFormatterTest \
  --tests de.regelsuche.transform.PolynomialDecompositionSynthesisOperatorTest \
  --tests de.regelsuche.transform.PolynomialTheorySubsumptionClassifierTest

./gradlew :regelsuche-math-algorithms:test \
  --tests de.regelsuche.math.algorithms.polynomial.UnivariateContentNormalizationTest \
  --tests de.regelsuche.math.algorithms.polynomial.UnivariatePolynomialAlgorithmsTest \
  --tests de.regelsuche.math.algorithms.polynomial.SquareFreeDecompositionTest \
  --tests de.regelsuche.math.algorithms.polynomial.FiniteFieldFactorizationTest \
  --tests de.regelsuche.math.algorithms.polynomial.SuitablePrimeSelectionTest \
  --tests de.regelsuche.math.algorithms.polynomial.HenselLiftingTest \
  --tests de.regelsuche.math.algorithms.polynomial.ZassenhausRecombinationTest \
  --tests de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngineTest \
  --tests de.regelsuche.math.algorithms.polynomial.ExactFactorizationNativeRenderingIntegrationTest
```

Capability-matched Benchmark und validierter Bericht:

```bash
./gradlew :regelsuche-math-sympy:verifySymPyFactorizationBenchmark
```

Vollständiger Repositoryvertrag:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Aussagegrenze

Der implementierte Stand belegt eine erweiterbare exakte
Faktorisierungsarchitektur, eine begrenzte native allgemeine univariate Engine
für `Z[x]` und `Q[x]`, vollständige interne Modular-, Lift- und
Rekombinationsinvarianten, exakte Produktrückprüfung sowie einen exakt
rekonstruierten Ausdruckstransformationspfad für das Wurzelvorkommen.

Er belegt noch keine algorithmisch unabhängige Vollständigkeit oder
Irreduzibilität in `Z[x]` beziehungsweise `Q[x]`, keine multivariate
Faktorisierung, keinen qualifizierten Suchvorteil und keine universelle
Überlegenheit gegenüber etablierten Computer-Algebra-Systemen.
