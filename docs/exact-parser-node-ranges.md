# Parsergebundene AST-Quellbereiche

**Implementierungsstand: 28. August 2026**

## Zweck

`ExpressionParser.parseExactTerm` bewahrt bereits exakte Evidence für jedes
numerische Quellliteral. Für occurrence-erhaltende Transformationen reicht die
Literalebene allein jedoch nicht: Ein ausgewählter Teilbaum muss auf genau das
konkrete Quellvorkommen zurückgeführt werden können, ohne seine Koeffizienten
über `double`, den allgemeinen Formatter oder einen neu geparsten Ersatztext
zurückzugewinnen.

Der exakte Parser-Companion speichert deshalb zusätzlich für jeden
source-gestützten AST-Knoten einen parserausgestellten, halb offenen
Quellbereich:

```text
Quelltext
  -> ExpressionParser.parseExactTerm
  -> gewöhnlicher Expr-AST
  -> ExactParsedTerm
       -> exakte LiteralOccurrence-Evidence
       -> IdentityHashMap<Expr, SourceRange>
```

Die normale AST-Gleichheit und der allocation-minimale Legacy-Parserpfad
bleiben unverändert.

## Identitätsvertrag

Quellbereiche werden nach Java-Objektidentität aufgelöst. Zwei strukturell oder
wertmäßig gleiche Knoten können daher unterschiedliche Vorkommen besitzen:

```text
1 + 1
^   ^
0:1 4:5
```

Ein später erzeugter `new NumberExpr(1)` erbt keinen dieser Bereiche. Damit
bleibt die bestehende Schichtung erhalten:

- `Expr.equals` beschreibt Syntaxstruktur;
- Java-Identität unterscheidet konkrete Parser-Vorkommen;
- `SourceRange` bindet ein Vorkommen an den ursprünglichen Quelltext;
- mathematische Wertidentität und E-Klassen bleiben getrennte Ebenen.

Eine einzelne Knoteninstanz darf im parserausgestellten Baum nur an genau einer
Position vorkommen. Ein künstlich erzeugter DAG, der dieselbe Instanz unter
mehreren Eltern oder Kindindizes wiederverwendet, besitzt keine eindeutige
Occurrence-Identität und wird abgewiesen.

`ExactParsedTerm` stellt dafür bereit:

```java
Optional<SourceRange> sourceRangeFor(Expr node)
Optional<String> sourceTextFor(Expr node)
SourceRange rootSourceRange()
```

## Bereichssemantik

`SourceRange(startInclusive, endExclusive)` verwendet halb offene Indizes im
ursprünglichen Java-String. Führende und nachlaufende Leerzeichen außerhalb des
Ausdrucks gehören nicht zum Wurzelbereich. Der Wurzelbereich muss sämtliche
Nicht-Leerraumzeichen der Eingabe enthalten; außerhalb darf ausschließlich der
vom Parser übersprungene Leerraum liegen.

Klammern gehören dagegen zum Bereich des von ihnen umschlossenen Knotens. Für

```text
 ((01)) 
```

besitzt der `NumberExpr` den Knotenbereich `1:7` mit dem Text `((01))`. Die
unabhängige Literal-Evidence bleibt auf dem echten Zahlentoken `3:5` mit dem
Lexem `01`. Darstellungsvorkommen und numerische Autorität werden dadurch nicht
vermischt.

Binäre Operationen erhalten den Bereich vom Beginn des linken bis zum Ende des
rechten Operanden. Bei gewöhnlichen Binärknoten müssen linker und rechter
Kindbereich disjunkt und in Quellreihenfolge liegen. Funktionsknoten umfassen
Name, öffnende Klammer, Argumente und schließende Klammer; ihre Argumentbereiche
müssen ebenfalls in Quellreihenfolge liegen. Potenz-, Produkt-, Quotienten-,
Summen- und Differenzknoten bewahren ihre vollständige ursprüngliche
Schreibweise.

