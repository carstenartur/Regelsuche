# Exakte Parser-zu-Faktorisierungs-Pipeline

**Implementierungsstand: 28. August 2026**

## Zweck

Die Pipeline verbindet einen konkreten mathematischen Quelltext ohne
Zwischendarstellung über `double`, gerenderten Text oder die historische
Quartik-API direkt mit der domänenbewussten Faktorisierungsarchitektur.

Der autoritative Fluss lautet:

```text
Quelltext
  -> ExpressionParser.parseExactTerm
  -> ExactParsedTerm mit parsergebundener Literalprovenienz
  -> ExactParsedUnivariatePolynomialView
  -> SparsePolynomial<ExactRational>
       in PolynomialRing<ExactRational>
  -> FactorizationRequest<ExactRational>
  -> ausgewählte FactorizationEngine<ExactRational>
  -> FactorizationVerifier
  -> verifier-ausgestellter Report und Kandidaten
```

Damit existiert kein paralleles öffentliches rationales Polynommodell für die
Faktorisierung. `SparsePolynomial` bleibt die mathematische Identität;
Quellbereiche und Literalzertifikate bleiben eine getrennte, gebundene
Provenienzschicht.

Die früher im nicht gemergten PR #759 vorgeschlagene
`ExactRationalPolynomial`-Zwischenarchitektur wird nicht übernommen. Ihre
nützlichen Parser- und Budgettests wurden auf die aktuelle Ring-, Request- und
Verifier-Grenze portiert.

## Exakte Syntaxgrenze

`ExpressionParser.parseExactTerm` erzeugt neben dem gewöhnlichen AST für jedes
numerische Quelltoken eine `LiteralOccurrence`. Sie bindet:

- die Identität des tatsächlich vom Parser erzeugten `NumberExpr`-Knotens;
- den exakten Quellbereich;
- die ursprüngliche Schreibweise;
- den kanonischen `ExactRational`-Wert;
- Value- und Zertifikatshash.

`ExactParsedUnivariatePolynomialView` löst numerische Koeffizienten und
Exponenten ausschließlich über diese Knotenidentität auf. Ein wertgleicher,
später erzeugter `NumberExpr` erbt keine Provenienz. Der historische
`NumberExpr.value()`-Wert autorisiert niemals einen exakten Koeffizienten.

Die einzige eng begrenzte Ausnahme ist die Erkennung des vom Parser intern für
Unary Minus erzeugten, provenancefreien Nullknotens. Dieser Knoten wird nicht
als Quellliteral ausgegeben und kann nur die bereits bekannte Parserstruktur

```text
0 - expression
```

kennzeichnen.

## Unterstütztes Fragment

Version 1 unterstützt:

- genau eine skalare Variable;
- exakte ganzzahlige und endliche dezimale Quellliterale;
- explizite Brüche aus exakten konstanten Teilausdrücken;
- Addition und Subtraktion;
- kommutative Multiplikation;
- Division durch einen exakten, von null verschiedenen konstanten Ausdruck;
- exakte nichtnegative ganzzahlige Potenzen;
- Unary Minus in der vorhandenen Parserdarstellung.

Beispielsweise wird

```text
0.10*x^4 - (3/4)*x^2 + 2
```

unmittelbar zu einem Polynom über
`regelsuche.coefficients.rational/v1` mit den Koeffizienten

```text
[2, 0, -3/4, 0, 1/10]
```

und der Ringvariablen `x`.

Eine Nullpotenz bewahrt den deklarierten Ring. `x^0` wird daher als konstantes
Eins-Polynom im Ring `Q[x]` und nicht als provenancefreies Polynom in einem
nullvariablen Ring dargestellt.

## Fail-Closed-Grenzen

Die View lehnt insbesondere ab:

- mehr als eine Variable;
- Funktionsanwendungen und andere nichtpolynomiale AST-Arten;
- variable oder nullwertige Divisoren;
- negative, gebrochene, symbolische oder provenancefreie Exponenten;
- numerische AST-Knoten ohne parserausgestellte exakte Provenienz;
- überschrittene Grad-, Koeffizienten-, AST-Besuchs- oder Arithmetikbudgets.

Die Ergebnisarten sind getrennt:

```text
SUPPORTED
UNSUPPORTED
BUDGET_INCONCLUSIVE
```

Eine Budgeterschöpfung wird weder als Nichtanwendbarkeit noch als
Irreduzibilität interpretiert. Fehlgeschlagene Analysen exponieren kein
Teilpolynom.

Null- und konstante Polynome können korrekt extrahiert werden, werden aber vor
der Engine-Ausführung als nichttriviale Faktorisierungsanfrage abgelehnt. Die
Engine wird in diesen Fällen nicht aufgerufen.

## Ein gemeinsames Arbeitsbudget

`ExactParsedFactorizationPipeline` besitzt ein einziges
`maxTotalWorkUnits`-Budget. Die Reihenfolge ist verbindlich:

1. AST-Besuche und exakte Koeffizientenarithmetik werden belastet;
2. nur das verbleibende Budget wird in den `FactorizationRequest` geschrieben;
3. Backend und `FactorizationVerifier` teilen dieses Restbudget;
4. das Pipeline-Ergebnis vereinigt alle Stufen in einem
   `PolynomialWorkLedger`.

Bereits beim Aufbau der Pipeline wird fail-closed geprüft, dass die Summe aus
`maxVisitedNodes` und `maxArithmeticOperations` der konfigurierten View nicht
größer als `maxTotalWorkUnits` ist. Ein engeres Gesamtbudget muss daher mit
einem entsprechend engeren View-Budget kombiniert werden. Eine widersprüchliche
Konfiguration wird vor jeder Extraktion mit
`PIPELINE_TOTAL_WORK_BELOW_EXTRACTION_CEILING` abgewiesen. Dadurch kann die
Extraktion die gemeinsame Autorität nicht bereits vor der Request-Erzeugung
überschreiten.

