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
- `de.regelsuche.learning.MacroLearningPipeline` als Evidence-Grade-Pipeline:
  erfolgreiche Replay-Pfade sammeln, Source/Target-Paare extrahieren, Schema
  generalisieren, Parameterrelationen minen, generierte Instanzen validieren,
  Counterexamples suchen, Confidence bewerten und erst danach promoten.

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

Hidden-Structure-Funde trennen `DiscoveryResultKind` (Suchzustand) von Evidence:

| Level | `DiscoveryResultKind` | Bedeutung |
|-------|------------------------|-----------|
| 0 | `NO_CANDIDATE` | kein begrenzter Hypothesenkandidat |
| 1 | `HYPOTHESIS_ONLY` | Kandidat erzeugt, aber kein Replay-Bridge-State |
| 2 | `BRIDGE_FOUND` | validierter Zwischenzustand wie Quadratdifferenz oder quadratische Ergänzung |
| 3 | `TRANSFORMED` | Replay erreicht ein transformiertes Ziel |
| Evidence | `FACTORED`, `SIMPLIFIED`, `MACRO_LEARNED`, `MACRO_REUSED`, `EQUIVALENCE_VALIDATED` | zusätzliche Fähigkeiten/Belege, nicht Suchzustand |

Die aktuelle Infrastruktur unterstützt Sophie-Germain-Bridges und konservative
quadratische Ergänzung (`ConservativeCompleteSquareHypothesisOperator`). Alle Gallery- und
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
   `A=2*x`, `A=x^2` und `A=n+2` mit dem konfigurierten `EquivalenceService`.
   Dieser Pfad ist explizit opt-in und senkt nicht die normalen Mining-Schwellen.
3. **Parametric Sophie-Germain learning:** Aus dem tatsächlichen Replay
   `x^4 + 4*y^4 → (x^2 - 2*x*y + 2*y^2) * (x^2 + 2*x*y + 2*y^2)`
   lernt `PatternGeneralizer` das Schema
   `A^4 + 4*B^4 → (A^2 - 2*A*B + 2*B^2) * (A^2 + 2*A*B + 2*B^2)`.
   Die Promotion validiert generierte Substitutionen für beide unabhängigen
   Platzhalter und das gelernte Makro kann anschließend auf
   `(x+1)^4 + 4*z^4` mit `A = x + 1`, `B = z` wiederverwendet werden. Es gibt
   keine hart codierte Sophie-Germain-Rewrite-Regel.

Aktuelles Matching ist strukturell mit vorhandener Normalisierung. Formen wie
`(x^2)^2 + 4` können daher nach `ast_power_of_power` als `x^4 + 4` vom Makro
erfasst werden. Algebraisch äquivalente, aber strukturell verdeckte Formen wie
`x^4 + 2*x^2 + 1 + 3 - 2*x^2` werden noch nicht über eine ganze
Äquivalenzklasse gematcht. Hypothesen werden operatorspezifisch und begrenzt
generiert; vor Discovery-, Makro- oder Gallery-Erfolg ist Validierung
verpflichtend.

## Parametric Sophie-Germain learning

The Sophie-Germain macro is discovered from an actual replay of
`x^4 + 4*y^4` and generalized to `A^4 + 4*B^4`. Promotion validates generated
substitutions for the independent placeholders before the macro is enabled. The
same learned rule can then be reused on `(x+1)^4 + 4*z^4` by binding
`A = x + 1` and `B = z`; no hardcoded Sophie-Germain rewrite rule is installed.

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

## Evidence-grade MacroLearningPipeline

`MacroLearningPipeline` lernt aus tatsächlichen `SuccessfulTransformationPath`-
Replay-Pfaden und schreibt nur validierte `ReusableRule`s in das Inventar.
Eine Promotion ohne Validierung ist nicht erlaubt. Die Ergebnisstruktur
`MacroLearningResult` enthält die berührten/promoteten Regeln, alle generierten
Validierungsbeispiele, Counterexample-Suchergebnisse und Stage-Evidence.

Qualitätsgates vor Promotion:

- Source-Pattern enthält mindestens einen Platzhalter.
- Target-Pattern nutzt nur gebundene Platzhalter und Konstanten.
- strukturierte `ParameterRelation`s sind parsebar und durch Guards erzwingbar.
- Annahmen aus dem Replay werden normalisiert und in Regel sowie Replay-Expansion
  getragen.
- generierte Instanzen sind äquivalent.
- Counterexample-Suche findet keinen Gegenbeleg oder symbolische Äquivalenz liegt
  vor.
- Confidence liegt über dem Schwellwert.

### Parameterrelationen

Relationen werden als `ParameterRelation(left, operator, right, relationType)`
modelliert; Strings bleiben nur Anzeige-/Legacy-Format. Beispiele:

- `B = A + 1` mit `UNIT_STEP`
- `B = A + k` mit `AFFINE_OFFSET`
- `C = A^2` mit `POWER`
- `K != 0` mit `NON_ZERO_ASSUMPTION`

`MacroApplicabilityGuard` nutzt strukturierte Relationen, um unsichere
Wiederverwendung zu verhindern. Das Teleskoping-Schema wird deshalb als
`1/(A*(A+1)) -> 1/A - 1/(A+1)` gelernt; die unsichere Variante
`1/(A*B) -> 1/A - 1/B` wird nicht promotet, solange `B=A+1` nicht erhalten und
erzwingbar ist.

