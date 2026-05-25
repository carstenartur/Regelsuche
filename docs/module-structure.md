# Module Structure (Teil 0)

Regelsuche nutzt jetzt echte Gradle-Subprojekte für die bereits sauber trennbaren
Schichten. Die restlichen Zielmodule bleiben bewusst in `app`, bis ihre heutigen
Import-Zyklen über die eingeführten Ports aufgelöst sind.

| Gradle-Projekt | Paketbasis | Verantwortung | Abhängigkeiten |
| --- | --- | --- | --- |
| `:regelsuche-core` | `de.regelsuche.ast`, `parse`, `canonical`, `rules`, `transform` (ohne `SymPyTransformationEngine`), `assumption`, `input`, `algebra`, `calculus`, `linalg`, `json`, `notify`, `util` | AST, Parser, kanonische Form, Rewrite-Regeln/Pattern-Ausdrücke, deterministische Mathematik-Helfer | keine Projektabhängigkeiten, keine Infrastruktur-Libraries |
| `:regelsuche-egraph` | `de.regelsuche.egraph` | E-Graph, Equality Saturation, Pattern-Matching | `:regelsuche-core` |
| `:regelsuche-search` | `de.regelsuche.search` (ohne `TransformationSearchService`, `TeachingPathScorer`), `search.strategy`, `search.memory` (ohne JSON/Neo4j-Adapter), `scoring`, `scoring.cost`, didaktische CostModel-Primitiven | Suchprofile, Strategien, CostModels, SearchMemory und TranspositionTable-Port/InMemory-Implementierung | `:regelsuche-core`, `:regelsuche-egraph` |
| `:regelsuche-validation` | `de.regelsuche.equivalence`, `validation` | Äquivalenzchecks, Gegenbeispiel-/Validierungsports, gemeinsamer `CandidateProofStatus` und aktuelle SymPy-Validierung | `:regelsuche-core` |
| `:regelsuche-persistence` | `de.regelsuche.persistence.PersistenceConfig`, `GraphPersistenceMode`; `de.regelsuche.checkpoint` | Persistenz-Konfiguration, checkpointfähige Suchjob-Snapshots und In-Memory-/JSON-Datei-Repositories ohne Datenbanktreiber | `:regelsuche-core` |
| `:regelsuche-learning` | portable Teile von `de.regelsuche.mining` (`RuleCandidate`, Pattern-/Anti-Unification-Bausteine, `HypothesisRepository`, `RuleCandidateMiner`, `SuccessfulTransformationPath`) | Hypothesis-/Rule-Candidate-Mining und Anti-Unification-Primitiven ohne App-Orchestrierung | `:regelsuche-core`, `:regelsuche-search`, `:regelsuche-validation` |
| `:regelsuche-experiments` | `de.regelsuche.benchmark` (Benchmark-Kern, Result-DTOs, Report-Renderer, `DiscoveryExperimentRunner`) | Experiment-/Benchmark-Primitiven, Seed-Corpus-Runner-Port und Report-Artefakte ohne App-Orchestrierung | `:regelsuche-search`, `:regelsuche-validation` |
| `:app` | `de.regelsuche.App`, `cli`, `web`, `api`, orchestration-nahe `search`, app-spezifisches `mining` (`RuleDiscoveryService`, `MacroRuleMiner`), `discovery`, `inventory`, `graph`, app-spezifische `persistence`, `export`, restliches `didactic`, `proof`, `demo`, `equation`, `inequality`, `paths`, `jobs`, app-spezifische `benchmark.BenchmarkSuite`, plus `transform.SymPyTransformationEngine` | Runtime-Wiring, Web/CLI, datenbankgebundene Persistence, Learning/Discovery-Orchestrierung und noch zyklische obere Schichten | `:regelsuche-core`, `:regelsuche-egraph`, `:regelsuche-search`, `:regelsuche-validation`, `:regelsuche-persistence`, `:regelsuche-learning`, `:regelsuche-experiments`, Neo4j/GraalVM/WebAssets |

## Noch nicht physisch getrennte Zielmodule

Die Zielmodule `regelsuche-web` und `regelsuche-cli` bleiben vorerst in
`app`, weil der aktuelle Code noch eine obere SCC enthält. `regelsuche-learning`
existiert bereits für portable Mining-Primitiven; Discovery-Orchestrierung
(`RuleDiscoveryService`, `MacroRuleMiner`) bleibt bis zur weiteren Entkopplung
in `app`. `regelsuche-persistence` existiert bereits für Konfiguration und
checkpointfähige Ports; datenbankgebundene App-Adapter bleiben bis zur weiteren
Entkopplung in `app`:

```text
api, discovery, explain, export, graph, inventory, app-spezifisches mining, orchestration-nahe search
```

Diese Pakete werden über die Teil-0-Ports (`RuleIndex`, `SearchTraceStore`,
`HypothesisRepository`, `DiscoveryExperimentRunner`, …) entkoppelt, bevor sie als
eigene Gradle-Projekte herausgelöst werden.
