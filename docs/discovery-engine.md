# Discovery Engine

Die produktive Discovery-Pipeline in Regelsuche folgt dem Pfad:

`Seed → Discovery/Search/Validation → Replay → Persistenz → Report`

Zentrale Bausteine:

- `ScientificDiscoveryWorkflow` bootet die produktive App-Wiring-Schicht und führt reproduzierbare Discovery-Läufe aus.
- `DeterministicDiscoveryExperimentRunner` wertet Seeds deterministisch, budgetiert und optional parallel aus.
- `DiscoveryReplayArtifactWriter` erzeugt `discovery-report.{json,html,md}`, `discovery-replay.json`, `discovery-summary.png` und `discovery-replay.gif`.
- `PathReplayDto` und die Web-Workbench liefern das interaktive Schritt-für-Schritt-Replay inklusive Makrozügen, Domänenkarten und Proof-Status.
- `TermRuleIndex`/`RootSymbolTermRuleIndex` bilden den ersten Index-Layer für
  atomare Regeln und gelernte Makrozüge; Feature-Vektor- und
  Discrimination-Tree-Varianten sind als Erweiterungspunkte angelegt.

Wichtige Einstiegspunkte:

- [docs/rule-discovery.md](rule-discovery.md)
- [docs/experiment-runner.md](experiment-runner.md)
- [docs/replay-and-reports.md](replay-and-reports.md)
- [docs/scientific-reproducibility.md](scientific-reproducibility.md)

Reproduktionskommandos:

```bash
./gradlew :app:test --tests de.regelsuche.discovery.ScientificDiscoveryReproductionTest
./gradlew :app:dockerE2eTest --tests de.regelsuche.dockere2e.ScientificDiscoveryPostgresE2ETest
```
