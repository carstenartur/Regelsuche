# Domänenbewusste Polynomfaktorisierung

**Implementierungsstand: 25. August 2026**

Regelsuche behandelt Polynomfaktorisierung nicht mehr als Eigenschaft eines
einzelnen Quartikoperators. Der mathematische Kern trennt
Koeffizientendomänen, Polynomringe, kanonische Polynome, allgemeine
univariate Algorithmen, Faktorisierungsanfragen, untrusted Engine-Ausgaben und
unabhängig ausgestellte Evidence.

Die Architektur ist absichtlich nicht rückwärtskompatibel zu den früheren
verschachtelten Polynom- und Kandidatentypen. Es gibt keine externen Nutzer,
deren Quell- oder Binärkompatibilität parallele Alt-APIs rechtfertigen würde.
Historische Evidence behält ihre eingefrorenen Identitäten; aktuelle Java-APIs
werden nach mathematischer Verantwortung gestaltet.

## Aktueller Integrationsstand

Die verfügbaren Bausteine sind unterschiedlich weit integriert. Diese
Unterscheidung ist Teil des öffentlichen Claim-Vertrags:

| Baustein | Stand |
| --- | --- |
| Domänen, Polynomringe und kanonische Sparse-Polynome | implementiert |
| Backendneutrale Engine-/Verifier-Grenze | implementiert |
| Begrenzte binäre Quartikengine mit gemeinsamer Request-/Verifier-Arbeit | implementiert |
| Verlustfreie univariate Koeffizientenansicht und exakte Grundoperationen | implementiert |
| Inhalt und primitiver Teil für `Z[x]` und `Q[x]` | implementiert |
| Euklidischer Polynom-GGT über einem exakten Feld | implementiert |
| Quadratfreie Zerlegung in Charakteristik null | implementiert |
| Eine vollständige Engine, die alle univariaten Stufen unter einem einzigen Request ausführt | **noch nicht implementiert** |
| Faktorisierung über endlichen Körpern, Primzahlauswahl, Hensel-Lifting und Rekombination | **noch nicht implementiert** |
| Unabhängige Vollständigkeits- und Irreduzibilitätsevidence | **noch nicht implementiert** |

Insbesondere sind GGT und quadratfreie Zerlegung bereits exakte, budgetierte
Algorithmen. Ihre derzeitigen öffentlichen Einstiegspunkte laufen jedoch noch
als eigenständig begrenzte Algorithmusaufrufe. Erst eine spätere vollständige
univariate Engine wird Inhaltsnormalisierung, GGT, Quadratfreistellung,
endliche Körper, Hensel-Lifting und Rekombination in einem einzigen
request-weiten Ablauf zusammenführen.

## Bausteine und vorgesehener Gesamtfluss

```text
Quelltext und parsergebundene exakte Literale
  -> PolynomialSemanticView
  -> CoefficientDomain
  -> PolynomialRing mit expliziter Monomordnung
  -> kanonisches SparsePolynomial
  -> FactorizationRequest
       -> request-weite Strukturgrenzen
       -> Evidence-Anforderung
       -> Kandidaten- und Arbeitsbudget

Bereits integrierter Engine-Pfad:
  FactorizationRequest
    -> BinaryQuarticFactorizationEngine
    -> untrusted Proposal / BackendClaim / Work Ledger
    -> FactorizationVerifier
    -> verifier-ausgestellte Kandidaten und Report-Evidence

Bereits implementierte allgemeine Algorithmusbausteine:
  FactorizationRequest
    -> Inhalts- und Primitivteilnormalisierung für Z[x] und Q[x]
       mit übergebbarem, nicht zurücksetzbarem Request-Budget
  SparsePolynomial über exaktem Feld
    -> Polynom-GGT mit eigenem explizitem Budget
    -> quadratfreie Zerlegung mit eigenem explizitem Budget

Noch offene Orchestrierung:
  ein gemeinsamer univariater Engine-Ablauf
    -> Inhalt und primitiver Teil
    -> Quadratfreistellung
    -> Faktorisierung über endlichem Körper
    -> geeignete Primzahlauswahl
    -> Hensel-Lifting
    -> ganzzahlige Rekombination
    -> rationale Reassemblierung
    -> unabhängige Vollständigkeitsprüfung
    -> FactorizationVerifier
```

