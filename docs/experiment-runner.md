# Experiment Runner

Für wissenschaftliche Discovery-Läufe nutzt Regelsuche den `DeterministicDiscoveryExperimentRunner`.

Eigenschaften:

- deterministische Seed-Reihenfolge über `SeedExpression.stableKey()`
- globales Budget
- optionale Parallelität ohne veränderte Report-Reihenfolge
- stabile JSON-/Replay-Artefakte für CI und Reproduktionsanhänge

Wichtige Klassen:

- `ScientificSeedCorpora`
- `SeedExpression`
- `DeterministicDiscoveryExperimentRunner`
- `ScientificDiscoveryWorkflow`

Typische Kommandos:

```bash
./gradlew :app:test --tests de.regelsuche.discovery.ScientificDiscoveryReproductionTest
./gradlew benchmarkReport
./gradlew runDiscoveryCampaign1
./gradlew runDiscoveryCampaign2
./gradlew runDiscoveryCampaign3
./gradlew runDiscoveryCampaign4
./gradlew runDiscoveryCampaign5
./gradlew runDiscoveryCampaign6
./gradlew runDiscoveryPromotionPipeline
```

## Discovery Campaign 6: Countable Move Search Probe

`DiscoveryCampaignSixRunner` kombiniert pro Fall drei Sichtweisen:

1. **Depth-1 Candidate Probe**
   - erwarteter Move vorhanden?
   - Move-only / Classic-only / Overlap
   - klassische vs. Move-Zählwerte
2. **Multi-step Countable Move Search**
   - bounded search (`maxDepth<=4`, `maxStates`)
   - Zielerreichbarkeit, Pfadlänge, applied moves/rules, ordinal path
   - explored/unique states und Failure Reason
3. **Interpretation**
   - Tauglichkeit des Falls
   - fehlende Move-Familie
   - Einordnung gegenüber klassischer Kandidatenerzeugung

Die vier Kernfälle bleiben:

- `x - 1 = 0` → Ziel `x = 1` (oder äquivalente Normalform), erwarteter `+1`-Move
- `x^2 + 6*x + 5` → Ziel `(x + 3)^2 - 4`, erwarteter complete-square Move
- `(x+1)^2 - (x+1)` → Ziel `(x+1)*x`, erwarteter repeated-subexpression/factor Move
- `x*(y+1)+z*(y+1)` → Ziel `(y+1)*(x+z)`, erwarteter common-subexpression Move

Der Report (`app/build/reports/discovery-campaign-6/`) enthält je Fall:

- Input und Target
- Depth-1 Candidate Summary
- Multi-step Search Result
- Successful Path Tabelle (`step`, `before`, `moveKind`, `ruleId`, `ordinal`, `parameters`, `after`)
- Classic-vs-Move Vergleich
- Interpretation und Architecture Note
- Related follow-up issues

Siehe auch:

- [docs/rule-discovery.md](rule-discovery.md)
- [docs/scientific-reproducibility.md](scientific-reproducibility.md)
