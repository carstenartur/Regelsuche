# Representation Discovery

Representation Discovery behandelt eine äquivalente Darstellung als eigenes
Discovery-Ergebnis. Ein Kandidat kann einen Ausdruck komprimieren oder bekannte
mathematische Struktur mit konkreten Folgefähigkeiten sichtbar machen – auch
wenn die Zwischenform länger ist. Die vollständige Zielsetzung steht in
[Issue #663](https://github.com/carstenartur/Regelsuche/issues/663).

## Implementierter Slice

Das Paket `de.regelsuche.discovery.representation` liefert:

- Kandidaten für ganze Ausdrücke und exakt adressierte Teilausdrücke;
- occurrence-lokale Kontextprüfung;
- rohe mehrdimensionale Kompressionsmaße;
- einen content-addressed Katalog bekannter Strukturen;
- verschachtelbare exakte, AC-/algebraisch äquivalenzbewusste und begrenzt
  theoriegestützte Erkennung im ganzen Ausdruck und in Teilausdrücken;
- positionsgebundene Konsequenz- beziehungsweise Capability-Unlocks;
- Schutz gegen Kompression durch erfundene Variablen- oder Funktionsnamen;
- einen von der strukturellen Bewertung getrennten Validierungsstatus.

## Kompressionsvektor

`SemanticDescriptionMetrics` hält lexikalische Token, AST-Knoten, Operatoren,
numerische Bitkosten, semantische Wertvorkommen, verschiedene semantische Werte
und daraus entstehende Sharing-Ersparnis getrennt fest. Keine Dimension ist ein
universeller Einfachheitsscore.

Die anfängliche konservative Policy verlangt Verbesserungen in mindestens zwei
Dimensionen. Neu eingeführte Symbole erhalten keinen Kompressionskredit.
Regressionen bei AST-Knoten, Operatoren oder verschiedenen semantischen Werten
blockieren den Kompressionsstatus. Die heutige Zahlenmessung folgt noch dem
bestehenden Ausdrucksmodell; exakte gemeinsame Integer-/Rational-Semantik ist in
#661 abgegrenzt.

## Matcher und Templates sind getrennte Verträge

`PatternExpr` war historisch zugleich Muster und erzeugbare rechte Regelseite.
Das funktioniert nur für instanziierbare Baumvorlagen. Negation, Alternativen,
Containment oder relationale Constraints beschreiben dagegen keinen eindeutigen
Ausdruck. Deshalb sind die Rollen jetzt getrennt:

```text
ExprMatcher   erkennt null bis viele Belegungen
ExprTemplate  erzeugt aus einer Belegung genau einen Ausdruck
PatternExpr   bleibt als kompatibles ExprTemplate erhalten
```

Vorhandene `PatternExpr`-Regeln bleiben über `ExprMatcher.pattern(...)`
verwendbar. Neue Wissenseinträge speichern jedoch einen deklarativen
`ExprMatcher`, nicht mehr zwingend ein einzelnes `PatternExpr` plus globale
Sonderoptionen.

## Verschachtelbare Matcher-Algebra

Die erste Algebra umfasst:

- `any`, feste Zahlen und feste Variablen;
- Eigenschaften numerischer Literale wie ganzzahlig oder ungleich null;
- `operation` und `function` mit rekursiven Kind-Matchern;
- `bind(name, matcher)` für die Bindung eines ganzen Teilbaums nach erfolgreicher
  Prüfung des inneren Matchers;
- `allOf`, `anyOf` und fehlersichere `not`-Komposition;
- `contains`, das denselben Matcher an der Wurzel und allen Nachkommen prüft;
- lokal eingebettete `PatternExpr`-Matcher mit eigenem `RecognitionProfile`;
- `equivalent`, das nur ausdrücklich vom
  `EquivalentExpressionProvider` gelieferte, begrenzte Repräsentanten prüft;
- `where` mit Constraints auf bereits gebundene Ausdrücke;
- `bindingMatches` und `sameAs`, auch mit einem eigenen lokalen
  Äquivalenzprofil.

Damit ist eine eingeschränkte Wildcard nur noch ein Spezialfall:

```text
bind("a", allOf(any(), not(contains(literalVariable("x")))))
```

Die Algebra kann zusätzlich innere Bestandteile binden und Beziehungen zwischen
mehreren Bindungen prüfen. Beispielsweise kann ein Bruch als Ganzes sowie
Zähler und Nenner separat gebunden werden, während der Nenner zugleich als
numerisch und ungleich null eingeschränkt wird.

Die Matcherstruktur ist versiegelt, deterministisch beschreibbar und
content-addressed. Knowledge Packs können sie deshalb prüfen und verteilen,
ohne beliebigen Predicate-Code zu laden. Algorithmische Plugin-Prädikate sind
ein getrenntes späteres Erweiterungsthema und dürfen diese deklarative Grenze
nicht umgehen.

## Mehrwertige und fehlersichere Ergebnisse

Ein Matcher liefert nicht nur `boolean`, sondern ein `MatchOutcome` mit:

- null bis vielen deterministisch geordneten Bindungen;
- tatsächlich verwendeter Repräsentation;
- Erkennungsstärke;
- nachvollziehbarer Matcher-Spur;
- ausgewerteten Schritten und AC-Backtracking-Arbeit;
- expliziten Limitdiagnosen.

Die möglichen Gesamtzustände sind:

```text
MATCHED
NOT_MATCHED
INCONCLUSIVE
```

Insbesondere sind zu breite AC-Muster, erschöpfte Backtracking-Budgets,
Repräsentanten- oder Ergebnislimits kein mathematischer Nichttreffer. Negation
ist dreiwertig: `not(INCONCLUSIVE)` bleibt `INCONCLUSIVE` und kann daher keinen
falschen positiven Match erzeugen.

`KnownStructureMatcher.match(...)` ist bewusst streng und schlägt bei einer
solchen unvollständigen Erkennung fehl. `scan(...)` liefert dieselben Treffer
zusammen mit den Diagnoseobjekten, wenn ein aufrufender Prozess die
Unvollständigkeit explizit weiterverarbeiten möchte.

## Bekannte Struktur und Erkennungsstufen

Eine `KnownStructure` bindet ID, Domäne, einen ausführbaren `ExprMatcher`,
erforderliche Annahmen, konkrete Konsequenz-IDs und Provenienz. Der Kataloghash
bindet die vollständige verschachtelte Erkennungssemantik und ist unabhängig
von der Eingabereihenfolge.

Die Erkennung arbeitet an jedem AST-Vorkommen und unterscheidet weiterhin:

1. `EXACT` für exakt strukturelle Teilmatcher;
2. `EQUIVALENCE_AWARE` für lokal ausdrücklich erlaubte AC- oder algebraische
   Erkennung;
3. `BOUNDED_REPRESENTATIVE` für einen tatsächlich verwendeten alternativen
   Repräsentanten aus einer allow-gelisteten Recognition-Theorie.

Diese Stärke wird über den gesamten verschachtelten Matcher propagiert. Ein
äußerer Bruch kann also exakt erkannt sein, während nur sein Zähler modulo AC
passt. Jeder Treffer hält die konkreten Bindungen, den AST-Pfad, die tatsächlich
verwendete Repräsentation, deren Index und die stärkste benötigte Stufe fest.

## Folgefähigkeit

Ein bekannter Name allein ist nur ein Signal. Ein
`DOWNSTREAM_CAPABILITY_BRIDGE` entsteht erst, wenn eine neu exponierte Struktur
unter erfüllten Annahmen eine vorher nicht verfügbare Konsequenz freischaltet.
Der Unlock bindet Konsequenz, Struktur, AST-Pfad und Match-Identität. So zählt
dieselbe Regelfamilie an einer neu erschlossenen zweiten Position als neue
mathematische Möglichkeit.

## Claim-Grenze

Strukturelle Evidenz ist kein Beweis. Kandidaten dürfen bereits als `OBSERVED`
retained werden; claim-fähig sind materielle Ergebnisse erst ab
`SYMBOLICALLY_VERIFIED`. AC-Umsortierung, Formatänderung, Alias-Tricks,
unerfüllte Strukturannahmen und unvollständige Matcherläufe bleiben sichtbare
Negativ- beziehungsweise Inconclusive-Kontrollen.

Dieser Slice erzeugt noch keine zielblinde Kandidatenmenge, beweist selbst keine
Äquivalenz, generalisiert keine konkrete Brücke und liefert noch keinen
umfassenden kuratierten Katalog allgemein bekannter Mathematik. Diese
Folgearbeiten bleiben #663-Tranchen unter den Informationsgrenzen aus #620, #235
und #383.

## Fokussierte Verifikation

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-core -Dtest=ExprMatcherTest test

mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-discovery -am \
  -Dtest=RepresentationCandidateAssessorTest,NestedKnownStructureMatcherTest \
  test
```
