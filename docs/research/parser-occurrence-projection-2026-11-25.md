# Parsergebundene Teilbaumprojektion am 25. November 2026

`ExactParsedSubtermProjector.withParserOccurrenceReuse` übernimmt AST-Identität,
Quellbereiche und geprüfte Literalbelege aus einem Parsergebnis oder einer
Projektion desselben Verfahrens. Selektionspfad, vollständige Eingabe,
absoluter Bereich und aktueller Formattertext gehen in den Beleg ein. Die
Quellbereiche im projizierten Term sind relativ zum ausgewählten Ausschnitt.
Die Literale behalten ihre Schreibweise und ihren ursprünglichen Exaktbeleg.
Der neue Beleg verwendet `regelsuche.exact-parsed-subterm-projector/parser-occurrence-reuse/v1`.

Unter Java 25 wurden je Eingabe dieselbe Parserinstanz, dieselbe Auswahl,
derselbe erwartete Formattertext und dieselben Projektionsgrenzen verwendet.
Die Tabelle zeigt `work().totalWorkUnits()` bei `Policy.boundedDefaults()`.
Die Eingaben wurden vor der Projektion geparst.

| Eingabe | Pfad | Standard | Wurzelwiederverwendung | Vorkommenswiederverwendung |
| --- | --- | ---: | ---: | ---: |
| `f(g(((01 / 004.00))), g(((01 / 004.00))))` | `[0, 0]` | 1.967 | 1.967 | 1.031 |
| `\t  x^2 - 01.250 \n` mit Tab und Zeilenumbruch | `[]` | 1.989 | 1.989 | 998 |
| `x^2-1/9` | `[]` | 2.815 | 74 | 139 |
| `f(x, y)` | `[0]` | 86 | 86 | 137 |

Bei den beiden ersten Eingaben genügt ein Projektionsbudget von 1.500
Einheiten für die Vorkommenswiederverwendung. Der Standard stoppt bei der
erneuten Belegprüfung. Der neue Pfad berechnet die relative Bindung einmal
und verbucht die Prüfung der Auswahl, die eingeplanten Kindknoten, die
erzeugten Indizes und die Aufnahme der Auswahl in den Beleg. Bei der kleinen
Variablenprojektion erhöhen die Auswahlprüfung und die zusätzliche
Auswahlbindung die verbuchte Arbeit. Die Messung betrifft allein die
Projektionsabrechnung. Parsezeit, CPU-Zeit, Allokationen und die Kosten
nachgelagerter mathematischer Prüfungen erfordern eigene Messungen.

Zur Reproduktion in `jshell --class-path regelsuche-core/target/classes`
nach einem Maven-Build des Kernmoduls lässt sich folgender Aufruf auf die
Eingaben anwenden.

```java
import de.regelsuche.parse.*;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import java.util.List;

var policy = ExactParsedSubtermProjector.Policy.boundedDefaults();
var work = PolynomialWorkAuthority.unbounded();
var projectors = List.of(new ExactParsedSubtermProjector(policy),
    ExactParsedSubtermProjector.withParserRootReuse(policy, work),
    ExactParsedSubtermProjector.withParserOccurrenceReuse(policy, work));
var parsed = new ExpressionParser().parseExactTerm(
    "f(g(((01 / 004.00))), g(((01 / 004.00))))");
for (var projector : projectors) {
    var result = projector.project(parsed, List.of(0, 0), "1 / 4");
    System.out.println(result.status() + " " + result.work().totalWorkUnits());
}
```

Die Tests unterscheiden syntaktisch gleiche Vorkommen anhand ihrer Position,
prüfen die relativen Bereiche erneut projizierter Literale und weisen
manuell zusammengestellte Belege sowie veralteten Formattertext zurück.
Der Standardpfad und die bisherige Wurzelwiederverwendung bleiben über ihre
vorhandenen Einstiege verfügbar.
