# ADR: Domänenbewusster Polynomkern statt Quartik-API

- **Status:** akzeptiert
- **Datum:** 25. August 2026
- **Entscheidungsträger:** Projektmaintainer
- **Umsetzung:** Issue #763, PR #764

## Kontext

Die erste allgemeine Polynomzerlegung in Regelsuche wurde für binäre homogene
Quartiken entwickelt. `PolynomialSemanticView` besaß ein eigenes verschachteltes
Polynommodell; `PolynomialDecompositionSynthesisOperator` vereinte
Koeffizientenlösung, Faktorisierungsstatus, Kandidaten, Zertifikat und
Syntaxrendering in einer Klasse.

Diese Struktur war für den ersten eng begrenzten Fähigkeitsnachweis geeignet,
aber kein tragfähiger Ausgangspunkt für:

- beliebige univariate Grade;
- vollständige Faktorisierung über `Z[x]` und `Q[x]`;
- Multiplizitäten und quadratfreie Zerlegung;
- endliche Körper und algebraische Erweiterungen;
- mehrere native oder externe Faktorisierungsengines;
- eine saubere Trennung von Produktgleichheit, Backend-Claim,
  Irreduzibilität und Vollständigkeit;
- multivariate Polynome.

Es gibt keine bekannten externen Nutzer. Quell- oder Binärkompatibilität zu den
alten internen Typen besitzt daher keinen eigenständigen Produktwert. Das
Beibehalten paralleler Alt-APIs würde Zuständigkeiten duplizieren, exakte Werte
erneut durch historische `double`-Grenzen führen und spätere Engines auf eine
Quartikoberfläche reduzieren.

## Entscheidung

Regelsuche ersetzt die alte Polynom- und Zerlegungsoberfläche durch folgende
Schichten:

```text
ExactParsedTerm
  -> PolynomialSemanticView
  -> CoefficientDomain
  -> PolynomialRing mit expliziter Monomordnung
  -> SparsePolynomial
  -> FactorizationRequest
  -> FactorizationEngine
       -> untrusted Proposal
       -> BackendClaim
       -> WorkLedger
  -> FactorizationVerifier
       -> Vertragsprüfung
       -> exakte Produktrekonstruktion
       -> VerifiedCandidate
       -> verifier-ausgestellter Report
  -> Ausdrucksadapter / Suche / Cache
```

### Mathematische Identität

Ein mathematisches Polynom enthält nur:

- die stabile Koeffizientendomäne;
- die geordnete Variablen- beziehungsweise Atomidentität;
- die ausdrücklich gewählte Monomordnung;
- kanonische Monome und Koeffizienten.

Quellstellen, konkrete AST-Knoten und Anzeigezeichenfolgen gehören in separate
View- und Provenienzobjekte.

### Exakte Eingabe

Exakte Koeffizienten und Exponenten werden ausschließlich aus
parserausgestellter Literal-Evidence gelesen. `NumberExpr(double)` bleibt Teil
des historischen AST, ist aber keine exakte mathematische Quelle.

### Engine-SPI

Jede Faktorisierungsmethode ist eine Engine hinter einem typisierten Request.
Der vorhandene Quartiksolver wird als erste Engine migriert. Er ist nicht länger
das zentrale Polynommodell.

Eine Engine ist keine Evidence-Autorität. Sie liefert strukturell kanonische,
aber mathematisch untrusted Proposals sowie ausdrücklich retained Backend-Claims.
Widersprüchliche Rohresultate — etwa ein Vollständigkeitsclaim mit ungelöstem
Rest — werden fail-closed abgelehnt.

### Verifier-Autorität

Nur `FactorizationVerifier` darf positive Faktorisierungsevidence ausstellen. Er
prüft unabhängig:

- Engine- und Koeffizientendomänenidentität;
- Request-, Kandidaten- und Work-Budgets;
- Ringgleichheit;
- exakte Rückmultiplikation von Einheit, Faktoren, Multiplizitäten und Rest;
- die Trennung von Backend-Claim und geforderter unabhängiger Evidence.

Positive `VerifiedCandidate`- und `Report`-Zustände besitzen keine öffentlichen
Konstruktoren.

### Claim-Trennung

Eine korrekte Rückmultiplikation belegt nur eine Zerlegung. Der Verifier hält
folgende Stärken getrennt:

```text
VERIFIED_DECOMPOSITION
BACKEND_CLAIMED_COMPLETE
BACKEND_CLAIMED_IRREDUCIBLE
INDEPENDENTLY_CERTIFIED_COMPLETE
INDEPENDENTLY_CERTIFIED_IRREDUCIBLE
```

Ein Engine-Miss wird nicht als Irreduzibilität ausgegeben. Ein Backend-Claim
erfüllt keinen `INDEPENDENT_COMPLETE`-Request.