## Numerische Autorität

Ein `SourceRange` belegt nur, welche Zeichen ein konkretes AST-Vorkommen
erzeugt haben. Er autorisiert keinen numerischen Wert. Jeder source-gestützte
`NumberExpr` muss zusätzlich eine verifizierte `LiteralOccurrence` besitzen,
die genau dieselbe Parser-Knoteninstanz bindet.

Damit werden insbesondere abgewiesen:

- manuell erzeugte Zahlenknoten mit plausibler Range, aber ohne exakte
  Literal-Evidence;
- wertgleiche Ersatzknoten, die die Range eines Parserknotens übernehmen sollen;
- Range-Einträge für Knoten, die vom gebundenen Wurzelbaum aus nicht erreichbar
  sind.

Die Range-Schicht kann die exakte Literal-Schicht daher ergänzen, aber niemals
ersetzen.

## Unary Minus

Der historische Parser stellt

```text
-0.25
```

intern als

```text
0 - 0.25
```

dar. Der linke Nullknoten ist synthetisch und besitzt kein Quelltoken. Er erhält
bewusst weder Literal-Evidence noch `SourceRange`. Der vollständige
Unary-Minus-Knoten besitzt dagegen den Bereich des Minuszeichens und seines
Operanden; der rechte Zahlknoten besitzt den Bereich des echten Tokens.

Diese Ausnahme wird nicht allein aus `value() == 0.0` abgeleitet. Zugelassen ist
sie nur für die rangefreie linke Null eines `SUB`-Knotens. Vor dem rechten
Operanden darf der Bereich ausschließlich ausgeglichene öffnende Gruppierung,
Leerraum und genau ein Minuszeichen enthalten; danach ausschließlich die dazu
gehörige schließende Gruppierung und Leerraum. Dadurch können weder ein
gewöhnliches `0 - x`, ein zu einem einzigen Knoten zusammengezogener Ausdruck
wie `--x`, unausgeglichene Gruppierung noch eine rangebehaftete oder an anderer
Stelle eingesetzte Null als synthetischer Unary-Minus-Knoten ausgegeben werden.

Fehlende Evidence für diesen eng begrenzten synthetischen Knoten ist damit ein
expliziter Parservertrag und kein allgemeiner Weg, provenancefreie Zahlen zu
autorisieren.

## Validierung und Fail-Closed-Grenzen

Beim Aufbau von `ExactParsedTerm` wird geprüft:

- der Wurzelknoten besitzt einen gültigen Bereich ohne äußeren Leerraum;
- außerhalb des Wurzelbereichs stehen keine Syntaxzeichen;
- jeder source-gestützte AST-Knoten besitzt genau einen identitätsgebundenen
  Bereich;
- jede Knoteninstanz tritt im Baum nur einmal auf;
- jeder Kindbereich liegt vollständig im Elternbereich;
- binäre Kindbereiche und Funktionsargumente liegen disjunkt in
  Quellreihenfolge;
- jeder Bereich liegt innerhalb des ursprünglichen Quelltexts;
- die Range-Map enthält keine Identität außerhalb des gebundenen Baums;
- jedes exakte Literal liegt innerhalb des Bereichs seines konkreten
  `NumberExpr`;
- jeder source-gestützte Zahlenknoten besitzt verifizierte exakte
  Literal-Evidence;
- der rangefreie synthetische Unary-Minus-Nullknoten ist die einzige zulässige
  source-freie Zahl im Parserbaum und wird zusätzlich gegen die genaue
  Gruppierungs- und Minusform geprüft.

Ein fremder Knoten, eine aus dem Quelltext herausragende Range, eine geteilte
Knoteninstanz, vertauschte oder überlappende Kindbereiche, eine numerische Range
ohne Literal-Evidence oder eine inkonsistente Literalbindung wird abgewiesen,
statt eine approximative Position zu erzeugen.

