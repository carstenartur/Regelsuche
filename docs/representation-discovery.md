# Representation Discovery

Representation Discovery bewertet äquivalente Darstellungen als eigenes
Discovery-Ergebnis. Ein Kandidat kann einen Ausdruck komprimieren oder eine
bekannte mathematische Struktur mit konkreten Folgefähigkeiten sichtbar machen,
auch wenn die Zwischenform länger ist. Die vollständige Zielsetzung steht in
[Issue #663](https://github.com/carstenartur/Regelsuche/issues/663).

## Implementierter Slice

`de.regelsuche.discovery.representation` liefert:

- Kandidaten für ganze Ausdrücke und exakt adressierte Teilausdrücke;
- occurrence-lokale Kontextprüfung;
- rohe mehrdimensionale Kompressionsmaße;
- einen content-addressed Katalog bekannter Strukturen;
- verschachtelbare exakte, AC-/algebraisch äquivalenzbewusste und begrenzt
  theoriegestützte Erkennung an jedem AST-Vorkommen;
- positionsgebundene Konsequenz- beziehungsweise Capability-Unlocks;
- Schutz gegen Kompression durch erfundene Symbole;
- einen von der strukturellen Bewertung getrennten Validierungsstatus.

## Kompressionsvektor

`SemanticDescriptionMetrics` hält lexikalische Token, AST-Knoten, Operatoren,
numerische Bitkosten, semantische Wertvorkommen, verschiedene semantische Werte
und Sharing-Ersparnis getrennt fest. Keine Dimension ist ein universeller
Einfachheitsscore. Die konservative Anfangspolicy verlangt Verbesserungen in
mindestens zwei Dimensionen; neu eingeführte Symbole und strukturelle
Regressionen blockieren Kompressionskredit. Exakte gemeinsame
Integer-/Rational-Semantik bleibt in #661 abgegrenzt.

## Matcher und Templates

`PatternExpr` war historisch zugleich Muster und erzeugbare rechte Regelseite.
Nicht instanziierbare Konzepte wie Negation, Alternativen oder Containment
benötigen getrennte Verträge:

```text
ExprMatcher   erkennt null bis viele Belegungen
ExprTemplate  erzeugt aus einer Belegung einen Ausdruck
PatternExpr   bleibt als kompatibles ExprTemplate erhalten
```

Vorhandene Regeln verwenden ihre Muster weiterhin über
`ExprMatcher.pattern(...)`. Neue `KnownStructure`-Einträge speichern einen
`ExprMatcher`.

Die erste versiegelte Matcher-Algebra umfasst:

- `any`, Literale und Eigenschaften numerischer Literale;
- rekursive `operation`- und `function`-Matcher;
- `bind(name, matcher)` für ganze Teilbäume;
- `allOf`, `anyOf`, `not` und `contains`;
- lokal eingebettete `PatternExpr`-Matcher mit eigenem `RecognitionProfile`;
- begrenzte Repräsentantensuche über `equivalent`;
- `where`-Constraints sowie `bindingMatches` und `sameAs` zwischen Bindungen.

Eine eingeschränkte Wildcard ist damit nur ein Spezialfall:

```text
bind("a", allOf(any(), not(contains(literalVariable("x")))))
```

Die allgemeinere Algebra kann zusätzlich innere Bestandteile binden und mehrere
Bindungen relational vergleichen. Ihre kanonische Beschreibung bindet die
vollständige verschachtelte Semantik in die Katalogidentität, ohne beliebigen
Predicate-Code laden zu müssen.

## Fehlersichere Ergebnisse

`MatchOutcome` enthält Bindungen, verwendete Repräsentation,
Erkennungsstärke, Matcher-Spur und Arbeitszähler. Es unterscheidet:

```text
MATCHED
NOT_MATCHED
INCONCLUSIVE
```

Zu breite AC-Muster, erschöpfte Backtracking-, Schritt-, Repräsentanten- oder
Ergebnislimits sind kein mathematischer Nichttreffer. Insbesondere bleibt
`not(INCONCLUSIVE)` ebenfalls `INCONCLUSIVE`.

`KnownStructureMatcher.match(...)` schlägt bei unvollständiger Erkennung
fehlersicher fehl. `scan(...)` liefert Treffer und Diagnoseobjekte, wenn ein
Aufrufer die Unvollständigkeit ausdrücklich weiterverarbeiten will.

## Bekannte Strukturen und Folgefähigkeiten

Eine `KnownStructure` bindet ID, Domäne, `ExprMatcher`, Annahmen, konkrete
Konsequenz-IDs und Provenienz. Treffer bewahren AST-Pfad, Bindungen, tatsächlich
verwendete Repräsentation und eine der Stufen `EXACT`, `EQUIVALENCE_AWARE` oder
`BOUNDED_REPRESENTATIVE`.

Ein bekannter Name allein ist kein materieller Bridge-Erfolg. Ein
`DOWNSTREAM_CAPABILITY_BRIDGE` entsteht erst, wenn eine neu exponierte Struktur
unter erfüllten Annahmen eine vorher nicht verfügbare Konsequenz an genau diesem
AST-Vorkommen freischaltet.

## Claim-Grenze

Strukturelle Evidenz ist kein Beweis. Materielle Kandidaten werden erst ab
`SYMBOLICALLY_VERIFIED` claim-fähig. Dieser Slice erzeugt noch keine zielblinde
Kandidatenmenge, beweist keine Äquivalenz, generalisiert keine konkrete Brücke
und liefert noch keinen umfassenden Mathematikkatalog. Diese Folgearbeiten
bleiben #663-Tranchen unter den Informationsgrenzen aus #620, #235 und #383.

## Fokussierte Verifikation

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-core -Dtest=ExprMatcherTest test

mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-discovery -am \
  -Dtest=RepresentationCandidateAssessorTest,NestedKnownStructureMatcherTest \
  test
```
