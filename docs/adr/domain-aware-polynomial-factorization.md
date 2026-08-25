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
- eine saubere Trennung von Produktgleichheit, Irreduzibilität und
  Vollständigkeit;
- multivariate Polynome.

Es gibt keine bekannten externen Nutzer. Quell- oder Binärkompatibilität zu den
alten internen Typen besitzt daher keinen eigenständigen Produktwert. Das
Beibehalten paralleler Alt-APIs würde dagegen Zuständigkeiten duplizieren,
exakte Werte erneut durch historische `double`-Grenzen führen und spätere
Engines auf eine Quartikoberfläche reduzieren.

## Entscheidung

Regelsuche ersetzt die alte Polynom- und Zerlegungsoberfläche durch folgende
Schichten:

```text
ExactParsedTerm
  -> PolynomialSemanticView
  -> CoefficientDomain + PolynomialRing + SparsePolynomial
  -> FactorizationRequest
  -> FactorizationEngine
  -> FactorizationReport + FactorizationCandidate
  -> FactorizationVerifier
  -> Ausdrucksadapter / Suche / Cache
```

### Mathematische Identität

Ein mathematisches Polynom enthält nur:

- die stabile Koeffizientendomäne;
- die geordnete Variablen- beziehungsweise Atomidentität;
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

### Claim-Trennung

Eine korrekte Rückmultiplikation belegt nur eine Zerlegung. Vollständigkeit und
Irreduzibilität werden getrennt durch folgende Stärken repräsentiert:

```text
DECOMPOSITION_ONLY
BACKEND_CLAIMED_COMPLETE
INDEPENDENTLY_CERTIFIED_COMPLETE
```

Ein Engine-Miss wird nicht als Irreduzibilität ausgegeben.

### Kompatibilität

Die alten verschachtelten Typen werden entfernt. Es entstehen keine deprecated
Doppel-APIs und keine Rückadapter. Historische Artefakte behalten ihre bereits
eingefrorenen IDs; das aktuelle Laufzeitsystem muss ihre ehemalige Java-API
nicht weiterführen.

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

### Ausschließlich externes CAS als Blackbox

Verworfen. Ein externes Backend kann später Kandidaten liefern, darf aber
Produktgleichheit, Irreduzibilität oder Vollständigkeit nicht allein durch eine
untypisierte Textausgabe autorisieren.

### Vollständige CAS-Implementierung vor jeder Integration

Verworfen. Die Architektur wird zuerst durch Migration einer vorhandenen
Fähigkeit qualifiziert. Vollständige univariate Faktorisierung wird anschließend
inkrementell hinter denselben Verträgen implementiert.

## Konsequenzen

### Positiv

- neue Koeffizientendomänen und Engines besitzen einen klaren Erweiterungspunkt;
- Parser-, Algebra-, Engine-, Evidence- und Renderverantwortung sind getrennt;
- exakte Werte können die historische AST-`double`-Grenze umgehen;
- externe Backends lassen sich nutzen, ohne ihre stärkeren Claims ungeprüft zu
  übernehmen;
- Such- und Lernsysteme konsumieren ein einheitliches Faktorisierungsergebnis;
- die Quartikengine kann später neben vollständigen univariaten und
  multivariaten Engines bestehen.

### Negativ

- interne Aufrufer und Tests müssen unmittelbar migriert werden;
- historische Java-Typnamen sind nicht mehr verfügbar;
- der erste PR vergrößert primär die Architekturqualität, nicht sofort die
  vollständig faktorisierbare Domäne;
- kanonische IDs der neuen Laufzeitpfade ändern sich absichtlich.

## Folgearbeiten

Issue #763 verfolgt:

1. vollständige univariate Faktorisierung über `Z[x]` und `Q[x]`;
2. quadratfreie Zerlegung und Multiplizitäten;
3. endliche-Körper-Faktorisierung, Hensel-Lifting und Rekombination;
4. ein Backendportfolio mit exakter interner Rückprüfung;
5. multivariate Erweiterung und gehaltene Qualifikation.

## Aussagegrenze

Die Entscheidung verbessert Erweiterbarkeit und Korrektheitsgrenzen. Sie ist
für sich kein Leistungs- oder Überlegenheitsnachweis gegenüber einem CAS.
