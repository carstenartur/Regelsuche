# Exakte Monomial-Inferenz beim Pattern-Matching

Dieser Schritt setzt die [Konsolidierungsentscheidung](adr/architecture-consolidation-exact-numerics.md)
unter Issue #661 fort. Er ersetzt die bisherige Double-Arithmetik der internen
Monomial-Inferenz durch den vorhandenen `ExactRational`-Vertrag. Er ersetzt
noch nicht den allgemeinen `NumberExpr(double)`-AST.

## Verantwortung und Grenzen

`EquivalenceAwarePatternMatcher` bleibt der gemeinsame Matcher. Der nicht
öffentliche Helfer `BoundedExactMonomial` übernimmt nur Monomialprojektion,
exakte Koeffizientenrechnung und überprüfte Potenzbindungen. Es gibt keinen
zweiten Parser, Suchalgorithmus, öffentlichen Zahlentyp oder Ausführungspfad.

Die Eingangsbrücke interpretiert endliche numerische AST-Blätter unter ihrer
bestehenden kürzesten Dezimalkonvention. Die Rechnung ab dieser Grenze ist
rational und exakt. Bereits vor dem Matcher verlorene Quelltextpräzision wird
nicht wiederhergestellt. Insbesondere darf der Helfer nicht als Ersatz für
parsergebundene Literalprovenienz oder die ausstehende exakte AST-Migration
verwendet werden.

Der unterstützte algebraische Inferenzbereich umfasst Produkte, Division durch
konstante, exakt von null verschiedene Monome und positive ganzzahlige Potenzen.
Ein symbolischer Nenner bleibt außerhalb dieser annahmenfreien Inferenz. Das
verhindert beispielsweise, dass `x/x` ohne Nichtnull-Bedingung zu `1` wird.
Strukturelle Muster bleiben davon getrennt: `A/A` kann weiterhin die Syntax
`x/x` erkennen; eine daraus folgende Kürzungsregel benötigt ihre eigene
Annahmenprüfung.

## Verhalten

| Aufgabe | Ergebnis |
| --- | --- |
| `A^2` gegen `2` | Keine gerundete Wurzel als exakte Bindung. |
| `A^2` gegen `4/9` | Exakte Bindung `A = 2/3`, keine Double-Näherung. |
| `1` gegen `1.0000000001 * 1` | Kein Toleranztreffer. |
| `0.02` gegen `0.1 * 0.2` | Exakte rationale Koeffizientengleichheit. |
| `1` gegen `x/x` | Keine bedingungslose Kürzung. |
| Quadratische Ergänzung mit `4/3` und `4/9` | Rationale Bindung bleibt bei Anwendung und Replay erhalten. |

Wurzeln entstehen nur, wenn Zähler und Nenner perfekte ganzzahlige Potenzen
sind und sämtliche Variablenexponenten teilbar sind. Eine endliche binäre Suche
mit exaktem Potenzvergleich ersetzt die bisherige `Math.pow`-Näherung. Zusätzlich
prüft der Matcher die instanziierte Bindung erneut gegen den Quellmonomialwert.

Die Rückgabe in den bisherigen AST bevorzugt eine endliche Dezimalzahl nur,
wenn exakte Dezimaldivision und Rückvergleich denselben rationalen Wert
bestätigen. So bleibt `3/2` als `1.5` darstellbar, während `2/3` Bruchsyntax
benötigt. Jeder neu erzeugte numerische Blattwert muss ohne Änderung seines
Dezimalwerts zurücklesbar sein. Ein mathematisch exaktes, im alten AST nicht darstellbares
Ergebnis wird nicht gerundet, sondern als `INCONCLUSIVE` mit
`ALGEBRAIC_BINDING_NOT_REPRESENTABLE` gekennzeichnet.

## Begrenzte Ausführung

Alle algebraischen Vorfilter und Inferenzversuche innerhalb eines
`matchDetailed`-Aufrufs teilen denselben Zähler. Der Helfer begrenzt Besuche und
arithmetische Schritte auf 10.000, Projektionstiefe auf 128 und vorab geschätzte
Zähler-/Nennergröße auf jeweils 4.096 Bit. Exponentaddition und -multiplikation
werden vor einer Verengung auf `int` geprüft. Die Größenprüfung erfolgt vor
potenziell großen Potenz- oder Produktallokationen; konservative Ablehnungen
bleiben möglich.

Überschreitungen liefern `INCONCLUSIVE`, keine negative mathematische Aussage.
Die Codes sind `ALGEBRAIC_WORK_LIMIT`, `ALGEBRAIC_COEFFICIENT_LIMIT` und
`ALGEBRAIC_EXPONENT_LIMIT`. Caller-Bindings bleiben unverändert. Verschachtelte
Negation darf einen solchen Zustand nicht in einen positiven Treffer umdeuten.

Dieser interne Ressourcenvertrag ist kein vollständiges CPU-/Sucharbeits-Ledger.
Die bestehenden AC-Verzweigungszähler behalten ihre eigene Bedeutung. Die
Boolesche Komfortschnittstelle liefert wie bisher auch bei `INCONCLUSIVE`
`false`; Anwendungen, die diese Unterscheidung benötigen, lesen das Detailergebnis.

## Verifikation und historische Ergebnisse

`ExactMonomialInferenceTest` enthält Positiv-, Negativ-, Grenz-, Replay- und
Negationstests. Eine separate endliche Referenz bildet Potenzen durch wiederholte
Multiplikation und vergleicht 3.078 ganzzahlige und 1.300 rationale Wurzelfälle.
Diese Charakterisierung ist weder eine allgemeine Beweiszertifizierung noch
Exaktheit des gesamten Suchsystems.

```sh
mvn -pl regelsuche-core -Dtest=NumericBoundaryRegressionTest,ExactMonomialInferenceTest,EquivalenceAwarePatternMatcherTest test
./gradlew :regelsuche-core:test --tests '*NumericBoundaryRegressionTest' --tests '*ExactMonomialInferenceTest' --tests '*EquivalenceAwarePatternMatcherTest'
./gradlew --no-configuration-cache ciCheck
```

Die geänderte Inferenz kann andere Bindungen und damit andere Ausdrucksbytes
erzeugen. Historische Artefakte, versiegelte Aufgaben und Qualitätsgrenzen
werden nicht nachträglich angepasst. Betroffene Experimente behalten ihre
gebundene Implementierung oder benötigen eine ausdrücklich neue Revision.
Die vollständige Qualifikation bezieht sich jeweils auf den konkreten Commit.

## Integration der Dezimaldarstellung

Der erste CI-Lauf dieses Schritts bestand die neuen Einzeltests, zeigte aber
zwei Fehler in den bestehenden Ableitungstests. Die Rechnung war exakt,
die erzeugte Schreibweise `(x + 3 / 2 * a)^2` verfehlte jedoch deren unverändertes
Syntaxziel `(x + 1.5 * a)^2`. Die Produktionsbrücke wählt deshalb eine endliche
Dezimaldarstellung nur nach exaktem Rückvergleich. Die bestehenden
Integrationstests, ihre Zielausdrücke, Suchbudgets und Schwellen bleiben
unverändert. Ein zusätzlicher Test prüft genau diesen Übergang mit der produktiven
`PatternRewriteRule`, Parser und Formatter. Die bestehenden Integrationstests
prüfen zusätzlich den tatsächlichen `AstRewriteTransformationEngine`.
