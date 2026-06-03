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
```

Siehe auch:

- [docs/rule-discovery.md](rule-discovery.md)
- [docs/scientific-reproducibility.md](scientific-reproducibility.md)
