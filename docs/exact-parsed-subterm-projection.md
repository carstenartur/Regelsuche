# Exakte Projektion parsergebundener Teilvorkommen

**Implementierungsstand: 28. August 2026**

## Zweck

Die parsergebundenen AST-Quellbereiche aus #782 unterscheiden konkrete
Vorkommen desselben strukturellen Ausdrucks. Für verschachtelte mathematische
Operationen muss ein ausgewähltes Vorkommen anschließend als eigener exakter
Parser-Companion verarbeitet werden können, ohne seine numerischen Werte über
den allgemeinen Formatter, einen erneuten Parserlauf oder `double`
zurückzugewinnen.

`ExactParsedSubtermProjector` bildet diese Grenze:

```text
ExactParsedTerm der vollständigen Wurzel
  + stabiler Kindindexpfad
  + formatierter Snapshot nur als Stalenessschutz
  -> Navigation zum konkreten Parserknoten
  -> parserausgestellter SourceRange
  -> identische AST-Knoteninstanzen
  -> lokal verschobene Node-Ranges
  -> identische exakte Literal-Evidence mit lokalen Positionen
  -> fail-closed revalidiertes ExactParsedTerm des Teilvorkommens
  -> inhaltsadressierte Projektions-Evidence
```

Der Projektor faktorisiert noch nicht und ersetzt noch keinen Teilbaum. Er
liefert den exakten, occurrence-gebundenen Eingang für die folgende
Faktorisierungs- und Local-Rewrite-Integration.

## Pfad- und Vorkommensvertrag

Ein Pfad ist eine unveränderliche Liste nichtnegativer Kindindizes:

- bei `BinaryExpr` bezeichnet `0` den linken und `1` den rechten Operanden;
- bei `FunctionExpr` bezeichnet der Index das Argument;
- der leere Pfad bezeichnet die Wurzel;
- jeder andere Index oder ein Abstieg durch ein Blatt ergibt
  `POSITION_NOT_PRESENT`.

Die Navigation verwendet den bereits parsererzeugten AST. Ein strukturell
wertgleicher Knoten an einem anderen Pfad ist ein anderes Vorkommen. Deshalb
bindet das Projektionszertifikat sowohl den Pfad als auch den absoluten
`SourceRange` des ausgewählten Knotens.

Ein source-freier synthetischer Nullknoten aus der Unary-Minus-Darstellung kann
nicht als selbständiges Vorkommen projiziert werden. Er besitzt absichtlich
keinen `SourceRange`; das Ergebnis lautet `UNSUPPORTED`.

## Stalenessschutz und mathematische Autorität

Der Aufrufer liefert zusätzlich den formatierten Teilbaumtext, der bei der
Positionsauswahl sichtbar war. Nach der Pfadnavigation formatiert der Projektor
den aktuell gebundenen Knoten und vergleicht beide Texte.

```text
expected formatter snapshot == current formatter snapshot
```

Eine Abweichung ergibt `POSITION_STALE`. Der Formattertext bleibt jedoch reine
Anzeige- und Staleness-Evidence. Er autorisiert weder Koeffizienten noch
Exponenten und wird niemals erneut geparst, um exakte Werte zu erzeugen.

Mathematische und occurrence-bezogene Autorität stammt ausschließlich aus:

- dem konkreten parsererzeugten AST-Knoten;
- seinem parserausgestellten `SourceRange`;
- den identitätsgebundenen `LiteralOccurrence`-Objekten;
- den darin enthaltenen verifizierten
  `ExactRationalParseEvidence`-Zertifikaten.

## Lokale Koordinaten ohne Reparse

Das ausgewählte Quellfragment stammt direkt aus

```java
selectedRange.textFrom(root.source())
```

Alle source-gestützten Knoten des ausgewählten Teilbaums behalten ihre
Objektidentität. Ihre absoluten Bereiche werden nur um den Start des
Vorkommens verschoben:

```text
localStart = absoluteStart - selectedStart
localEnd   = absoluteEnd   - selectedStart
```

Dasselbe gilt für Literalpositionen. Lexem, exakter Wert, Value-ID und
Parserzertifikat bleiben unverändert. Beispielsweise wird aus

```text
q + ((01 / 004))
```

am rechten Kind:

```text
projected source: ((01 / 004))
literal 01:        2:4
literal 004:       7:10
```

Das neu zusammengesetzte `ExactParsedTerm` führt anschließend nochmals den
vollständigen Fail-Closed-Vertrag aus #782 aus. Eine verschobene Range oder ein
Literal, das nicht mehr zum übernommenen AST passt, wird nicht als Projektion
ausgegeben.

## Getrennte Ergebnisse

Der Projektor unterscheidet:

```text
PROJECTED
POSITION_NOT_PRESENT
POSITION_STALE
UNSUPPORTED
BUDGET_INCONCLUSIVE
TECHNICAL_FAILURE
```

- `PROJECTED` enthält das revalidierte lokale `ExactParsedTerm`;
- `POSITION_NOT_PRESENT` bedeutet, dass der Pfad in der aktuellen Wurzel nicht
  existiert;
