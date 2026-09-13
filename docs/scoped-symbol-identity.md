# Symbolidentität unabhängig vom Anzeigenamen

Die explizite Java-Eingabe `SymbolicExpression` trennt die mathematische Variable,
ihre einzelnen Syntaxvorkommen und ihre Darstellung. Der Name `y` dient an der
Eingabe zur Auflösung eines Symbols; danach identifiziert `SymbolId` die Variable.
Eine Umbenennung in `vertical` verändert weder den Ausdruck noch seinen Wertschlüssel.

Dies ist ein zusätzlicher, versionierter Eingabe- und Dokumentpfad. Die gewöhnlichen
CLI-/HTTP-Eingaben und historischen Beweis-/Replay-Verträge werden nicht stillschweigend
auf neue Symbolräume umgestellt. Das ist keine Performance- oder Neuheitsbehauptung.

## Ein Ausdruck, drei getrennte Informationen

```java
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import de.regelsuche.symbol.SymbolicExpressionCodec;
import de.regelsuche.mining.RulePatternMatcher; // Modul regelsuche-learning

var scope = new SymbolScope();
var document = SymbolicExpression.parse("(x+y)*(x-y)+y^2", scope);
var y = document.sourceBindings().get("y");
var renamed = document.withDisplayName(y, "vertical");

// Derselbe identity-bound AST; nur die Darstellung ändert sich.
assert document.expression() == renamed.expression();
assert document.identityText().equals(renamed.identityText());
System.out.println(renamed.displayText());

var match = new RulePatternMatcher().matchExpression(
    "(A+B)*(A-B)+B^2", renamed.expression());
assert match.isPresent();

var codec = new SymbolicExpressionCodec();
byte[] saved = codec.encode(renamed);
var restored = codec.decode(saved);
assert restored.expression().equals(renamed.expression());
```

`original()` bleibt der unveränderte `ExactParsedTerm` der ursprünglichen Quelle.
`expression()` ist die davon getrennte, an Symbol-IDs gebundene Projektion. Die
Original-Evidence darf nicht so weitergereicht werden, als habe diese Projektion
bereits in der Originalquelle gestanden. Die tatsächlichen numerischen Blattobjekte
und ihre Literal-Evidence bleiben erhalten. `sourceRangeFor(node)` löst nur ein
konkretes projiziertes Vorkommen auf; ein bloß wertgleicher fremder Knoten bekommt
keine fremde Quellposition. Neu erzeugte oder aus einem E-Graph extrahierte Knoten
erben keine ursprüngliche Vorkommensprovenienz.

Für Berechnungen den gebundenen AST beziehungsweise `identityText()` verwenden,
nicht den nur für Herkunft und Anzeige aufbewahrten Originaltext. Bereits vorhandene
Formatter, Polynomnormalisierung und die primitive AST-Engine können die IDs über
diesen Textpfad erhalten. `display(result)` weist Ergebnisse zurück, die unbekannte
oder nicht mehr gebundene Variablen enthalten. Eine Umstellung weiterer Solver-,
Annahme- und Produktverträge muss ihre jeweiligen Identitätsgrenzen separat prüfen.

## IDs, Gültigkeitsbereiche und Aliasnamen

Eine ID besteht aus einer UUID des Vergaberaums und einer positiven lokalen Nummer.
Die UUID wird einmal pro Scope vergeben, nicht für jedes Vorkommen. Mehrere Ausdrücke
desselben Problems sollten denselben Scope benutzen. `resolve(name)` verwendet eine
vorhandene lokale oder geerbte Bindung; `declare(name)` legt explizit eine neue lokale
Variable an und darf eine geerbte Bindung verdecken. `alias(name, id)` verweist auf
ein bereits in dieser lexikalischen Abstammung bekanntes Symbol.

`child()` legt einen neuen Vergaberaum an. Das ist noch kein neuer Parser für Summen,
Integrale oder Quantoren: Deren Binder müssen die Scope-Grenzen selbst aufbauen.
Funktionsnamen wie `sin` behalten ihre bisherige Operatorbedeutung und werden hier
nicht zu umbenennbaren Variablensymbolen.

Ein explizit übergebener UUID-Wert ist die Identität einer Vergabeinstanz, kein
beliebig wiederverwendbares Testlabel. Zwei unabhängige aktive Vergabeinstanzen
dürfen nicht absichtlich dieselbe UUID verwenden. Der lokale Scope-Baum lehnt
doppelte Namespaces ab; eine globale verteilte Vergabeautorität wird nicht behauptet.

`snapshot()` enthält lokale Bindungen, Elternraum, Grenzen und Vergabestand.
`restore(snapshot)` stellt einen Root wieder her, `parent.restoreChild(snapshot)`
seinen zugehörigen Child-Scope. Eltern und Kinder zuerst vollständig wiederherstellen,
danach die Vergabe fortsetzen. Snapshots dienen dem Neustart eines einzelnen Writers,
nicht dem parallelen Forken mehrerer Writer mit demselben Nummernraum. Inkonsistente
Snapshots und ungültige oder zu große Auflösungsbatches ändern den vorhandenen Scope
nicht. Namen dürfen nach der Vergabe nicht durch Änderung der Bindung umbenannt werden;
für die Anzeige dienen die unveränderlichen Dokumentmethoden.