## Ressourcen- und Kompatibilitätsgrenze

Die zusätzliche `IdentityHashMap` wird ausschließlich durch
`parseExactTerm` angelegt. `parseTerm` behält seinen bisherigen
allocation-minimalen AST-Pfad ohne Quellbereiche und ohne Exact-Evidence.

Die Range-Erfassung führt keine mathematische Arithmetik aus und verändert
keine `Expr`-Records. Bestehende Matcher, Formatter, Suchidentitäten und
historische Evidence bleiben daher unverändert. Spätere Produktpipelines müssen
Range-Prüfung und Teilbaumprojektion trotzdem in ihre eigene Gesamtarbeit
aufnehmen; die Verfügbarkeit einer Range macht deren Nutzung nicht kostenlos.

## Verwendung für verschachtelte Faktorisierung

Der Vertrag ist eine Voraussetzung für den nächsten #763-Slice:

```text
ExactParsedTerm root
  + TreePosition path/text staleness guard
  -> selected concrete subtree by identity
  -> parser-issued SourceRange and exact node evidence
  -> exact polynomial projection
  -> FactorizationVerifier candidate
  -> #780 rendering/reparse/reconstruction
  -> replacement at exactly the selected path
```

`TreePosition.text` bleibt dabei Anzeige und Stalenessschutz. Numerische Werte
werden weiterhin ausschließlich aus parserausgestellter exakter
Literal-Evidence gelesen. Die Range ersetzt weder `TreePosition` noch die
vorhandene Local-Rewrite-Infrastruktur; sie ergänzt deren Pfadidentität um die
ursprüngliche Quellbindung.

Dieser Slice faktorisiert oder ersetzt noch keinen verschachtelten Teilbaum.
Er schafft nur die kleinste fehlende Parsergrenze, damit die folgende
Integration keinen Formatter-/Reparse- oder `double`-Umweg benötigt.

## Qualifikation

Fokussierte Tests charakterisieren:

- Wurzel-, Summen-, Produkt-, Variablen- und Literalbereiche;
- Klammerbereiche getrennt von Literalbereichen;
- synthetische Unary-Minus-Null ohne Quellbereich, auch unter Gruppierung;
- zwei wertgleiche Knoten mit verschiedenen Occurrence-Ranges;
- fehlende Range für einen fremd erzeugten wertgleichen Knoten;
- Funktionen, Argumente und gruppierte Potenzen;
- Ablehnung fremder Range-Map-Einträge;
- Ablehnung geteilter Knotenidentitäten an mehreren Baumpositionen;
- Ablehnung eines Wurzelbereichs, der Syntax auslässt;
- Ablehnung vertauschter oder überlappender Binärkind- und Argumentbereiche;
- Ablehnung source-gestützter Zahlen ohne exakte Literal-Evidence;
- Ablehnung einer rangefreien Null außerhalb parserbelegten Unary-Minus;
- Ablehnung eines gewöhnlichen `0 - x`, zusammengezogener oder unausgeglichener
  Unary-Minus-Quellformen und einer rangebehafteten synthetischen Null;
- unveränderte AST-Gleichheit zwischen `parseTerm` und `parseExactTerm`.

Fokussierter Maven-Aufruf:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-core -am \
  -Dtest=ExpressionParserExactLiteralTest,ExpressionParserNodeRangeTest \
  test
```

Vor einem Merge bleiben zusätzlich der checkout-eigene Gradle-Lifecycle und der
vollständige Maven-/Produkt-/Docker-Vertrag verbindlich.

## Claim-Grenze

Der Parser belegt nach diesem Slice die Quellposition eines konkreten
source-gestützten AST-Vorkommens und bewahrt dessen exakte Literale. Er belegt
noch keine sichere verschachtelte Faktorisierungsanwendung, keine
Occurrence-Ersetzung, keine mathematische Neuheit und keinen Suchvorteil.
