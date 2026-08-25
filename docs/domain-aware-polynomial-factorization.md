# Domänenbewusste Polynomfaktorisierung

**Implementierungsstand: 25. August 2026**

Regelsuche behandelt Polynomfaktorisierung nicht mehr als Eigenschaft eines
einzelnen Quartikoperators. Der mathematische Kern trennt Koeffizientendomänen,
Polynomringe, kanonische Polynome, Faktorisierungsanfragen, untrusted
Engine-Ausgaben und unabhängig ausgestellte Evidence.

Die Architektur ist absichtlich nicht rückwärtskompatibel zu den früheren
verschachtelten Typen von `PolynomialSemanticView` und
`PolynomialDecompositionSynthesisOperator`. Es gibt keine externen Nutzer, deren
Quell- oder Binärkompatibilität parallele Alt-APIs rechtfertigen würde.
Historische Evidence behält ihre eingefrorenen Identitäten; aktuelle Java-APIs
werden nach mathematischer Verantwortung gestaltet.

## Implementierter Ablauf

```text
Quelltext und parsergebundene exakte Literale
  -> PolynomialSemanticView
  -> CoefficientDomain
  -> PolynomialRing mit expliziter Monomordnung
  -> kanonisches SparsePolynomial
  -> FactorizationRequest
  -> FactorizationEngine
       -> untrusted Proposal
       -> BackendClaim
       -> kanonisches WorkLedger
  -> FactorizationVerifier
       -> Vertragsprüfung
       -> unabhängige exakte Produktrekonstruktion
       -> VerifiedCandidate
       -> verifier-ausgestellter Report
  -> Suchkante, Cache oder Bericht
```

Die Darstellung trennt vier Identitäten:

1. **Quellidentität:** konkrete Schreibweise, Stellenbereiche und
   Literalprovenienz;
2. **mathematische Polynomidentität:** Koeffizientendomäne, geordnete
   Variablen beziehungsweise strukturelle Atome, Monomordnung und kanonische
   Terme;
3. **Backendprovenienz:** Engine-ID, Engine-Zertifikate, Rohresultat-Hash,
   Backend-Claim und ausgeführte Engine-Arbeit;
4. **Regelsuche-Evidence:** unabhängig geprüfter Request, rekonstruierte
   Faktoren, Verifier-Zertifikat, Gesamtarbeit und autorisierte Claim-Stärke.

Eine andere Quellschreibweise verändert nicht automatisch das mathematische
Polynom. Eine andere Koeffizientendomäne, Variablenordnung oder Monomordnung
verändert dagegen den Ring und damit die mathematische beziehungsweise
algorithmische Identität.

## Koeffizientendomänen

`CoefficientDomain<C>` definiert exakte kanonische Arithmetik:

- stabile Domain-ID;
- Null und Eins;
- Addition, Negation und Multiplikation;
- kanonische Textdarstellung;
- Größenmaß für Budgets.

Zusätzliche algebraische Fähigkeiten besitzen getrennte Verträge:

- `ExactField<C>` für exakte Division durch Nichtnullwerte;
- `GcdDomain<C>` für ggT und geprüfte exakte Division.

Die erste Tranche enthält:

```text
regelsuche.coefficients.integer/v1
regelsuche.coefficients.rational/v1
```

Die rationale Implementierung verwendet den bereits autoritativen
`ExactRational`-Typ. Es existiert keine zweite Brucharithmetik und kein
`double`-Einstieg in die exakte Domäne.

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

`PolynomialSemanticView` v2 verwendet `ExpressionParser.parseExactTerm`.
Numerische Koeffizienten und Exponenten werden über die Identität des tatsächlich
vom Parser erzeugten `NumberExpr`-Knotens zu ihrer exakten Literal-Evidence
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

## Faktorisierungsanfrage

`FactorizationRequest<C>` enthält:

- das kanonische Quellpolynom;
- `EvidenceRequirement.VERIFIED_DECOMPOSITION` oder
  `EvidenceRequirement.INDEPENDENT_COMPLETE`;
- das maximale Kandidatenbudget;
- ein nicht zurücksetzbares Gesamtbudget in kanonischen Work Units.

Ein Backend-Claim erfüllt `INDEPENDENT_COMPLETE` niemals allein. Eine Engine,
die keine unabhängige Vollständigkeits- oder Irreduzibilitätsevidence liefern
kann, muss einen solchen Request als nicht unterstützt behandeln.

Grad-, Variablen-, Term- und Koeffizientengrößen sind am Polynom messbar. Ein
allgemeiner, requestweit einheitlicher Struktur-Budgetvertrag für zukünftige
beliebige Engines ist noch ein offener Punkt von Issue #763; die aktuelle
semantische View und die Quartikengine besitzen eigene enge Grenzen.

## Untrusted Engine-SPI

`FactorizationEngine<C>` arbeitet ausschließlich auf dem mathematischen
Polynom. Parser, AST und Benutzeranzeige gehören nicht zur Engine-Schnittstelle.
Dadurch können native Algorithmen und spätere externe Backendadapter dieselben
Requests bedienen.

Eine Engine gibt ein `EngineResult<C>` mit folgenden Bestandteilen zurück:

- `Outcome`;
- `detailCode`;
- stage-getrenntes `WorkLedger`;
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
eins behaupten. Widersprüchliche Rohresultate werden bereits als ungültige
Engineausgabe abgelehnt.

Diese Konstruktorinvarianten machen das Objekt strukturell wohldefiniert. Sie
machen seine mathematische Produktbehauptung oder seinen Backend-Claim noch
nicht vertrauenswürdig.

## Kanonisches Work Accounting

`WorkLedger` trennt Arbeit nach stabilen Stage-IDs, beispielsweise:

