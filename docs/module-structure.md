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
| `:regelsuche-persistence` | `de.regelsuche.persistence.PersistenceConfig`, `GraphPersistenceMode`; `de.regelsuche.checkpoint` | Persistenz-Ports/-Konfiguration und checkpointfähige Suchjob-Snapshots ohne Datenbanktreiber | `:regelsuche-core` |
| `:regelsuche-persistence-hibernate` | `de.regelsuche.persistence.relational` | PostgreSQL-/Hibernate-ORM-Entities, MigrationRunner, Repositories und Hibernate-Search-Facettenindex | `:regelsuche-persistence`, `:regelsuche-learning`, `:regelsuche-validation`, `:regelsuche-core`, Hibernate/PostgreSQL/Lucene |
| `:regelsuche-learning` | portable Teile von `de.regelsuche.mining` (`RuleCandidate`, Pattern-/Anti-Unification-Bausteine, `HypothesisRepository`, `RuleCandidateMiner`, `SuccessfulTransformationPath`) | Hypothesis-/Rule-Candidate-Mining und Anti-Unification-Primitiven ohne App-Orchestrierung | `:regelsuche-core`, `:regelsuche-search`, `:regelsuche-validation` |
| `:regelsuche-experiments` | `de.regelsuche.benchmark` (Benchmark-Kern, Result-DTOs, Report-Renderer, `DiscoveryExperimentRunner`), `de.regelsuche.example` | Experiment-/Benchmark-Primitiven, Seed-Corpus-Generator/-Runner-Port und Report-Artefakte ohne App-Orchestrierung | `:regelsuche-search`, `:regelsuche-validation` |
| `:regelsuche-cli` | `de.regelsuche.cli.core` | CLI-neutrale Command-Registry und Optionsparser-Primitiven ohne App-/Web-Wiring | keine Projektabhängigkeiten |
| `:regelsuche-discovery` | `de.regelsuche.discovery` | Portable Discovery-Pfad-DTOs (`DiscoveredTransformation`, `TransformationStep`) ohne Graph-/Export-/App-Orchestrierung | `:regelsuche-core`, `:regelsuche-search`, `:regelsuche-validation` |
| `:app` | `de.regelsuche.App`, app-spezifisches `cli.CliRouter`, `web`, `api`, orchestration-nahe `search`, app-spezifisches `mining` (`RuleDiscoveryService`, `MacroRuleMiner`), `inventory`, `graph`, app-spezifische `persistence.PersistenceContext`, `export`, restliches `didactic`, `proof`, `demo`, `equation`, `inequality`, `paths`, `jobs`, app-spezifische `benchmark.BenchmarkSuite`, plus `transform.SymPyTransformationEngine` | Runtime-Wiring, Web/CLI, Bootstrap und noch zyklische obere Schichten; Hibernate-Details liegen nicht mehr direkt in `app` | `:regelsuche-core`, `:regelsuche-egraph`, `:regelsuche-search`, `:regelsuche-validation`, `:regelsuche-persistence`, `:regelsuche-persistence-hibernate`, `:regelsuche-learning`, `:regelsuche-experiments`, `:regelsuche-cli`, `:regelsuche-discovery`, Neo4j/GraalVM/WebAssets |

## Noch nicht physisch getrennte Zielmodule

Das Zielmodul `regelsuche-web` bleibt vorerst in `app`, weil der aktuelle
Code noch eine obere SCC enthält. `regelsuche-cli` existiert bereits für
CLI-neutrale Primitiven; der app-spezifische `CliRouter` bleibt bis zur weiteren
Entkopplung in `app`. `regelsuche-discovery` existiert bereits für portable
Discovery-Pfad-DTOs; Graph-/Export-/Web-Orchestrierung bleibt in `app`.
`regelsuche-learning` existiert bereits für portable
Mining-Primitiven; Discovery-Orchestrierung (`RuleDiscoveryService`,
`MacroRuleMiner`) bleibt bis zur weiteren Entkopplung in `app`. `regelsuche-persistence` existiert für Konfiguration und
checkpointfähige Ports; der datenbankgebundene Hibernate-Adapter ist jetzt in
`:regelsuche-persistence-hibernate` ausgelagert:

```text
api, explain, export, graph, inventory, app-spezifisches mining, orchestration-nahe search
```

Diese Pakete werden über die Teil-0-Ports (`RuleIndex`, `SearchTraceStore`,
`HypothesisRepository`, `DiscoveryExperimentRunner`, …) entkoppelt, bevor sie als
eigene Gradle-Projekte herausgelöst werden.

## Nachgezogene Modularisierung (PR #48–#50)

```text
regelsuche-core
  → regelsuche-egraph / regelsuche-search / regelsuche-validation

regelsuche-learning / regelsuche-discovery
  → portable Ports und DTOs

regelsuche-persistence-hibernate
  → regelsuche-persistence Ports + Hibernate/PostgreSQL Adapter

app
  → Composition Root, Web/API/Docker/Bootstrap-Wiring
```
