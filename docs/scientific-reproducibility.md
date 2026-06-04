# Scientific Reproducibility

Scientific Discovery in Regelsuche ist auf reproduzierbare Seeds, stabile Reihenfolge und CI-taugliche Artefakte ausgelegt.

Grundprinzipien:

- kuratierte Seed-Corpora (`ScientificSeedCorpora`)
- deterministische Sortierung und Budgets
- stabile JSON-Ausgabe mit markierten `volatileFields`
- reproduzierbare Replay-/Report-Artefakte für lokale Läufe und CI

Wichtige Tests:

```bash
./gradlew :app:test --tests de.regelsuche.discovery.ScientificDiscoveryReproductionTest
./gradlew :app:dockerE2eTest --tests de.regelsuche.dockere2e.ScientificDiscoveryPostgresE2ETest
./gradlew runDiscoveryCampaign1
./gradlew runDiscoveryCampaign2
./gradlew runDiscoveryCampaign3
./gradlew runDiscoveryCampaign4
./gradlew runDiscoveryPromotionPipeline
```

Die erzeugten Artefakte eignen sich für:

- PR-Anhänge
- wissenschaftliche Notizen
- Browser-basierte Regressionen
- Dokumentations-Screenshots und Replay-Demos

Siehe auch:

- [docs/discovery-engine.md](discovery-engine.md)
- [docs/experiment-runner.md](experiment-runner.md)
- [docs/replay-and-reports.md](replay-and-reports.md)
