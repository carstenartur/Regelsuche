# Makroregeln & Emergent Identities

Makroregeln sind wiederkehrende Sequenzen atomarer Regeln. Sie werden aus den
bereits gefundenen `DiscoveredTransformation`s gemined und repräsentieren die
"emergenten Identitäten", die das System aus seiner eigenen Suche destilliert.

## Algorithmus

Implementiert in zwei komplementären Pfaden:

- `de.regelsuche.mining.MacroRuleMiner` für Sliding-Window-Frequenzanalyse über
  wiederholte Regel-Id-Sequenzen.
- `de.regelsuche.mining.RuleCandidateMiner` +
  `PatternGeneralizer` + `CandidateValidator` für validierte Regel-Schemata aus
  erfolgreichen Transformationspfaden.

1. Pro `DiscoveredTransformation` wird die Regel-Id-Sequenz extrahiert.
2. Für jede Fensterlänge `n ∈ [minSequenceLength, maxSequenceLength]` werden alle
   zusammenhängenden Teilsequenzen gezählt.
3. Sequenzen, die ≥ `minOccurrences` mal auftauchen, werden als
   `MacroRuleCandidate` emittiert.
4. `leftPattern`/`rightPattern` sind die Start- bzw. End-Ausdrücke des ersten
   beobachteten Vorkommens. Wiederkehrende Schemata werden im
   `RuleCandidateMiner` über Anti-Unifikation bzw. validierte
   Ein-Beispiel-Schemaextraktion verallgemeinert.
5. `compressionRatio = sequenceLength / 1.0`.

## Hidden-Structure Generalisierung

Hidden-Structure-Funde werden über `DiscoveryResultKind` klassifiziert:

| Level | `DiscoveryResultKind` | Bedeutung |
|-------|------------------------|-----------|
| 0 | `NO_CANDIDATE` | kein begrenzter Hypothesenkandidat |
| 1 | `HYPOTHESIS_ONLY` | Kandidat erzeugt, aber kein Replay-Bridge-State |
| 2 | `BRIDGE_FOUND` | validierter Zwischenzustand wie Quadratdifferenz oder quadratische Ergänzung |
| 3 | `FACTORED` / `SIMPLIFIED` | Replay erreicht faktorisierte oder vereinfachte Form |
| 4 | `MACRO_LEARNED` | ein validiertes Schema wurde aus einem realen Pfad gelernt |
| 5 | `MACRO_REUSED` | das gelernte Schema wurde in einem zweiten strukturell passenden Fall angewandt |

Die aktuelle Infrastruktur unterstützt Sophie-Germain-Bridges und konservative
quadratische Ergänzung (`CompleteSquareHypothesisOperator`). Alle Gallery- und
Report-Einträge werden aus echten Replay-/Suchartefakten erzeugt; statische
Diagramme oder erfundene Pfade sind nicht Teil des Flows.

Hidden-Structure-Funde werden anschließend in drei Stufen behandelt:

1. **Konkretes Replay:** Ein Suchlauf findet und speichert einen realen Pfad, z. B.
   `x^4 + 4 → … → (x^2 - 2*x + 2) * (x^2 + 2*x + 2)`.
2. **Validiertes Ein-Beispiel-Schema:** Wenn dieser konkrete Pfad
   äquivalenzerhaltend ist, kann `PatternGeneralizer` gemeinsame
   Ausdrucks-Teilbäume konsistent durch denselben Platzhalter ersetzen, z. B.
   `A^4 + 4 → (A^2 - 2*A + 2) * (A^2 + 2*A + 2)`. Vor der Promotion validiert
   `CandidateValidator` generierte Instanzen wie `A=x`, `A=y`, `A=x+1`,
   `A=2*x` und `A=x^2` mit dem konfigurierten `EquivalenceService`.
   Dieser Pfad ist explizit opt-in und senkt nicht die normalen Mining-Schwellen.
3. **Zukünftiges Mehrparameter-Schema:** Breitere Schemata wie
   `A^4 + 4*B^4 → …` bleiben Future Work.