Formal gilt:

```text
extraction work
+ engine work
+ independent verification work
<= pipeline maxTotalWorkUnits
```

Ist nach der Extraktion keine Work Unit mehr verfügbar, endet der Lauf als
`BUDGET_INCONCLUSIVE`; ein neues oder vergrößertes Request-Budget wird nicht
erzeugt.

Die Standardkonfiguration bindet:

- Extraktionsgrad höchstens 16; die harte View-Obergrenze liegt bei 64;
- höchstens 4.096 Koeffizientenbits in der Standardextraktion;
- höchstens 512 AST-Besuche und 10.000 exakte Arithmetikoperationen;
- Request-Strukturlimit von höchstens einer Variable, Grad 64 und 65 Termen;
- höchstens 8.192 Koeffizientenbits auf der Request-Strukturgrenze;
- höchstens 250.000 Kandidaten;
- höchstens 20.000.000 gesamte Work Units;
- `VERIFIED_DECOMPOSITION` als Evidence-Anforderung.

Engere Produkt- oder Experimentprofile können eine eigene unveränderliche
`Policy` und ein dazu passendes View-Budget einsetzen.

## Evidence und Identität

Die Extraction-Evidence bindet:

- View-ID und vollständiges Budget;
- Quelltext;
- kanonisches `SparsePolynomial` einschließlich Domain, Ring und Monomordnung;
- alle Literalbereiche, Lexeme, exakten Werte und Parserzertifikate;
- AST- und Arithmetikarbeit;
- einen SHA-256-Zertifikatshash.

Das Pipeline-Ergebnis bindet zusätzlich:

- Pipeline-ID und Policy;
- Engine-ID;
- vollständigen `FactorizationRequest`;
- den `FactorizationVerifier`-Reporthash;
- die zusammengeführte Gesamtarbeit;
- einen eigenen SHA-256-Zertifikatshash.

Bei einer nicht ausgeführten Anfrage bleiben Request und Report leer. Dadurch
kann ein Consumer nicht aus einer erfolgreichen Extraktion versehentlich eine
stattgefundene Faktorisierung ableiten.

## Engine-Grenze

Der Core hängt nur von `FactorizationEngine<ExactRational>` ab. Dadurch kann
dieselbe Quell- und Evidence-Grenze verwendet werden für:

- `NativeUnivariateFactorizationEngine.boundedRationals()`;
- den optionalen GraalPy-/SymPy-Adapter;
- spätere typisierte Engines unter derselben Verifier-Grenze.

Eine Engine liefert weiterhin nur Vorschläge und Backend-Claims. Die Pipeline
überspringt `FactorizationVerifier` nicht. Auch bei der nativen Engine bleibt
`BACKEND_CLAIMED_COMPLETE` von einem algorithmisch unabhängigen
Vollständigkeitszertifikat unterschieden.

## Qualifikation

Die fokussierten Tests decken ab:

- exakte Dezimal- und Bruchkoeffizienten;
- Produkte, Potenzen und konstante Division;
- Exponentiation durch Quadrieren unter engem Work-Budget;
- Unary Minus und Nullpotenzen;
- Mehrvariablen-, Funktions- und Divisor-Ablehnungen;
- deterministische AST-, Arithmetik- und Koeffizientengrenzen;
- fail-closed Ablehnung eines Gesamtbudgets unterhalb des deklarierten
  Extraktionsmaximums;
- Restbudgetübergabe an genau einen typisierten Request;
- kein Budgetreset, wenn eine erfolgreiche Extraktion die gesamte Autorität
  verbraucht;
- keine Engine-Ausführung für ungeeignete oder triviale Quellen;
- End-to-End-Faktorisierung eines parsergebundenen Polynoms in `Q[x]` durch die
  native Engine;
- exakte Rekonstruktion des ursprünglichen `SparsePolynomial` aus Einheit,
  Faktoren und Multiplizitäten.

Fokussierte Maven-Aufrufe:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-core -am \
  -Dtest=ExactParsedUnivariatePolynomialViewTest,ExactParsedFactorizationPipelineTest \
  test

mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-math-algorithms -am \
  -Dtest=ExactParsedNativeFactorizationIntegrationTest \
  test
```

Der Merge bleibt zusätzlich an den vollständigen checkout-eigenen CI-Lifecycle
und den Maven-/Produkt-/Docker-Vertrag gebunden.

## Offene Integration

Dieser Slice erzeugt noch keine allgemeine Suchkante und rendert keine Faktoren
in die historische AST-Schreibweise zurück. Der nächste Produkt-Slice muss:

1. einen verifier-ausgestellten Kandidaten occurrence-erhaltend rendern;
2. das gerenderte Ergebnis erneut exakt in der deklarierten Ringdomäne
   rekonstruieren;
3. Quell-, View-, Request-, Engine-, Verifier- und Rendering-Evidence in einer
   Transformationskandidatenidentität binden;
4. direkte Faktorisierung, Cache-Makro und Nichtanwendung unter gleichem Work-
   und Informationsbudget vergleichen.

## Claim-Grenze

Die Pipeline belegt eine exakte, budgetierte und reproduzierbare Verbindung von
parsergebundener rationaler Syntax zur bestehenden Faktorisierungs- und
Verifierarchitektur. Sie belegt nicht:

- algorithmisch unabhängige Vollständigkeit oder Irreduzibilität in `Q[x]`;
- multivariate Faktorisierung;
- universelle CAS-Parität;
- automatische mathematische Neuheit;
- einen Vorteil der Suchintegration, bevor die eingefrorene Vergleichsstudie
  ausgeführt wurde.
