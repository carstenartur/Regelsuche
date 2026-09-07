# Exakte Zahlen im AST

`NumberExpr.value()` ist ein `ExactRational`. Der vorhandene gemeinsame Werttyp
normalisiert Zähler und Nenner; Dezimalzeichen werden ohne Umweg über `double`
gelesen. Das gilt für den normalen Parser und für `parseExactTerm`. Letzterer
behält zusätzlich Quellbereiche und unabhängige Literalzertifikate. Der normale
Parser nutzt dieselbe Grammatik und dieselben Grenzen, erzeugt jedoch keine
anschließend ungenutzten Zertifikate.

```java
var parser = new ExpressionParser();
var large = parser.parseTerm("9007199254740993");
var third = NumberExpr.exact("1/3");
var exactSum = third.value().add(third.value()).add(third.value()); // 1
var literalPattern = PatternExpr.num("1.0000000000000001");
```

## Eingabe und Ausgabe

Ganzzahl- und Dezimaltokens verwenden die vorhandenen Grenzen von
`ExactRationalDomain`: 1.024 Ziffern, 256 Nachkommastellen und 4.096 Zeichen je
Literal. Beide Parsereingänge beachten auch explizit konfigurierte Grenzen.
Wissenschaftliche Notation und nichtendliche Werte sind keine Zahlen der
exakten Ausdruckssprache. Bereichsüberschreitungen werden mit Quellposition
abgewiesen und niemals gerundet.

Eine eingegebene Division bleibt im Syntaxbaum eine Division: `1/3` behält
seine beiden Zahlenvorkommen. Eine numerische Umformung kann daraus ein
rationales Zahlenblatt erzeugen. Endliche Dezimaldarstellungen innerhalb der
Eingabegrenze werden dezimal ausgegeben; andere rationale Blätter als Bruch.
In zusammengesetzten Ausdrücken werden solche Brüche geklammert. Wertprojektion
erkennt konstante Division und Subtraktion, sodass auch rationale und negative
Zahlen nach erneutem Parsen denselben Wertschlüssel erhalten. Für Bruchsyntax
gilt die Zifferngrenze je Ganzzahltoken; der Formatter weist größere Komponenten
explizit ab. Primitive Faltung erzeugt solche nicht wieder einlesbaren Ergebnisse
nicht; die String-Kanonisierung behält dann den ursprünglichen Ausdruck bei.
Syntaxvorkommen bleiben unabhängig adressierbar. `0/0` wird nicht zu einem Zahlenwert reduziert.

JSON serialisiert rationale Werte als kanonischen Text, zum Beispiel
`{"value":"9007199254740993/7"}` für ein `NumberExpr`. Damit brauchen auch
JSON-Verbraucher keine große Zahl durch einen Gleitkommatyp zu transportieren.
`ExactRational.fromCanonicalText` liest dieses normalisierte Wertformat innerhalb
von 4.096 Zeichen. Es verwendet bewusst nicht erneut das Ziffernbudget eines
Quellliterals: Ein zulässiges Dezimalliteral kann einen längeren kanonischen
Bruch erzeugen. Nichtnormalisierte oder ungültige Transportwerte werden abgewiesen.
E-Graph-Zahlensymbole verwenden denselben rationalen Text. Die Solver-IR behält
ihre Dezimalliterale und stellt nichtterminierende Brüche als exakte Division
zweier Ganzzahlliterale dar.

## Geänderte interne APIs

- `NumberExpr`, `PatternExpr.LiteralNumber`, `ExprMatcher.LiteralNumber` und
  `ExprValueFactory.NumberValue` tragen `ExactRational`.
- Für Ganzzahlkonstanten bestehen `long`-Einstiege; exakte Textwerte verwenden
  `NumberExpr.exact`, `PatternExpr.num` oder `ExactRational.parse`.
- Ein impliziter `double`-Konstruktor besteht nicht. Numerische Diagnostik
  projiziert Werte ausdrücklich mit `toBigDecimal(MathContext)` und darf diese
  Projektion nicht als exakte Gleichheit oder neu berechnetes Zahlenblatt verwenden.
- Zahlenfaltung, Koeffizientenrechnung, Ableitungen, quadratische Ergänzungen
  und die linearen Gleichungs- und Ungleichungslöser rechnen rational. Die
  primitive Zahlenfaltung ist auf 4.096 Bit je rationaler Komponente begrenzt.
- Begrenzte Integeralgorithmen prüfen Ganzzahligkeit und Bereich vor einer
  Verengung. Der ältere Integer-Regelminer akzeptiert weiterhin nur 32-Bit-Werte;
  die endliche quadratische Faktorsuche begrenzt ihre Koeffizienten auf 10.000.

## Identität und historische Evidenz

`ExprValueFactory.ValueKey` verwendet `regelsuche.expr-value/v2` mit kanonischen
rationalen Zahlen statt Double-Bitmustern. Numerische Beschreibungslängen zählen
die Bits des normalisierten Zählers und bei Brüchen zusätzlich die des Nenners.
Geänderte Ausdrucksbytes, Wertschlüssel und Regelbeschreibungen können andere
Hashes und Suchverläufe ergeben.

Historische Ergebnisdateien, versiegelte Qualifikationen und Schwellen bleiben
an ihre damalige Revision gebunden. Sie werden durch diese Migration nicht neu
erzeugt oder auf neue Hashes umgeschrieben. Neue Studien müssen den neuen Stand
vor ihrer Auswertung ausdrücklich binden.

Dieser Meilenstein bearbeitet die Zahlensemantik aus [#661](https://github.com/carstenartur/Regelsuche/issues/661).
Kompakte Arena, Suchzustandsquotient und deren Nutzenvergleich bleiben eigene
offene Teile desselben Issues. Die Migration behauptet keine Suchüberlegenheit.