### Kanonisches Work Accounting

Engine und Verifier teilen ein nicht zurücksetzbares Gesamtbudget. Das
stage-getrennte `WorkLedger` besitzt eine kanonisch sortierte und tatsächlich
reihenfolgeerhaltende unveränderliche Map. Evidence-Hashes dürfen nicht von der
nicht spezifizierten Iterationsreihenfolge einer Hash- oder `Map.copyOf`-Map
abhängen.

### Kompatibilität

Die alten verschachtelten Typen werden entfernt. Es entstehen keine deprecated
Doppel-APIs und keine Rückadapter. Historische Artefakte behalten ihre bereits
eingefrorenen IDs; das aktuelle Laufzeitsystem muss ihre ehemalige Java-API
nicht weiterführen.

Der verbleibende `PolynomialDecompositionSynthesisOperator` ist ein aktueller
Ausdrucks- und Suchadapter für eine konkrete Engine, keine
Kompatibilitätsfassade für die entfernten Polynom- und Reporttypen.

Ein späterer Adapter ist nur gerechtfertigt, wenn er einen konkret benannten
externen Vertrag oder ein unveränderliches serialisiertes Artefakt bedient.

## Alternativen

### Weitere Faktorgradschablonen im bisherigen Operator

Verworfen. Dies würde den Fähigkeitsumfang vergrößern, aber Domänen,
Multiplizitäten, Vollständigkeit und Backendauswahl weiterhin in einem
Quartikoperator vermischen.

### Neue Architektur hinter vollständigen Alt-Adaptern

Verworfen. Ohne Nutzer entstünde dauerhafte doppelte Oberfläche ohne
Migrationsnutzen. Tests müssten beide Modelle charakterisieren und spätere
Entwickler könnten versehentlich wieder die schwächere API wählen.

### Engine-Ergebnis direkt als vertrauenswürdiger Kandidat

Verworfen. Native und externe Engines würden dadurch ihre eigenen
Produkt-, Vollständigkeits- oder Irreduzibilitätsclaims autorisieren. Ein
separater Verifier ist die notwendige Trust-Grenze.

### Ausschließlich externes CAS als Blackbox

Verworfen. Ein externes Backend kann später Proposals liefern, darf aber
Produktgleichheit, Irreduzibilität oder Vollständigkeit nicht allein durch eine
untypisierte Textausgabe autorisieren.

### Vollständige CAS-Implementierung vor jeder Integration

Verworfen. Die Architektur wird zuerst durch Migration einer vorhandenen
Fähigkeit qualifiziert. Vollständige univariate Faktorisierung wird anschließend
inkrementell hinter denselben Verträgen implementiert.

## Konsequenzen

### Positiv

- neue Koeffizientendomänen und Engines besitzen einen klaren Erweiterungspunkt;
- Parser-, Algebra-, Engine-, Verifier-, Evidence- und Renderverantwortung sind
  getrennt;
- exakte Werte umgehen die historische AST-`double`-Grenze;
- externe Backends lassen sich nutzen, ohne ihre stärkeren Claims ungeprüft zu
  übernehmen;
- Such- und Lernsysteme konsumieren ein einheitliches verifiziertes Ergebnis;
- die Quartikengine kann später neben vollständigen univariaten und
  multivariaten Engines bestehen;
- kanonisches Work Accounting und Evidence-Hashing sind backendübergreifend
  möglich.

### Negativ

- interne Aufrufer und Tests müssen unmittelbar migriert werden;
- historische Java-Typnamen sind nicht mehr verfügbar;
- jede Engineintegration benötigt zusätzliche Proposal-, Work- und
  Verifier-Verträge;
- der erste PR vergrößert primär Architekturqualität und Korrektheitsgrenzen,
  nicht sofort die vollständig faktorisierbare Domäne;
- kanonische IDs der neuen Laufzeitpfade ändern sich absichtlich.

## Folgearbeiten

Issue #763 verfolgt:

1. requestweite Struktur-Budgets für Grad, Variablenzahl, Termzahl und
   Koeffizientengröße;
2. vollständige univariate Faktorisierung über `Z[x]` und `Q[x]`;
3. quadratfreie Zerlegung und Multiplizitäten;
4. endliche-Körper-Faktorisierung, Hensel-Lifting und Rekombination;
5. ein Backendportfolio mit exakter interner Rückprüfung und
   Umgebungsprovenienz;
6. multivariate Erweiterung und gehaltene Qualifikation.

## Aussagegrenze

Die Entscheidung verbessert Erweiterbarkeit, Determinismus und
Korrektheitsgrenzen. Sie ist für sich kein Leistungs- oder
Überlegenheitsnachweis gegenüber einem Computer-Algebra-System.