Aktuelles Matching ist strukturell mit vorhandener Normalisierung. Formen wie
`(x^2)^2 + 4` können daher nach `ast_power_of_power` als `x^4 + 4` vom Makro
erfasst werden. Algebraisch äquivalente, aber strukturell verdeckte Formen wie
`x^4 + 2*x^2 + 1 + 3 - 2*x^2` werden noch nicht über eine ganze
Äquivalenzklasse gematcht. Hypothesen werden operatorspezifisch und begrenzt
generiert; vor Discovery-, Makro- oder Gallery-Erfolg ist Validierung
verpflichtend.

Defaults (per Konstruktor konfigurierbar):

| Parameter           | Default |
|---------------------|---------|
| `minOccurrences`    | 2       |
| `minSequenceLength` | 2       |
| `maxSequenceLength` | 4       |

## MacroMoves als aktive Suchabkürzungen (Issue #34)

Makroregeln aus dem Inventar werden über `MacroMoveTransformationEngine` aktiv
in den realen `TransformationEngine`/`SearchStrategy`-Pfad eingespeist. Die
Suchstrategien bleiben unverändert: sie rufen weiterhin `engine.transform(...)`
auf, erhalten dort aber zusätzlich zu atomaren Transformationen ausführbare
MacroMove-Kandidaten.

Aktiver Flow:

```text
SearchStrategy
  → MacroMoveTransformationEngine.transform(current)
  → baseEngine.transform(current)                    (atomare Moves)
  → GoalAwareMacroMoveSelector.selectFor(current, optionalGoal)
  → ReusableRule → PatternRewriteRule → Transformation
  → SearchState depth + 1                            (ein Makro-Edge A → B)
```

Ein Makrozug `A → B` fasst mehrere atomare Schritte zusammen und verkürzt die
Suchtiefe messbar. Die Suchkante wird mit `MacroMoveExpansion` annotiert, damit
der Ursprungspfad rekonstruierbar bleibt.

### Replay-Expansion

`MacroMoveExpansion` (neu in `de.regelsuche.mining`, app-Modul) hält sowohl den
kompakten Makrozug als auch den vollständigen atomaren Ursprungspfad:

```java
record MacroMoveExpansion(
    String macroRuleId,
    String fromExpression,
    String toExpression,
    List<TransformationStep> atomicSteps,  // expandierbarer Ursprungspfad
    List<String> supportingPathIds,        // Rekonstruktionsreferenzen
    double compressionRatio,
    boolean expanded                        // UI-Zustand: aufgeklappt/zugeklappt
)
```

Im Replay ist der Makrozug kompakt sichtbar; `atomicSteps` enthält den vollständigen
ursprünglichen Pfad zum Aufklappen.

`MacroMoveExpansion` ist jetzt Bestandteil des Suchgraph-/Replay-Modells:

- `GraphEdge.macroMoveExpansion`
- `SearchGraphEdgeDto.macroMoveExpansion`
- `PathReplayDto.ReplayStep.macroMoveExpansion`
- `SearchGraphRecordCodec` exportiert `macroMoveExpansion.atomicSteps`

Makrozüge tragen außerdem Nutzungsstatistiken:

- `timesConsidered`
- `timesApplied`
- `timesImprovedScore`
- `averageCostReduction`
- `usefulForGoals`

`MacroMoveTransformationEngine` kann mit deaktivierten Makrozügen betrieben
werden; die atomare Suche bleibt dabei unverändert aktiv.

Exportformat (gekürzt):

```json
{
  "ruleId": "macro_binomial_square",
  "macroMoveExpansion": {
    "macroRuleId": "macro_binomial_square",
    "fromExpression": "(x + 3)^2",
    "toExpression": "x^2 + 6*x + 9",
    "supportingPathIds": ["p1", "p2", "p3"],
    "compressionRatio": 3.0,
    "expanded": false,
    "atomicSteps": [
      {"index": 0, "ruleId": "ast_power_two_to_product"},
      {"index": 1, "ruleId": "ast_distribute"},
      {"index": 2, "ruleId": "ast_canonical_normalize"}
    ]
  }
}
```

### Goal-aware MacroMove Selection

