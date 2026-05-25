# Discovery Engine

Die produktive Discovery-Pipeline in Regelsuche folgt dem Pfad:

`Seed → Discovery/Search/Validation → Replay → Persistenz → Report`

Zentrale Bausteine:

- `ScientificDiscoveryWorkflow` bootet die produktive App-Wiring-Schicht und führt reproduzierbare Discovery-Läufe aus.
- `DeterministicDiscoveryExperimentRunner` wertet Seeds deterministisch, budgetiert und optional parallel aus.
- `DiscoveryReplayArtifactWriter` erzeugt `discovery-report.{json,html,md}`, `discovery-replay.json`, `discovery-summary.png` und `discovery-replay.gif`.
- `PathReplayDto` und die Web-Workbench liefern das interaktive Schritt-für-Schritt-Replay inklusive Makrozügen, Domänenkarten und Proof-Status.
- `de.regelsuche.search.index.RuleCandidateIndex` erweitert den bisherigen
  `TermRuleIndex`-Pfad um `CandidateSet`, `SearchContext`, `CandidateBudget`
  und detaillierte `IndexMetrics`. `RootSymbolTermRuleIndex` bleibt die
  rückwärtskompatible Implementierung, führt aber optional eine mehrstufige
  Verengung aus Root-Symbol, Operator-Signatur, Feature-Vektor,
  Discrimination-Tree-Key und Goal-Ranking aus.
- `RuleIndexBenchmark` vergleicht naive Scans, Root-Symbol-Index und
  Multi-Stage-Index über wachsende Makroregel-Inventare, makrolastige
  Discovery-Szenarien und wachsende E-Graph-Matcher-Indizes. Exportierte
  Metriken sind u. a. `rulesSkippedByIndex`, `nodesScanned`,
  `matcherCacheHits` und durchschnittliche Kandidatenmengen.

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