## Gleichheit und gemeinsame Wertobjekte

`VariableExpr.scoped(id)` erzeugt ein eigenes Vorkommen desselben Symbols.
`symbol()` liefert dessen ID. Gleichheit vergleicht IDs, nicht Anzeigenamen oder
Speicheradressen. `ExprValueFactory.scopedVariable(id)` und `fromExpr(...)` verwenden
die bestehende beschränkte Wert-Internierung: gleiche Werte sind innerhalb derselben
Factory-Lebenszeit dasselbe Wertobjekt. Nach `clear()`, in einer anderen Factory oder
nach dem Laden sind gleiche Werte nicht zwingend Java-referenzgleich.

Unterschiedliche IDs beweisen keine Ungleichheit ihrer mathematischen Werte. Eine
Gleichheit unter Annahmen oder eine explizite E-Graph-Vereinigung ist eine andere
Relation. Ebenso bleibt der Vergleich bis auf konsistente Variablenumbenennung ein
separater Vorgang; seine Zuordnung darf mehrere Variablen nicht versehentlich zu
nur einem Symbol verschmelzen.

Die bisherigen ungebundenen Wertschlüssel behalten Version 2. Gebundene Variablen
und zusammengesetzte Werte, die sie enthalten, verwenden Version 3. Zahlen und die
bisherigen Operatorgesetze bleiben unverändert.

## Identitätstransport ist keine Anzeige

Der vorhandene `VariableExpr(String)`-Einstieg, JSON mit dem Feld `name` und
`name()` bleiben für gewöhnliche Namen wie `x` erhalten. Im gebundenen Pfad liefert
`name()` stattdessen einen reservierten Identitätstransport:

```text
rsym_<32 kleingeschriebene UUID-Hexziffern>_<positive lokale Nummer>
```

Der Parser rekonstruiert daraus genau die gleiche ID. Der Präfix `rsym_` ist
reserviert; fehlerhafte Tokens werden nicht als gewöhnliche Variablen interpretiert.
Diesen internen Namen nicht als Anzeigenamen verwenden. Neue Dokumentnamen und
Labels müssen gültige, nicht reservierte Bezeichner sein. Anzeigenamen sind innerhalb
eines Dokuments eindeutig; verschiedene Dokumente dürfen dieselbe Anzeige für
verschiedene IDs benutzen. Gleichzeitige Labelwechsel sind mit `withDisplayNames(...)`
möglich, ohne eine vorübergehende Mehrdeutigkeit zuzulassen.

## Begrenzte, strikte Speicherung

Der Codec `regelsuche.symbolic-expression/v1` speichert Originalquelle,
`bindings` mit Namen und IDs sowie `displayNames` mit IDs und Labels. Er lehnt
ungültiges UTF-8, doppelte oder unbekannte Felder, nachgestelltes JSON, fehlerhafte
IDs, fehlende oder ungenutzte Bindungen und mehrdeutige Anzeigen ab. IDs werden
verlustfrei übernommen; das Laden vergibt keine neuen IDs.

Die aktuelle Eingabegrenze beträgt 16.384 UTF-16-Code-Units, 128 Syntaxmarker
(Operatoren, Klammern und Kommata), 512 AST-Knoten, Tiefe 128 und 128 verschiedene
Quellbezeichner. Namen und Labels sind auf 128 Zeichen begrenzt. Der Markercheck
läuft vor dem rekursiven Parser; ein erschöpftes Limit ist keine mathematische
Aussage. Der Codec akzeptiert höchstens 1 MiB Eingabebytes und begrenzt zusätzlich
die JSON-Struktur. Die Scope-Limits sind davon getrennt und explizit konfigurierbar.

Ein gültiges Dokument ist Daten, kein Beweis und keine Laufzeitfreigabe. Wer gültige
IDs oder Bindungen ändert, definiert damit einen anderen mathematischen Ausdruck;
der Codec erhebt daraus keinen Identitäts-, Herkunfts- oder Autorisierungsanspruch
gegenüber einem früheren Dokument.

## Tests und verbleibende Integration

Die normalen JUnit-Suiten enthalten ID-/Scope-, Parser-/Provenienz-, Codec-,
Matcher- und E-Graph-Kontrollen. Insbesondere werden `+y^2` und `+z^2`, Aliasnamen,
komponierte Platzhalter, Labelwechsel, Neustart und fehlerhafte Importe geprüft.
Die historische Suchsteuerung bleibt unverändert: Der Dispatcher, der Kontexte auf
Regel-IDs reduziert, muss die schrittübergreifenden Bindungen weiterhin separat
behalten oder prüfen. Symbolidentität allein beseitigt diesen Informationsverlust
nicht.

Der [Umsetzungsplan](superpowers/plans/2026-09-13-symbol-identity.md) und die
[Abnahmespezifikation](superpowers/specs/2026-09-13-scoped-symbol-identity.md)
beschreiben Reihenfolge und Grenzen. Vor einem Merge der Implementierung sind die
unveränderten Java-25-Gradle-/Maven-/Docker-, JMH-, SymPy-, Coverage- und
Code-Scanning-Prüfungen des tatsächlichen PR-Heads erforderlich.
