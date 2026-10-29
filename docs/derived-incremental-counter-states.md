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
Zustände ausreichend. Diese Übergänge sind keine auswählbaren Vorlagen: Eine
Partitionierung beginnt mit gleichen Beobachtungswerten und trennt Zustände,
deren erlaubte Nachfolger in verschiedenen Klassen liegen.

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
keine Laufzeitvorhersagen. Die konservative Verfeinerung verspricht keine global
minimale Zustandszahl und kann quadratische Arbeit benötigen.

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
```

Die gewöhnlichen JUnit-Tests prüfen unter anderem Kollisionen, Löschungen,
Parität, quadratische Kategorienstatistiken, gefälschte Tabellen und Budgets.
Die Primachsenraum-Messung vergleicht vollständige Anwendungen einschließlich
Ableitung, nativer Prüfung und exakter Referenzwiederholung. Ein automatischer
Zustandsentwurf allein belegt weder einen Laufzeitgewinn noch eine neue
zahlentheoretische Erkenntnis.
