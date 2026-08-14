# Representation Discovery

Representation Discovery behandelt eine äquivalente Darstellung als eigenes
Discovery-Ergebnis. Ein Kandidat kann einen Ausdruck komprimieren oder bekannte
mathematische Struktur mit konkreten Folgefähigkeiten sichtbar machen – auch
wenn die Zwischenform länger ist. Die vollständige Zielsetzung steht in
[Issue #663](https://github.com/carstenartur/Regelsuche/issues/663).

## Implementierter erster Slice

Das Paket `de.regelsuche.discovery.representation` liefert:

- Kandidaten für ganze Ausdrücke und exakt adressierte Teilausdrücke;
- occurrence-lokale Kontextprüfung;
- rohe mehrdimensionale Kompressionsmaße;
- einen content-addressed Katalog bekannter Strukturen;
- Mustererkennung im ganzen Ausdruck und in Teilausdrücken;
- positionsgebundene Konsequenz- beziehungsweise Capability-Unlocks;
- Schutz gegen Kompression durch erfundene Variablen- oder Funktionsnamen;
- einen von der strukturellen Bewertung getrennten Validierungsstatus.

## Kompressionsvektor

`SemanticDescriptionMetrics` hält Token, AST-Knoten, Operatoren, numerische
Bitkosten, semantische Wertvorkommen, verschiedene semantische Werte und daraus
entstehende Sharing-Ersparnis getrennt fest. Keine Dimension ist ein
universeller Einfachheitsscore.

Die anfängliche konservative Policy verlangt Verbesserungen in mindestens zwei
Dimensionen. Neu eingeführte Symbole erhalten keinen Kompressionskredit.
Regressionen bei AST-Knoten, Operatoren oder verschiedenen semantischen Werten
blockieren den Kompressionsstatus. Die heutige Zahlenmessung folgt noch dem
bestehenden Ausdrucksmodell; exakte gemeinsame Integer-/Rational-Semantik ist in
#661 abgegrenzt.

## Bekannte Struktur und Folgefähigkeit

Eine `KnownStructure` bindet ID, Domäne, ausführbares `PatternExpr`, erforderliche
Annahmen, konkrete Konsequenz-IDs und Provenienz. Der Kataloghash ist unabhängig
von der Eingabereihenfolge.

Ein bekannter Name allein ist nur ein Signal. Ein
`DOWNSTREAM_CAPABILITY_BRIDGE` entsteht erst, wenn eine neu exponierte Struktur
unter erfüllten Annahmen eine vorher nicht verfügbare Konsequenz freischaltet.
Der Unlock bindet Konsequenz, Struktur, AST-Pfad und Match-Identität. So zählt
dieselbe Regelfamilie an einer neu erschlossenen zweiten Position als neue
mathematische Möglichkeit.

## Claim-Grenze

Strukturelle Evidenz ist kein Beweis. Kandidaten dürfen bereits als `OBSERVED`
retained werden; claim-fähig sind materielle Ergebnisse erst ab
`SYMBOLICALLY_VERIFIED`. AC-Umsortierung, Formatänderung, Alias-Tricks und
unerfüllte Strukturannahmen bleiben sichtbare Negativkontrollen.

Dieser Slice erzeugt noch keine zielblinde Kandidatenmenge, beweist selbst keine
Äquivalenz, lernt den Katalog nicht und generalisiert keine konkrete Brücke.
Diese Folgearbeiten bleiben #663-Tranchen unter den Informationsgrenzen aus
#620, #235 und #383.

## Fokussierte Verifikation

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-discovery -am \
  -Dtest='SemanticDescriptionMeasurerTest,KnownStructureMatcherTest,RepresentationCandidateAssessorTest' \
  test
```
