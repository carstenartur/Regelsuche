# Makroregeln & Emergent Identities

Makroregeln sind wiederkehrende Sequenzen atomarer Regeln. Sie werden aus den
bereits gefundenen `DiscoveredTransformation`s gemined und repräsentieren die
"emergenten Identitäten", die das System aus seiner eigenen Suche destilliert.

## Algorithmus

Implementiert in `de.regelsuche.mining.MacroRuleMiner` (Sliding-Window-Frequenzanalyse):

1. Pro `DiscoveredTransformation` wird die Regel-Id-Sequenz extrahiert.
2. Für jede Fensterlänge `n ∈ [minSequenceLength, maxSequenceLength]` werden alle
   zusammenhängenden Teilsequenzen gezählt.
3. Sequenzen, die ≥ `minOccurrences` mal auftauchen, werden als
   `MacroRuleCandidate` emittiert.
4. `leftPattern`/`rightPattern` sind die Start- bzw. End-Ausdrücke des ersten
   beobachteten Vorkommens. Anti-Unifikation zur Verallgemeinerung ist als
   spätere Erweiterung geplant.
5. `compressionRatio = sequenceLength / 1.0`.

Defaults (per Konstruktor konfigurierbar):

| Parameter           | Default |
|---------------------|---------|
| `minOccurrences`    | 2       |
| `minSequenceLength` | 2       |
| `maxSequenceLength` | 4       |

## MacroMoves als aktive Suchabkürzungen (Discovery Epic Teil 2)

Makroregeln aus dem Inventar werden über `InventoryBackedRewriteRuleProvider` aktiv
im Move-Generator genutzt. Ein Makrozug `A → B` fasst mehrere atomare Schritte
zusammen und verkürzt die Suchtiefe messbar.

### Replay-Expansion

`MacroMoveExpansion` (neu in `de.regelsuche.mining`, app-Modul) hält sowohl den
kompakten Makrozug als auch den vollständigen atomaren Ursprungspfad:

```java
record MacroMoveExpansion(
    String macroRuleId,
    String fromExpression,
    String toExpression,
    List<TransformationStep> atomicSteps,  // expandierbarer Ursprungspfad
    double compressionRatio,
    boolean expanded                        // UI-Zustand: aufgeklappt/zugeklappt
)
```

Im Replay ist der Makrozug kompakt sichtbar; `atomicSteps` enthält den vollständigen
ursprünglichen Pfad zum Aufklappen.

### Goal-aware MacroMove Selection

`GoalAwareMacroMoveSelector` (neu in `de.regelsuche.mining`, app-Modul) filtert
Inventar-Regeln nach:
- Confidence-Score ≥ Schwellwert (Standard: 0.5)
- Positive durchschnittliche Verbesserung (`averageImprovement > 0`)
- Mindest-Occurrence-Count
- Ziel-Ausrichtung: strukturelle Token-Überlappung zwischen Muster und aktuellem Ausdruck
  (ohne Zahlen und Platzhalter, um false positives zu vermeiden)

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

