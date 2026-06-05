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

## Discovery Campaign 6: Countable Move Enumeration Probe

`DiscoveryCampaignSixRunner` vergleicht für vier gezielte Depth-1-Fälle die klassische
Kandidatenerzeugung mit explizit enumerierten `RewriteMove`s:

- **Cancellation auf Gleichungen**: `x - 1 = 0` mit Kandidat `+1`
- **Complete square**: `x^2 + 6*x + 5` mit Parametern `shift=3`, `residue=-4`
- **Repeated subexpression**: `(x+1)^2 - (x+1)` mit Teilausdruck `x+1`
- **Common subexpression**: `x*(y+1)+z*(y+1)` mit Teilausdruck `y+1`

Der Report (`app/build/reports/discovery-campaign-6/`) enthält je Fall:

- klassische Engine-Kandidaten
- Move-Enumerator-Kandidaten
- Überschneidungen
- Kandidaten nur aus Move-Enumeration
- Kandidaten nur aus der alten Engine
- einen Check, ob der Kandidat im bestehenden `BestFirstSearchStrategy`-Raum auf Tiefe 1 sichtbar ist

Siehe auch:

- [docs/rule-discovery.md](rule-discovery.md)
- [docs/scientific-reproducibility.md](scientific-reproducibility.md)
