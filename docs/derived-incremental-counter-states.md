# Abgeleitete inkrementelle Zählerzustände

`CounterStateSynthesis` in `regelsuche-search` leitet endliche Zustände und
Aktualisierungstabellen aus einer gewünschten Zählerbeobachtung und erlaubten
Änderungen ab. Die Eingabe verwendet den bestehenden `Expr`-AST.

```java
var count = new VariableExpr("count");
var source = new CounterStateSynthesis.Specification(
    new FunctionExpr("positive", count), 20, true);
var derived = CounterStateSynthesis.derive(source);
var categories = CounterStateSynthesis.prepareVerified(source, derived.plan())
    .<String>newAccumulator();
categories.insert("red");
categories.insert("red");
categories.delete("red");
// categories.value() == 1: red is still present.
```

Bei reinen Einfügungen genügen für `positive(count)` zwei Zustände. Bei Löschungen
müssen die Zählerstände 0 bis zur gewählten Grenze unterscheidbar bleiben:
Aus einer Belegungsmarkierung allein lässt sich nicht entscheiden, ob nach einer
Löschung noch ein Element vorhanden ist. Für `mod(count, 2)` bleiben dagegen zwei
Zustände ausreichend. Diese Übergänge sind keine auswählbaren Vorlagen: Aus dem
endlichen Beobachtungswort `f(0), ..., f(M)` wird eine Periode abgeleitet.

Bei Einfügungen und Löschungen muss eine Zusammenfassung von `c` und `d` auch
nach gemeinsamen Löschungen und Einfügungen gültig bleiben. Damit ist `|d-c|`
eine Periode des gesamten Beobachtungsworts. Die kürzeste Periode `p` liefert
`count mod p` als Zustand und die minimale Zahl von Tabellenzuständen.

Bei reinen Einfügungen darf vor einem periodischen Rest ein endlicher Präfix
liegen. Gesucht wird das kleinste `Präfixlänge + Periodenlänge`. Eine Präfixfunktion
nach dem KMP-Verfahren auf dem umgekehrten Wort liefert die kürzesten Perioden
aller Suffixe in linearer Arbeit. Die unabhängige Tabellenprüfung bleibt erhalten.
Die Minimalität gilt für die Tabellenzustände unter extern garantierten legalen
Änderungen. Der generische Akkumulator speichert zusätzlich die Multiplizität für
seine Schrankenprüfung; sie ist nicht in dieser Zustandszahl enthalten.

Der getrennte Prüfer enumeriert **alle** Zählerstände und legalen Übergänge
innerhalb der Schranke. Er kontrolliert die Beobachtung und das kommutierende
Übergangsdiagramm, ohne die Partitionierung erneut auszuführen. Induktion über
legale Änderungen begründet die Ausführung. Übergänge außerhalb der Schranke
sind undefiniert; der Akkumulator verwirft sie vor einer Zustandsänderung.
Tabellen werden defensiv kopiert. Sitzungen teilen keine veränderlichen Daten.
Der gruppierte Akkumulator verlangt `f(0)=0`; allgemeine endliche Beobachter sind
auch mit anderem Nullwert als Tabellen darstellbar.

Unterstützt werden ganzzahlige Konstanten, `count`, Addition, Subtraktion,
Multiplikation, `positive` und Modulo mit positivem Modul. AST-Größe,
Wertebitlänge, Zählergrenze und Ableitungs-/Prüfarbeit sind begrenzt. Ein erschöpftes
Budget liefert eine explizite Ausnahme, keinen teilweise geprüften Plan.
`Derivation` trennt Ableitungs- und Prüfarbeit; dies sind diskrete Arbeitseinheiten,
keine Laufzeitvorhersagen. Das bestehende Feld `refinementWork` zählt jetzt die
Arbeit der Periodenableitung. Bei fester AST-/Wertebitgrenze benötigen Ableitung
und Prüfung lineare Arbeit und linearen Speicher in der Zahl der Zählerstände.
Der vorherige quadratische Verfeinerungsweg wurde ersetzt. Ein JUnit-Test leitet
die reversible Belegung bis zur maximalen Schranke 8192 einschließlich Prüfung
innerhalb von `20 * (M+1)` Arbeitseinheiten ab. Die alte Fassung scheitert daran.

Ein zweiter Test vergleicht die Zahl der Tabellenzustände für jedes binäre
Beobachtungswort der Längen 2 bis 7 und beide Änderungspolitiken mit erschöpfender
Präfix-/Periodenprüfung. Dabei wird auch `0,1,0,0,0` erfasst: drei statt der zuvor
erzeugten vier Zustände bei reinen Einfügungen.

Die Modellierung der Schlüsselprojektion und der additiven Gewichte bleibt beim
Aufrufer. Dieses Modul ist begrenzte Zustandssynthese für Zählerbeobachter, keine
Synthese beliebiger Java-Programme oder allgemeiner Sammlungsabfragen. Es enthält
keine neue Suchwarteschlange und keine Primzahlannahmen. Der Primachsenraum-Adapter
verwendet die Tabellen in seinem tatsächlichen Konstellationsoptimierer und
prüft die benötigte Belegungs-/Mitgliedschaftssemantik nochmals unabhängig ohne
Regelsuche-Abhängigkeit im Hauptprojekt.

Ausführen mit Java 25:

```sh
./gradlew :regelsuche-search:test --no-configuration-cache
# Mit dem installierten, gepinnten AI Knowledge Extractor zusätzlich:
./gradlew -PenableAiKnowledgeExtractor=true :ai-knowledge-verification:verifyComplexityHotspots --no-configuration-cache
```

Die gewöhnlichen JUnit-Tests prüfen unter anderem Kollisionen, Löschungen,
Parität, quadratische Kategorienstatistiken, gefälschte Tabellen und Budgets.
Die Primachsenraum-Messung vergleicht vollständige Anwendungen einschließlich
Ableitung, nativer Prüfung und exakter Referenzwiederholung. Ein automatischer
Zustandsentwurf allein belegt weder einen Laufzeitgewinn noch eine neue
zahlentheoretische Erkenntnis.