### Annahmen

Rationalisierungs-Makros tragen Annahmen weiter. Aus
`1/(sqrt(x)+1) -> (sqrt(x)-1)/(x-1)` wird
`1/(sqrt(A)+1) -> (sqrt(A)-1)/(A-1)` mit `A != 1`. Die Annahme wird in
`ReusableRule.assumptions()` und in `MacroMoveExpansion.assumptions()` sichtbar.

### Validierungslevel

- `VALIDATED_BY_EXAMPLES`: alle generierten Substitutionen bestehen und die
  Counterexample-Suche findet keinen Gegenbeleg.
- `SYMBOLICALLY_VERIFIED`: zusätzlich bestätigt der symbolische
  `EquivalenceService` das Schema.
- `OBSERVED`: nur gesehen; nicht ausreichend für Promotion.

Ein einzelnes Replay reicht nur dann zur Promotion, wenn diese Validierung
bestanden ist. Andernfalls bleibt die Generalisierung verworfen, auch wenn der
konkrete Replay-Pfad erfolgreich war.

### Beispiele abgelehnter Generalisierungen

- `1/(A*B) -> 1/A - 1/B` ohne `B=A+1`: übergeneralisiert und erzeugt
  False Positives wie `1/(n*(n+2))`.
- Target-Patterns mit ungebundenen Platzhaltern.
- Schemas, bei denen eine der generierten Substitutionen (`A=x`, `A=y`,
  `A=x+1`, `A=2*x`, `A=x^2`, `A=n+2`) nicht äquivalent ist.

## Tests

- `MacroRuleMinerTest#detectsRepeatedMacroRuleSequence`
- `MacroRuleMinerTest#respectsMinOccurrencesThreshold`
- `SearchGraphEndpointsTest#identitiesEndpointListsAndPromotes`
- `GoalAwareMacroMoveSelectorTest` (Discovery Epic Teil 2)
- `DiscoveryIntegrationTest` (binomial formula, commutativity)
- `MacroMoveTransformationEngineTest` (aktive MacroMoves verkürzen Suchtiefe)
- `PathReplayDtoTest#replayStepCarriesCollapsedMacroExpansionWithAtomicSteps`

## Discovery profiles

`DiscoveryOptions` bündelt `DiscoveryEngineOptions` für Hypothesenoperatoren,
Makro-Wiederverwendung, Suchbudget/-tiefe und `DiscoveryLearningOptions` für
Makro-Lernen, Validierung generierter Instanzen und Promotion. Die benannten
`DiscoveryProfile`s reduzieren hart verdrahtete Spezialfälle:

| Profil | Verwendung |
|--------|------------|
| `PURE_REWRITE` | deterministische Baseline nur mit atomaren Rewrite-Regeln |
| `HYPOTHESIS_ONLY` | Experimente mit Hypothesenoperatoren, aber ohne gelernte Regeln |
| `MACRO_REUSE_ONLY` | Evaluation eines vorhandenen Makroregel-Inventars ohne neue Hypothesen |
| `HYPOTHESIS_AND_MACRO_REUSE` | Engine-Profil mit Hypothesen und Wiederverwendung bereits gelernter Makros |
| `RESEARCH_DISCOVERY_PIPELINE` | Orchestrierungsprofil mit Hypothesen, Makro-Reuse, optionalem Makro-Lernen/Promotion und Gallery |

`HypothesisOperatorRegistry` ist die zentrale Liste der verfügbaren
`HypothesisOperatorDescriptor`s mit stabiler ID, Display-Name, Familie, Factory,
Default-Enablement und Tags. Die aktuelle Reihenfolge ist deterministisch:

1. `hypothesis_difference_of_squares_preparation`
2. `hypothesis_complete_square_preparation`

`DiscoveryEngineFactory` komponiert Engines immer in der Reihenfolge
**base rewrite → hypothesis operators → learned macro moves**. Dadurch muss der
Workflow nicht mehr wissen, welche Operator-Klassen direkt zu instanziieren sind,
und neue Varianten lassen sich über Optionen statt über verstreute Konstruktoren
steuern. Das senkt die kognitive Last, weil Profilwahl, Operatorliste und
Engine-Reihenfolge jeweils genau eine Zuständigkeit haben. Die Factory lernt oder
promotet keine Makros; das ist ausschließlich Orchestrierungslogik.

Neue Hypothesenoperatoren werden so ergänzt:

1. `HypothesisOperator` implementieren.
2. Operator mit stabiler Rule-ID in die Registry aufnehmen.
3. Corpus-Tests für positive Fälle ergänzen.
4. False-Positive-/Near-Miss-Tests ergänzen.
5. Optional eine Gallery-Regel ergänzen, wenn ein echtes Replay sie belegt.

`ConservativeCompleteSquareHypothesisOperator` ist bewusst konservativ: Der bounded
square-completion Operator emittiert nur Kandidaten mit Rest `0` oder negativem
perfekten Quadrat. Er erhebt keinen Anspruch, alle gültigen quadratischen
Ergänzungen abzudecken, z. B. nicht jede Form wie `x^2 + 6*x + 6`.