Das Diagramm beschreibt sowohl implementierte Bausteine als auch die noch
offene Verbindung zu einer vollständigen Engine. Die gestrichene Grenze darf
nicht als bereits vorhandene vollständige `Q[x]`-Faktorisierung gelesen werden.

## Getrennte Identitäten

Die Darstellung trennt vier Identitäten:

1. **Quellidentität:** konkrete Schreibweise, Stellenbereiche und
   Literalprovenienz;
2. **mathematische Polynomidentität:** Koeffizientendomäne, geordnete
   Variablen beziehungsweise strukturelle Atome, Monomordnung und kanonische
   Terme;
3. **Backendprovenienz:** Engine-ID, Engine-Zertifikate, Rohresultat-Hash,
   Backend-Claim und ausgeführte Engine-Arbeit;
4. **Regelsuche-Evidence:** vollständig gebundener Request, unabhängig
   rekonstruierte Ergebnisse, Verifier-Zertifikat, Gesamtarbeit und
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

Die erste Tranche enthält:

```text
regelsuche.coefficients.integer/v1
regelsuche.coefficients.rational/v1
```

Die rationale Implementierung verwendet den autoritativen `ExactRational`-Typ.
Es existiert keine zweite Brucharithmetik und kein `double`-Einstieg in die
exakte Domäne.

Künftige Primkörper, endliche Erweiterungskörper oder algebraische Zahlkörper
werden als weitere Domänen hinter denselben Fähigkeiten ergänzt. Eine Engine
muss ihre unterstützte Domain-ID ausdrücklich deklarieren.

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

`PolynomialSemanticView` verwendet `ExpressionParser.parseExactTerm`.
Numerische Koeffizienten und Exponenten werden über die Identität des vom
Parser erzeugten `NumberExpr`-Knotens zu ihrer exakten Literal-Evidence
aufgelöst. Der historische `double`-Wert des Knotens ist für exakte Mathematik
nicht autoritativ.

Damit bleibt beispielsweise

```text
9007199254740993*x^4
```

auch oberhalb der exakten IEEE-754-Ganzzahlgrenze korrekt. Ein fremd erzeugter
numerischer AST-Knoten erbt keine Source-Evidence. Synthetische Nullknoten aus
der Unary-Minus-Darstellung werden nur als exakt null akzeptiert.

`ExactExpressionFormatter` formatiert parsergebundene Teilbäume auf derselben
Grundlage. Strukturelle Atome und spätere Render-Schritte interpretieren exakte
Werte nicht erneut über `double`.

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

`FactorizationVerifier` prüft diese Grenzen, bevor eine Engine das Quellpolynom
inspizieren darf. Die Inhaltsnormalisierung verwendet denselben Request und
kann einen bereits belasteten, request-autorisierten Work-Zähler übernehmen.
Die bestehenden GGT- und Quadratfrei-Einstiegspunkte besitzen dagegen noch
eigene explizite Budgets; ihre Integration in denselben Request-Zähler ist Teil
der noch offenen vollständigen Engine-Orchestrierung.

Ein Algorithmus mit zusätzlichem Zwischenwertwachstum darf eine eng begrenzte
Stufenpolitik ergänzen, aber keine zweite Request-Oberfläche mit duplizierten
Struktur- oder Arbeitsbudgets einführen.

Ein Backend-Claim erfüllt `INDEPENDENT_COMPLETE` niemals allein. Eine Engine,
die keine unabhängige Vollständigkeits- oder Irreduzibilitätsevidence liefern
kann, muss einen solchen Request als nicht unterstützt behandeln.

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

