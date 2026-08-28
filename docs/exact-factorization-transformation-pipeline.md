# Exakte Faktorisierungs-Transformationspipeline

**Implementierungsstand: 28. August 2026**

## Zweck

Die Transformationspipeline bildet die Produktgrenze zwischen einer bereits
verifizierten exakten Faktorisierung und einer tatsächlich in Regelsuche
verwendbaren Ausdruckstransformation.

Eine mathematisch korrekte Faktorisierung ist noch keine sichere Suchkante. Vor
der Integration muss zusätzlich belegt werden, dass

- der ausgewählte Faktorisierungskandidat wirklich zu genau diesem
  parsergebundenen Quellausdruck gehört;
- die Faktoren ohne Rundung in eine vom Parser akzeptierte Schreibweise
  überführt wurden;
- der gerenderte Ausdruck nach erneutem exaktem Parsen dasselbe Polynom im
  selben Ring ergibt;
- Faktorisierung, Rendering und Rekonstruktion unter einer einzigen
  nicht zurückgesetzten Arbeitsautorität ausgeführt wurden.

Der autoritative Fluss lautet:

```text
ExactParsedTerm der ausgewählten Quellvorkommens
  -> ExactParsedFactorizationPipeline.Result
  -> explizit ausgewählter FactorizationVerifier.VerifiedCandidate
  -> ExactFactorizationExpressionRenderer
  -> ExpressionParser.parseExactTerm
  -> ExactParsedUnivariatePolynomialView
  -> exakte Ring- und Polynomgleichheit
  -> ExactFactorizationTransformationPipeline.Result
```

Der gerenderte Text ist dabei niemals mathematische Autorität. Erst die
unabhängige Rekonstruktion im ursprünglichen `PolynomialRing<ExactRational>`
autorisiert die Transformationskante.

## Quell- und Vorkommensbindung

Version 1 zielt bewusst zunächst auf das Wurzelvorkommen. Die
`OccurrenceEvidence` enthält:

- den leeren Wurzelpfad;
- den unveränderten ursprünglichen Quelltext;
- einen Inhaltshash über Quelltext und sämtliche exakten Literalbelege.

Vor dem Rendering werden der aktuelle `ExactParsedTerm` und die bereits in der
Faktorisierung gebundene Extraktion verglichen. Übereinstimmen müssen:

- Quelltext;
- Anzahl und Reihenfolge der numerischen Literale;
- Start- und Endposition jedes Literals;
- ursprüngliches Lexem;
- kanonischer exakter Wert;
- Value-ID;
- Parserzertifikat;
- extrahiertes Polynom und `FactorizationRequest`.

Ein nachträglich ersetzter oder nur wertähnlicher Quellausdruck erhält daher
nicht stillschweigend die Faktorisierungsevidence eines anderen
Vorkommens. Der Lauf endet als `SOURCE_EVIDENCE_MISMATCH`.

Für verschachtelte Vorkommen wird später die vorhandene pfadbasierte
Local-Rewrite-Infrastruktur mit ihrem Stalenessschutz wiederverwendet. Es wird
kein zweites konkurrierendes Vorkommensmodell eingeführt.

## Explizite Kandidatenauswahl

Ein `FactorizationVerifier.Report` kann grundsätzlich mehrere verifizierte
Kandidaten enthalten. Die parameterlose Wurzeltransformation verwendet einen
Kandidaten nur dann automatisch, wenn genau einer vorhanden ist.

Bei mehreren Kandidaten lautet das Ergebnis:

```text
MULTIPLE_CANDIDATES_REQUIRE_EXPLICIT_SELECTION
```

Der Aufrufer muss anschließend den Kandidatenindex explizit angeben. Dadurch
entsteht keine versteckte Best-of-Auswahl, die ein Experiment oder eine
Suchstrategie nach Kenntnis der Ergebnisse bevorzugen könnte.

Die Transformationsart unterscheidet:

```text
VERIFIED_DECOMPOSITION
VERIFIED_DECOMPOSITION_WITH_COMPLETE_BACKEND_CLAIM
```

Ein Backend-Claim bleibt auch nach erfolgreichem Rendering ein Backend-Claim.
Er wird nicht in ein algorithmisch unabhängiges Vollständigkeitszertifikat
umgedeutet.

## Exaktes deterministisches Rendering

`ExactFactorizationExpressionRenderer` akzeptiert ausschließlich einen vom
`FactorizationVerifier` ausgestellten `VerifiedCandidate<ExactRational>`.

Gerendert werden in kanonischer Reihenfolge:

1. die Einheit, sofern sie nicht eins ist;
2. alle Faktoren mit positiver Multiplizität;
3. ein zulässiger nichttrivialer unresolved remainder, sofern vorhanden.

Die Schreibregeln sind deterministisch:

- ganze Zahlen werden als ganze Zahlen ausgegeben;
- nichtganzzahlige rationale Werte werden immer als expliziter Bruch
  `(Zähler / Nenner)` geschrieben;