`GoalAwareMacroMoveSelector` (neu in `de.regelsuche.mining`, app-Modul) filtert
Inventar-Regeln nach:
- Confidence-Score ≥ Schwellwert (Standard: 0.5)
- Positive durchschnittliche Verbesserung (`averageImprovement > 0`)
- Mindest-Occurrence-Count
- strukturelle Token-Überlappung zwischen Muster und aktuellem Ausdruck
- optionaler Ziel-Ausdruck: benannte Ziel-Token müssen überlappen; reine Operator-
  Überlappung reicht nicht, damit high-confidence aber ziel-irrelevante Makros
  (z. B. Trigonometrie während einer Polynom-Faktorisierung) abgelehnt werden
- ob die rechte Seite neue anwendbare Token/Regeln freilegt

Die Kandidaten werden nach Confidence, durchschnittlicher Verbesserung,
Occurrence-Count, aktuellem Overlap, Ziel-Overlap und Freilegungs-Bonus sortiert.

## Demos ohne DB

Issue #34 speichert keine Makro-/Hypothesen-Datenbank. Die reproduzierbaren Demos
nutzen `InMemoryRuleInventoryRepository` und `InMemoryHypothesisRepository`.

### Rationalvereinfachung

`DiscoveryDemos.rationalSimplificationExamples()` enthält u. a.:

```text
(x*x)/x       → x
(a*b)/a       → b
(x+1)/(x+1)   → 1
```

`DiscoveryDemos.promoteRationalSimplification(...)` promoted eine
Rational-Kürzungsregel in das Inventar. Ein nachfolgender Suchlauf mit
`MacroMoveTransformationEngine` erreicht `(x*x)/x → x` in einer Suchkante,
während der atomare Default-Search bei Tiefe 1 nicht kürzt.

### Geometrische Reihe

`DiscoveryDemos.geometricSeriesHypothesis()` erzeugt aktuell eine strukturelle
Hypothese:

```text
1 + x
1 + x + x^2
1 + x + x^2 + x^3
→ S_(n+1) = S_n + x^n
```

Limitation: Die geschlossene Form `(1-x^n)/(1-x)` wird derzeit noch nicht
automatisch anti-unifiziert oder bewiesen.

## API

### `GET /api/identities`

Liefert alle Makroregel-Kandidaten als `IdentityReportDto`:

```json
{
  "identities": [
    {
      "id": "macro:expand>combine",
      "leftPattern": "(x+1)*(x+1)",
      "rightPattern": "x^2 + 2*x + 1",
      "ruleIdSequence": ["expand", "combine"],
      "occurrences": 3,
      "compressionRatio": 2.0,
      "proofStatus": "OBSERVED",
      "knownRuleStatus": "MATCHES_KNOWN_RULE",
      "supportingTransformationIds": ["p1", "p2", "p3"]
    }
  ]
}
```

`knownRuleStatus` kommt aus `KnownRuleRepository` (z. B. binomische Formeln).

### `POST /api/identities/{id}/promote`

Speichert die Makroregel als `ReusableRule` im `RuleInventoryRepository`.
Liefert die neu vergebene Regel-Id zurück.

## Validierungsstatus

Vorerst wird `CandidateProofStatus.OBSERVED` gesetzt. Eine zukünftige
Integration mit `CandidateValidator` (Random-Sampling über `EquivalenceService`)
kann den Status auf `VALIDATED_BY_EXAMPLES` oder höher anheben.

## Tests

- `MacroRuleMinerTest#detectsRepeatedMacroRuleSequence`
- `MacroRuleMinerTest#respectsMinOccurrencesThreshold`
- `SearchGraphEndpointsTest#identitiesEndpointListsAndPromotes`
- `GoalAwareMacroMoveSelectorTest` (Discovery Epic Teil 2)
- `DiscoveryIntegrationTest` (binomial formula, commutativity)
- `MacroMoveTransformationEngineTest` (aktive MacroMoves verkürzen Suchtiefe)
- `PathReplayDtoTest#replayStepCarriesCollapsedMacroExpansionWithAtomicSteps`