`UnivariateContentPolicy` ergänzt ausschließlich die Grenze für wachsende
Zwischenkoeffizienten. Source-Struktur und Gesamtarbeit bleiben Eigentum des
`FactorizationRequest`. Ein paketinterner Einstieg übernimmt einen bereits
belasteten `PolynomialWorkBudget`. Er weist einen Zähler zurück, dessen
Autoritätslimit nicht exakt dem Requestbudget entspricht. Dadurch kann eine
spätere Engine den Zähler weder zurücksetzen noch durch Übergabe eines größeren
Limits nachträglich erweitern.

Vor einem positiven Abschluss werden der primitive Koeffizienten-GGT, der
Leitkoeffizient, die ganzzahlige Zwischenform und die vollständige
`Z[x]`- beziehungsweise `Q[x]`-Quelle unabhängig rekonstruiert. Das
issuer-owned Resultat bindet Request, Stufenpolitik, Work Ledger und exakte
Ausgabe in ein deterministisches Zertifikat.

## Polynom-GGT und quadratfreie Zerlegung

`UnivariatePolynomialAlgorithms.gcd` berechnet über einem deklarierten exakten
Feld einen monischen euklidischen Polynom-GGT. `gcd(0, 0)` bleibt ausdrücklich
undefiniert. Unsupported Domain, Unsupported Shape, Budgeterschöpfung und
technischer Fehler sind getrennte Ausgänge.

`SquareFreeDecomposition` führt über einem exakten Feld der Charakteristik null
eine Yun-artige Zerlegung aus. Sie bewahrt Faktorvielfachheiten, rekonstruiert
das Quellpolynom und prüft für jeden ausgegebenen Faktor
`gcd(f, f') = 1`.

Beide Komponenten besitzen derzeit eigenständige explizite Arbeitsbudgets.
Diese Budgets sind nicht versteckt und nicht zurücksetzbar innerhalb des
jeweiligen Algorithmusaufrufs. Die Übergabe eines gemeinsamen
`PolynomialWorkBudget` aus einer vollständigen Engine ist jedoch noch nicht
für alle Stufen implementiert.

Quadratfrei bedeutet nicht irreduzibel. Weder ein GGT-Ergebnis noch eine
quadratfreie Zerlegung autorisiert allein einen Vollständigkeits- oder
Irreduzibilitätsclaim.

## Untrusted Engine-SPI

`FactorizationEngine<C>` arbeitet ausschließlich auf dem mathematischen
Polynom. Parser, AST und Benutzeranzeige gehören nicht zur Engine-Schnittstelle.
Dadurch können native Algorithmen und spätere externe Backendadapter dieselben
Requests bedienen.

Eine Engine gibt ein `EngineResult<C>` mit folgenden Bestandteilen zurück:

- `Outcome`;
- `detailCode`;
- stage-getrenntes `PolynomialWorkLedger`;
- null oder mehrere `Proposal<C>`;
- optionaler `BackendClaim`;
- Engine-Result-Hash.

Ein `Proposal<C>` enthält:

- eine skalare Einheit;
- kanonisch sortierte nichtkonstante Faktoren mit positiven Multiplizitäten;
- einen optionalen nichtkonstanten ungelösten Rest oder exakt eins;
- ein Engine-Zertifikat.

Konstante Restfaktoren sind nicht zulässig: Sie müssen in die skalare Einheit
verschoben werden. Gleiche Faktoren werden zusammengeführt. Ein Backend darf
`COMPLETE_FACTORIZATION` nicht gleichzeitig mit einem ungelösten Rest ungleich
eins behaupten. Widersprüchliche Rohresultate werden als ungültige
Engineausgabe abgelehnt.

Diese Konstruktorinvarianten machen das Objekt strukturell wohldefiniert. Sie
machen seine mathematische Produktbehauptung oder seinen Backend-Claim noch
nicht vertrauenswürdig.

## Kanonisches Work Accounting

`PolynomialWorkLedger` trennt Arbeit nach stabilen Stage-IDs, beispielsweise:

```text
content.denominator-lcm.gcd
content.integralization.multiplication
content.verify.source-comparison
gcd.division.coefficient-updates
square-free.initial-gcd.iterations
verify.factor-product-multiplications
verify.product-comparisons
```