- `POSITION_STALE` bewahrt erwarteten und aktuellen Anzeige-Snapshot, gibt aber
  keine Projektion aus;
- `UNSUPPORTED` bezeichnet ein vorhandenes, aber nicht source-gestütztes
  Vorkommen, insbesondere die synthetische Unary-Minus-Null;
- `BUDGET_INCONCLUSIVE` bewahrt eine Ressourcen- oder Darstellungsgrenze;
- `TECHNICAL_FAILURE` bezeichnet einen internen Widerspruch zwischen bereits
  validierter Wurzelevidence und dem ausgewählten Teilbaum.

Keiner dieser negativen Zustände wird zu einem mathematischen Miss oder einer
Irreduzibilitätsaussage umgedeutet.

## Ressourcenvertrag

Die Policy begrenzt explizit:

- Pfadtiefe;
- Codeeinheiten des vollständigen Quelltexts;
- Zahl der besuchten Teilbaumknoten;
- Codeeinheiten des projizierten Quellfragments;
- erwarteten und aktuellen Formattertext;
- gesamte kanonische Projektionsarbeit.

Das Work-Ledger trennt unter anderem:

```text
projection.root-source-hash-code-units
projection.path-navigation-steps
projection.subtree-node-visits
projection.shifted-range-bindings
projection.shifted-literal-bindings
projection.staleness-format-node-visits
projection.staleness-format-code-units
projection.staleness-text-comparison
projection.selected-source-code-units
projection.revalidation-node-visits
projection.revalidation-range-bindings
projection.revalidation-literal-bindings
```

Der Slice besitzt noch eine eigene begrenzte Projektionsautorität. Die
anschließende Produktintegration muss diese bereits verbrauchte Arbeit in die
ursprüngliche Gesamtgrenze für Faktorisierung, Rendering, Reparse, Ersetzung und
Replay übernehmen. Ein Budgetreset zwischen den Stufen wäre nicht zulässig.

## Inhaltadressierte Evidence

Ein erfolgreiches Ergebnis bindet mindestens:

- Projektor-ID und Policy;
- Ergebnisstatus und Detailcode;
- Kindindexpfad;
- erwarteten und aktuellen Staleness-Snapshot;
- SHA-256 des vollständigen ursprünglichen Quelltexts;
- absoluten `SourceRange` des ausgewählten Vorkommens;
- eine deterministische Verpflichtung auf Knotenarten, Operatoren,
  Funktionsnamen, lokale Ranges und Literalzertifikate;
- projizierten Quelltext und lokal verschobene Literalpositionen;
- vollständiges Projektions-Work-Ledger;
- eigenes SHA-256-Zertifikat.

Zwei wertgleiche Teilbäume an verschiedenen Pfaden dürfen denselben lokalen
Quelltext ergeben, besitzen aber wegen Pfad und absolutem Vorkommensbereich
unterschiedliche Projektionszertifikate.

## Qualifikation

Die fokussierten Tests charakterisieren:

- Projektion eines verschachtelten exakten rationalen Polynoms;
- anschließende Verarbeitung durch
  `ExactParsedUnivariatePolynomialView` ohne Reparse;
- Erhalt derselben AST-Knotenidentität;
- gruppierten lokalen Quelltext;
- korrekt verschobene Literalpositionen;
- zwei wertgleiche Teilbäume an unterschiedlichen Pfaden;
- stale Formatter-Snapshots;
- fehlende Pfade und source-freie synthetische Knoten;
- Wurzelprojektion ohne äußeren Leerraum;
- Pfad- und Gesamtarbeitsgrenzen;
- deterministische Zertifikate über wiederholte Läufe.

Fokussierter Maven-Aufruf:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-core -am \
  -Dtest=ExpressionParserNodeRangeTest,ExactParsedSubtermProjectorTest \
  test
```

Vor einem Merge bleiben zusätzlich der checkout-eigene Gradle-Lifecycle und der
vollständige Maven-/Produkt-/Docker-Vertrag verbindlich.

## Nächste Integration

Nach diesem Slice kann die verschachtelte Faktorisierung geschlossen werden:

```text
projected ExactParsedTerm
  -> ExactParsedFactorizationPipeline
  -> FactorizationVerifier candidate
  -> #780 exact rendering/reparse/reconstruction
  -> Ersatz genau am gebundenen Pfad
  -> erneutes Wurzel-Replay mit Kontext- und Stalenessprüfung
```

Dabei müssen Projektions-, Faktorisierungs-, Rendering-, Reparse-,
Ersetzungs- und Replayarbeit in einer nicht zurücksetzbaren Gesamtbilanz
zusammengeführt werden. Die vorhandene `TreePosition`- und Local-Rewrite-
Infrastruktur bleibt das Pfad- und Ersetzungsmodell.

## Claim-Grenze

Der Projektor belegt, dass ein parsergebundenes, source-gestütztes
Teilvorkommen ohne numerischen Reparse in ein lokales exaktes Parserobjekt
überführt werden kann. Er belegt noch keine erfolgreiche Faktorisierung,
Teilbaumersetzung, Suchintegration, mathematische Neuheit oder einen
Suchvorteil.
