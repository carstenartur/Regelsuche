# Representation Discovery

Representation Discovery behandelt eine äquivalente mathematische Darstellung als
eigenständiges Discovery-Ergebnis. Ein Kandidat kann wertvoll sein, weil er einen
Ausdruck komprimiert, eine bekannte mathematische Struktur sichtbar macht oder
eine konkrete nachgelagerte Fähigkeit freischaltet – auch wenn der
Zwischenausdruck länger ist.

Die vollständige Zielsetzung wird in [Issue #663](https://github.com/carstenartur/Regelsuche/issues/663)
verfolgt.

## Implementierte Grundlage

Der erste produktive Slice liegt unter
`de.regelsuche.discovery.representation` und liefert:

- Vorschläge für ganze Ausdrücke und exakt adressierte Teilausdrücke;
- deterministische rohe Beschreibungsmaße statt eines universellen
  Einfachheitsscores;
- Vorkommenspfade mit Prüfung, dass der umgebende Ausdruck unverändert bleibt;
- einen content-addressed Katalog bekannter Strukturen;
- Mustererkennung im ganzen Ausdruck und in Teilausdrücken;
- occurrence-spezifische Konsequenz- beziehungsweise Capability-Unlocks;
- getrennte Kandidatenklassen für Kompression, bekannte Formen und
  nachgelagerte Fähigkeiten;
- konservative Schutzregeln gegen Kompression durch neu erfundene Variablen-
  oder Funktionsnamen;
- einen unabhängigen mathematischen Validierungsstatus;
- deterministische strikte JSON-Kodierung des Assessments.

## Beschreibungsmaße

`SemanticDescriptionMetrics` hält folgende Rohdimensionen fest:

| Dimension | Bedeutung |
| --- | --- |
| `tokenCount` | Token im deterministisch formatierten Ausdruck |
| `astNodeCount` | Knoten im Syntaxbaum |
| `operatorCount` | Binäre Operatoren und Funktionsanwendungen |
| `numericBitLength` | approximierte Bitkosten der derzeitigen Zahlenliterale |
| `semanticValueOccurrences` | Syntaxvorkommen mit semantischer Wertprojektion |
| `distinctSemanticValues` | verschiedene owner-scoped mathematische Werte |
| `repeatedSemanticValueSavings` | Vorkommen minus verschiedene Werte |

Keine einzelne Dimension ist autoritativ. Die anfängliche konservative Policy
verlangt eine Verbesserung in mindestens zwei Dimensionen. Neu eingeführte
Symbole erhalten keinen Kompressionskredit; ebenso blockieren Regressionen bei
AST-Knoten, Operatoren oder verschiedenen semantischen Werten den
Kompressionsstatus. Der vollständige Vektor bleibt für spätere Profile und
Sensitivitätsanalysen erhalten.

Das aktuelle Zahlenmodell verwendet noch die bestehende Darstellung der exakten
Ausdruckssprache. Eine gemeinsame exakte Integer-/Rational-Semantik ist getrennt
in #661 beschrieben.

## Bekannte mathematische Strukturen

Eine `KnownStructure` bindet:

- stabile ID und mathematische Domäne;
- ein ausführbares `PatternExpr`;
- erforderliche Annahmen;
- konkrete Konsequenz- oder Capability-IDs;
- Provenienz.

Der Kataloghash ist unabhängig von der Eingabereihenfolge. Ein Match hält den
exakten Vorkommenspfad und die gerenderten Platzhalterbindungen fest. Ein
bekannter Name allein ist nur ein Signal. `DOWNSTREAM_CAPABILITY_BRIDGE` wird
erst vergeben, wenn eine neu exponierte Struktur eine konkrete Konsequenz an
einem zuvor nicht verfügbaren Strukturmatch freischaltet und alle deklarierten
Annahmen vorhanden sind.

Ein Capability-Unlock bindet deshalb nicht nur eine globale Regel-ID, sondern
zusätzlich Struktur-ID, AST-Vorkommenspfad und Match-Identität. Wird dieselbe
Regelfamilie an einer zweiten Stelle des Ausdrucks neu anwendbar, bleibt dies als
eigenständige neue Möglichkeit sichtbar.

## Claim-Grenze

Das Assessment trennt strukturelle Evidenz von mathematischer Wahrheit:

- `OBSERVED`- oder nur durch Beispiele validierte Kandidaten dürfen erhalten und
  untersucht werden;
- ein materielles Signal ist erst ab `SYMBOLICALLY_VERIFIED` claim-fähig;
- neu eingeführte Symbole dürfen keinen Kompressionsgewinn vortäuschen;
- eine nicht erfüllte Strukturannahme darf keine Konsequenz freischalten;
- reine AC-Umsortierung oder Formatänderung ohne materiellen Gewinn bleibt als
  Negativkontrolle sichtbar.

Dieser Slice erzeugt noch nicht selbst zielblind die Kandidatenmenge, beweist
keine Äquivalenz, lernt den Strukturkatalog nicht automatisch und generalisiert
keine konkrete Brücke. Diese Schritte bleiben weitere #663-Tranchen und müssen
die Informations- und Benchmarkgrenzen aus #620, #235 und #383 einhalten.

## Fokussierte Verifikation

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-discovery -am \
  -Dtest='SemanticDescriptionMeasurerTest,KnownStructureMatcherTest,RepresentationCandidateAssessorTest' \
  test
```