Die Stage-Abbildung wird lexikographisch sortiert und danach als
reihenfolgeerhaltende unveränderliche Map gespeichert. Das kanonische
Hashmaterial hängt damit nicht von einer nicht spezifizierten
Map-Iterationsreihenfolge ab.

Bereits request-weit zusammengeführt sind:

- Engine- und Verifier-Arbeit im vorhandenen Faktorisierungsvertrag;
- sämtliche Unterstufen der Inhaltsnormalisierung;
- wiederholte Inhaltsnormalisierungsaufrufe, wenn eine spätere Orchestrierung
den gleichen `PolynomialWorkBudget` weiterreicht.

Noch nicht request-weit zusammengeführt sind die eigenständigen öffentlichen
GGT- und Quadratfrei-Aufrufe. Ihre Ledgers besitzen bereits dasselbe kanonische
Format, doch die vollständige Engine muss sie noch unter demselben
Request-Zähler orchestrieren. Bis dahin darf aus der Existenz gleicher
Stage-IDs keine bereits implementierte Gesamtbudgetierung der vollständigen
univariaten Pipeline abgeleitet werden.

## Unabhängiger Verifier

`FactorizationVerifier.execute` ist die autoritative Grenze für
Engine-Ergebnisse. Er prüft mindestens:

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

Callers und Engines können deren positiven Zustand nicht über öffentliche
Konstruktoren herstellen. Der Verifier bindet Quellpolynom, vollständigen
Request, Engineprovenienz, Work Ledger, rekonstruierte Faktoren,
Backend-Claim und sein eigenes Zertifikat in das Report-Hashmaterial.

Die issuer-owned Resultate von Inhaltsnormalisierung, GGT und
Quadratfreistellung sind Algorithmusevidence. Sie werden erst dann Teil eines
verifier-ausgestellten Faktorisierungsreports, wenn eine Engine sie in einem
vollständigen Proposal-/Verifier-Ablauf verwendet.

## Status und Claim-Stärken

Terminale `FactorizationVerifier.Status` sind getrennt:

| Status | Bedeutung |
| --- | --- |
| `COMPLETE_FACTORIZATION` | vollständige Faktorisierung mit unabhängig autorisierter Evidence |
| `IRREDUCIBLE` | unabhängig autorisierte Irreduzibilität im deklarierten Ring |
| `PARTIAL_FACTORIZATION` | Produktgleichheit geprüft, Vollständigkeit oder Irreduzibilität nicht unabhängig belegt |
| `NO_FACTORIZATION_FOUND` | kein Kandidat innerhalb des Enginevertrags; kein automatischer Irreduzibilitätsbeweis |
| `UNSUPPORTED_DOMAIN` | Koeffizientendomäne nicht unterstützt |
| `UNSUPPORTED_REQUEST` | Form oder verlangte Evidenzstärke nicht unterstützt |
| `BUDGET_INCONCLUSIVE` | Struktur- oder Gesamtbudget reicht nicht aus |
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

`BACKEND_CLAIMED_COMPLETE` bedeutet nur: Das Backend hat Vollständigkeit
behauptet und Regelsuche hat das ausgegebene Produkt exakt rekonstruiert. Es
autorisiert weder `COMPLETE_FACTORIZATION` noch einen
`INDEPENDENT_COMPLETE`-Request.

## Erste Engine: binäre homogene Quartiken

Die erste Engine besitzt die ID:

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

Sie enumeriert begrenzte Teiler der äußeren Koeffizienten und löst die
verbleibenden linearen Bedingungen exakt. Konkrete Identitäten wie
Sophie-Germain sind nicht gespeichert.

Die Engine gibt `BackendClaim.NONE` aus. Der Verifier erzeugt bei einem
korrekten Produkt `PARTIAL_FACTORIZATION` mit
`ClaimStrength.VERIFIED_DECOMPOSITION`. Obwohl der ungelöste Rest eins ist,
beweist die Engine nicht die Irreduzibilität jedes quadratischen Faktors.

## Ausdrucksadapter und Suche

`PolynomialDecompositionSynthesisOperator` ist ein Integrationsadapter:

```text
String
  -> PolynomialSemanticView
  -> FactorizationRequest
  -> BinaryQuarticFactorizationEngine
  -> FactorizationVerifier
  -> VerifiedCandidate
  -> gerenderter Transformationstext
```

Er besitzt keine eigene Koeffizientenlösung und kein paralleles
Polynomdatenmodell. Große exakte Faktorkoeffizienten werden beim Rendern nicht
über `NumberExpr(int/double)` geleitet.

`PolynomialTheorySubsumptionClassifier` klassifiziert gefundene Identitäten
gegen verifier-ausgestellte Faktorisierungsevidence. Positiv subsumierte
Instanzen können nur in den begrenzten `PolynomialDerivedMacroCache`; sie
werden nicht als neue Kernelgesetze behandelt. Der Cache bindet das
vollständige Work Ledger statt eines quartikspezifischen
Konfigurationszählers.

Die neue allgemeine Inhaltsnormalisierung ist noch nicht automatisch im
Workbench-Suchprofil aktiv. Ihre Nutzung durch eine vollständige univariate
Engine und danach durch Suche beziehungsweise Replay ist ein eigener,
qualifikationspflichtiger Integrationsschritt.

## Keine unnötige Rückwärtskompatibilität

Entfernt beziehungsweise ersetzt wurden insbesondere die früheren
verschachtelten Polynom-, Kandidaten- und Reporttypen. Es gibt keine deprecated
Duplikate und keine Adapter, die den neuen Kern wieder auf die alte
Quartikoberfläche reduzieren.

Ein Adapter wird künftig nur beibehalten, wenn ein konkretes externes
Serialisierungsformat oder ein unveränderliches historisches Experiment ihn
benötigt und dieser Eigentümer ausdrücklich dokumentiert ist.

## Nächste Ausbaustufen

Issue #763 verfolgt als erste vollständige Qualifikationsdomäne:

```text
Q[x]
```

mit beliebigem unterstützten Grad unter expliziten Budgets. Bereits
implementiert sind die gemeinsame univariate Darstellung, Inhalt und
primitiver Teil, Ableitung, exakte Division, Polynom-GGT und quadratfreie
Zerlegung.

Die verbleibende native Pipeline umfasst:

1. gemeinsame Orchestrierung der vorhandenen Vorstufen unter einem einzigen
   `FactorizationRequest` und `PolynomialWorkBudget`;
2. Faktorisierung über endlichen Körpern mit deterministischer oder
   seed-gebundener Algorithmuswahl;
3. geeignete Primzahlauswahl mit retained Ablehnungsgründen;
4. Hensel-Lifting;
5. ganzzahlige Faktorrekomposition, zunächst Zassenhaus;
6. später LLL-/van-Hoeij-Rekombination, wenn qualifiziert;
7. rationale Faktorreassemblierung;
8. unabhängige Vollständigkeits- und Irreduzibilitätsprüfung;
9. Übergabe der vollständigen Ergebnisse an `FactorizationVerifier`.

Parallel kann ein externer Backendadapter dieselben Requests bedienen. Seine
Ergebnisse bleiben Proposals, bis Regelsuche sie exakt rekonstruiert hat. Eine
spätere multivariate Pipeline nutzt denselben Domain-, Ring-, Engine- und
Verifiervertrag, erweitert aber die algorithmische Implementierung.

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
  --tests de.regelsuche.math.algorithms.polynomial.SquareFreeDecompositionTest
```

Discovery-Integration:

```bash
./gradlew :app:test \
  --tests de.regelsuche.docs.PolynomialDecompositionDiscoveryIntegrationTest
```

Vollständiger Repositoryvertrag:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Aussagegrenze

Der implementierte Stand belegt eine erweiterbare, exakte
Faktorisierungsarchitektur, einen fail-closed Engine-/Verifier-Vertrag und die
ersten allgemeinen univariaten Vorstufen. Er belegt noch keine integrierte
vollständige Faktorisierung über `Q[x]`, keine multivariate Faktorisierung und
keine Überlegenheit gegenüber etablierten Computer-Algebra-Systemen.