```text
engine.divisor-tests
engine.factor-pair-configurations
engine.middle-system-solves
verify.factor-power-multiplications
verify.factor-product-multiplications
verify.product-comparisons
```

Die Stage-Abbildung wird lexikographisch sortiert und danach als tatsächlich
reihenfolgeerhaltende unveränderliche Map gespeichert. Das kanonische
Hashmaterial hängt damit nicht von der nicht spezifizierten Iterationsreihenfolge
von `Map.copyOf` oder einer Hash-Map ab.

Engine- und Verifier-Arbeit werden unter demselben Requestbudget addiert. Eine
Engine kann das Budget daher nicht nach ihrer Kandidatenbildung zurücksetzen.
Reicht die verbleibende Arbeit nicht für die unabhängige Produktprüfung, lautet
das Resultat `BUDGET_INCONCLUSIVE`.

## Unabhängiger Verifier

`FactorizationVerifier.execute` ist die autoritative Grenze. Er prüft mindestens:

- Übereinstimmung von Engine- und Koeffizientendomänen-ID;
- Übereinstimmung der deklarierten und zurückgegebenen Engine-ID;
- Einhaltung von Work- und Kandidatenbudget;
- Ringgleichheit sämtlicher Faktoren und des ungelösten Restes;
- exakte Rückmultiplikation von Einheit, Faktorpotenzen und Rest;
- Trennung von Backend-Claim und angeforderter unabhängiger Evidence.

Erst danach entstehen private, issuer-owned Typen:

```text
FactorizationVerifier.VerifiedCandidate<C>
FactorizationVerifier.Report<C>
```

Callers und Engines können deren positiven Zustand nicht über öffentliche
Konstruktoren herstellen. Der Verifier bindet Quellpolynom, Request,
Engineprovenienz, Work Ledger, rekonstruierte Faktoren, Backend-Claim und sein
eigenes Zertifikat in das Report-Hashmaterial.

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
| `BUDGET_INCONCLUSIVE` | gemeinsames Engine-/Verifier-Budget reicht nicht aus |
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

`PolynomialDecompositionSynthesisOperator` ist nur noch ein Integrationsadapter:

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
Instanzen können weiterhin nur in den begrenzten
`PolynomialDerivedMacroCache`; sie werden nicht als neue Kernelgesetze
behandelt. Der Cache bindet das vollständige Work Ledger statt eines
quartitspezifischen Konfigurationszählers.

Der Discovery-Integrationstest prüft den erzeugten Pfad über mathematische
Wertgleichheit. Er hängt nicht von historischen Renderer-Teilstrings ab.

## Keine unnötige Rückwärtskompatibilität

Entfernt beziehungsweise ersetzt wurden insbesondere die bisherigen
verschachtelten Typen:

```text
PolynomialSemanticView.Atom
PolynomialSemanticView.Monomial
PolynomialSemanticView.Polynomial
PolynomialDecompositionSynthesisOperator.Candidate
PolynomialDecompositionSynthesisOperator.SynthesisReport
```

Es gibt keine deprecated Duplikate und keine Adapter, die den neuen Kern wieder
auf die alte Quartikoberfläche reduzieren. Der verbleibende
`PolynomialDecompositionSynthesisOperator` ist ein aktueller Search-Adapter mit
einer konkreten fachlichen Verantwortung, keine Kompatibilitätsfassade für die
entfernten Datentypen.

Ein Adapter wird künftig nur beibehalten, wenn ein konkretes externes
Serialisierungsformat oder ein unveränderliches historisches Experiment ihn
benötigt und dieser Eigentümer ausdrücklich dokumentiert ist.

## Nächste Ausbaustufen

Issue #763 verfolgt als erste vollständige Qualifikationsdomäne:

```text
Q[x]
```

mit beliebigem unterstützten Grad unter expliziten Budgets. Die geplante native
Pipeline umfasst:

1. Inhalt und primitiven Anteil;
2. Ableitung, Polynom-ggT und quadratfreie Zerlegung mit Multiplizitäten;
3. geeignete Primzahlauswahl mit retained Ablehnungsgründen;
4. Faktorisierung über endlichen Körpern;
5. Hensel-Lifting;
6. ganzzahlige Faktorrekomposition, zunächst Zassenhaus;
7. später LLL-/van-Hoeij-Rekombination, wenn qualifiziert;
8. rationale Reassemblierung;
9. unabhängige Produkt-, Vollständigkeits- und Irreduzibilitätsprüfung.

Parallel kann ein externer Backendadapter dieselben Requests bedienen. Seine
Ergebnisse bleiben Proposals, bis Regelsuche sie exakt rekonstruiert hat. Eine
spätere multivariate Pipeline nutzt denselben Domain-, Ring-, Engine- und
Verifiervertrag, erweitert aber die algorithmische Implementierung.

## Prüfung aus dem Checkout

Fokussierte Kernprüfungen:

```bash
./gradlew :regelsuche-core:test \
  --tests 'de.regelsuche.polynomial.*' \
  --tests de.regelsuche.parse.ExactExpressionFormatterTest \
  --tests de.regelsuche.transform.PolynomialDecompositionSynthesisOperatorTest \
  --tests de.regelsuche.transform.PolynomialTheorySubsumptionClassifierTest
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

Die erste Tranche belegt eine erweiterbare, exakte Faktorisierungsarchitektur,
einen fail-closed Engine-/Verifier-Vertrag und die Migration der bisherigen
Quartikzerlegung ohne parallele Alt-API. Sie belegt noch keine vollständige
Faktorisierung über `Q[x]`, keine multivariate Faktorisierung und keine
Überlegenheit gegenüber etablierten Computer-Algebra-Systemen.