- es gibt keine Konvertierung über `double`, gerundete Dezimalzahlen oder eine
  Locale;
- negative Vorzeichen, Multiplikation, Addition und Subtraktion werden
  kanonisch gesetzt;
- jeder Polynomfaktor wird geklammert;
- Multiplizitäten größer eins werden als Potenzen ausgegeben;
- Koeffizient eins vor einer positiven Variablenpotenz wird ausgelassen;
- Monome folgen der bereits im Ring festgelegten Ordnung.

Beispiele:

```text
unit = 1/3, factor = x - 1, multiplicity = 2
-> (1 / 3) * (x - 1) ^ 2

unit = -1/2, factor = 2*x + 3/5
-> (-1 / 2) * (2 * x + (3 / 5))
```

Unterstützt wird in Version 1 ein exakter rationaler Ring mit genau einer
parserkompatiblen Variablen. Multivariate Ringe, fremde Koeffizientendomänen und
nicht parserkompatible Variablennamen werden ausdrücklich abgelehnt.

## Darstellungs- und Arbeitsgrenzen

Die unveränderliche Renderer-Policy begrenzt:

- Anzahl der Faktoren;
- gesamte Zahl gerenderter Polynomterme;
- Exponenten und Multiplizitäten;
- Koeffizientenbitlänge;
- ausgegebene UTF-16-Codeeinheiten;
- kanonische Rendering-Work-Units.

Die Standardwerte sind:

```text
maxFactors            = 256
maxPolynomialTerms    = 4096
maxExponent           = 64
maxCoefficientBits    = 8192
maxOutputCodeUnits    = 100000
maxWorkUnits          = 200000
```

Grenzverletzungen werden als `BUDGET_INCONCLUSIVE` berichtet. Ein zu großer
Ausdruck wird weder abgeschnitten noch teilweise als Transformation ausgegeben.
Das kumulative Termlimit wird vor der Traversierung des jeweils nächsten
Polynoms geprüft. Ein bereits erkennbar zu großes Polynom wird daher nicht erst
vollständig durchlaufen.

Der Renderer führt ein stufenspezifisches `PolynomialWorkLedger`, unter anderem
für:

- inspizierte Koeffizienten und Polynomterme;
- Faktorrecords;
- Einheit und unresolved remainder;
- ausgegebene Polynomterme;
- Monom- und Faktorexponenten;
- ausgegebene Codeeinheiten.

Damit gehört auch die Validierung vor dem eigentlichen Textaufbau zur
nicht zurücksetzbaren Ressourcenautorität.

## Ein nicht zurücksetzbares Gesamtbudget

Die Transformationspipeline erzeugt keine neue Arbeitsautorität. Sie übernimmt
das bereits im `ExactParsedFactorizationPipeline.Result` gebundene
`maxTotalWorkUnits`.

Vor Beginn wird konservativ geprüft, ob das verbleibende Budget den vollständig
deklarierten Fortsetzungsrahmen tragen kann:

```text
max renderer work
+ max parser input code units
+ max exact reparse AST visits
+ max exact reparse arithmetic operations
<= remaining original work authority
```

Ist diese Bedingung nicht erfüllt, endet der Lauf vor dem Rendering mit:

```text
INSUFFICIENT_REMAINING_TRANSFORMATION_AUTHORITY
```

Bei erfolgreicher Ausführung gilt:

```text
extraction work
+ factorization engine work
+ independent product verification work
+ rendering validation and output work
+ exact-parser input work
+ reconstruction work
<= original pipeline maxTotalWorkUnits
```

Es gibt keinen Budgetreset zwischen den Stufen.

## Exaktes Reparse und Rekonstruktion

Der vollständige gerenderte Ausdruck wird durch
`ExpressionParser.parseExactTerm` erneut geparst. Anschließend extrahiert eine
neue `ExactParsedUnivariatePolynomialView` das Polynom ausschließlich aus der
neu erzeugten exakten Literalprovenienz.

Eine Transformation wird nur ausgestellt, wenn:

- das Rendering vollständig exakt parsebar ist;
- die Reparse-View den Ausdruck unterstützt;
- der rekonstruierte Ring dem Ring des ursprünglichen Requests exakt gleicht;
- das rekonstruierte `SparsePolynomial<ExactRational>` dem ursprünglichen
  Request-Polynom exakt gleicht;
- die zusammengeführte Arbeit innerhalb der ursprünglichen Autorität bleibt.

Parser-, Ring- oder Koeffizientendrift wird als technischer Invariantenfehler
sichtbar und nicht als erfolgreiche Faktorisierung behandelt.

Ein fehlgeschlagener späterer Reparse- oder Rekonstruktionsschritt kann den
Renderertext zu Diagnosezwecken über `renderedExpression()` behalten.
`transformedExpression()` bleibt in diesem Fall jedoch leer. Nur ein
`TRANSFORMED`-Ergebnis darf eine autorisierte Ersatzdarstellung ausgeben.

## Ergebnisarten

Die Transformationspipeline unterscheidet:

```text
TRANSFORMED
NO_CANDIDATE
BACKEND_CLAIMED_IRREDUCIBLE
IRREDUCIBLE
UNSUPPORTED
BUDGET_INCONCLUSIVE
TECHNICAL_FAILURE
SOURCE_EVIDENCE_MISMATCH
```

`BACKEND_CLAIMED_IRREDUCIBLE` bewahrt ausdrücklich nur den vom Backend
berichteten Claim. `IRREDUCIBLE` bleibt einem vom `FactorizationVerifier` als
unabhängig zertifiziert ausgegebenen Irreduzibilitätsergebnis vorbehalten. Die
Transformationspipeline wertet einen Backend-Claim niemals zu diesem Status
auf.

Insbesondere gilt:

- ein Backend-Irreduzibilitätsclaim erzeugt keine Identitätstransformation;
- ein leeres Kandidatenergebnis erzeugt keine erfundene Faktorisierung;
- ein Budgetende wird nicht als Irreduzibilität interpretiert;
- ein technischer Rendering- oder Reparsefehler bleibt ein technischer Fehler.

## Inhaltadressierte Transformationsevidence

Ein erfolgreicher `Result` bindet:

- Transformations-ID, Status und Transformationsart;
- Wurzelvorkommen und Quellbeleg;
- vollständigen Zertifikatshash der Parser-zu-Faktorisierungs-Pipeline;
- expliziten Kandidatenindex;
- Verifier-Zertifikat des Kandidaten;
- Renderer-Zertifikat und gerenderten Ausdruck;
- erneut geparsten Quelltext;
- Zertifikat der unabhängigen Polynomrekonstruktion;
- vollständiges zusammengeführtes Work-Ledger;
- einen eigenen SHA-256-Zertifikatshash.

Workbench, Graph- und Replay-Oberflächen können damit später zwischen
ursprünglicher Notation, ausgewähltem Vorkommen, verifier-ausgestellten Faktoren
und exakt rekonstruierter Ersatzdarstellung navigieren, ohne den Text selbst
zur Beweisinstanz zu machen.

## Qualifikation

Die fokussierten Tests decken ab:

- ganzzahlige und rationale Einheiten;
- negative Vorzeichen;
- nichtmonische Faktoren;
- wiederholte Faktoren und Multiplizitäten;
- vollständige und partielle Zerlegungen mit unresolved remainder;
- deterministische Render- und Transformationszertifikate;
- exaktes Reparse und Gleichheit des rekonstruierten Polynoms;
- Ablehnung ersetzter Quellbelege;
- getrennte No-Candidate-, Backend-Claim- und unabhängig zertifizierte
  Irreduzibilitätsgrenzen;
- diagnostisch vorhandener Renderertext ohne Ausgabe einer abgelehnten
  `transformedExpression`;
- frühzeitige Termgrenzen und abgerechnete Validierungsarbeit;
- Ausgabe- und Fortsetzungsbudgeterschöpfung;
- Ablehnung multivariater Kandidaten;
- den vollständigen nativen `Q[x]`-Pfad vom Quelltext bis zur rekonstruierten
  Faktorisierungsdarstellung.

Fokussierte Maven-Aufrufe:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-core -am \
  -Dtest=ExactFactorizationExpressionRendererTest,ExactFactorizationTransformationPipelineTest \
  test

mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-math-algorithms -am \
  -Dtest=ExactFactorizationNativeRenderingIntegrationTest \
  test
```

Vor einem Merge bleiben zusätzlich der vollständige checkout-eigene
CI-Lifecycle und der Maven-/Produkt-/Docker-Vertrag verbindlich.

## Offene Integration

Nach diesem Slice bleiben insbesondere offen:

1. verschachtelte Vorkommen über den bestehenden `TreePosition`- und
   Stalenessschutzpfad;
2. eine stabile Suchkantenidentität für On-Demand-Ausführung und
   verifier-gebundenes Cache-Makro-Replay;
3. die in #748 geforderte eingefrorene Gegenüberstellung von
   `NO_FACTORIZATION`, `ON_DEMAND_VERIFIED_FACTORIZATION` und
   `VERIFIED_DERIVED_MACRO_CACHE` unter gleicher sichtbarer Information und
   kanonischer Arbeit;
4. algorithmisch unabhängige Vollständigkeits- oder
   Irreduzibilitätszertifikate für den ursprünglichen `Z[x]`-/`Q[x]`-Request.

## Claim-Grenze

Die Pipeline belegt, dass ein verifier-ausgestellter exakter
Faktorisierungskandidat deterministisch in Parser-Syntax überführt und durch
eine unabhängige exakte Ringrekonstruktion als Ausdruckstransformation
autorisiert werden kann.

Sie belegt nicht:

- universelle oder multivariate Faktorisierung;
- algorithmisch unabhängige Vollständigkeit oder Irreduzibilität;
- externe mathematische Neuheit;
- einen Suchvorteil vor der eingefrorenen Vergleichsstudie;
- die Rechtfertigung eines standardmäßig aktivierten Suchprofils.
