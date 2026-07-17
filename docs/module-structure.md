# Module Structure (Teil 0)

Regelsuche nutzt echte Gradle-Subprojekte für die sauber trennbaren Schichten. Die restlichen Zielmodule bleiben bewusst in `app`, bis ihre heutigen Import-Zyklen über die eingeführten Ports aufgelöst sind.

| Gradle-Projekt | Paketbasis | Verantwortung | Abhängigkeiten |
| --- | --- | --- | --- |
| `:regelsuche-core` | `de.regelsuche.ast`, `parse`, `canonical`, `rules`, `transform` (ohne `SymPyTransformationEngine`), `assumption`, `input`, `algebra`, `calculus`, `linalg`, `json`, `notify`, `util` | AST, Parser, kanonische Form, Rewrite-Regeln/Pattern-Ausdrücke, deterministische Mathematik-Helfer | keine Projektabhängigkeiten, keine Infrastruktur-Libraries |
| `:regelsuche-egraph` | `de.regelsuche.egraph` | E-Graph, Equality Saturation, Pattern-Matching | `:regelsuche-core` |
| `:regelsuche-search` | `de.regelsuche.search` (ohne `TransformationSearchService`, `TeachingPathScorer`), `search.strategy`, `search.memory` (ohne JSON/Neo4j-Adapter), `scoring`, `scoring.cost` | Suchprofile, Strategien, CostModels, SearchMemory und TranspositionTable-Port/InMemory-Implementierung | `:regelsuche-core`, `:regelsuche-egraph` |
| `:regelsuche-validation` | `de.regelsuche.equivalence`, `validation` | Äquivalenzchecks, Gegenbeispiel-/Validierungsports, Registry/Ports für mathematische Algorithmen | `:regelsuche-core` |
| `:regelsuche-solver-ir` | `de.regelsuche.solver.ir` | Solver-neutrale Obligation-, Translation-, Result- und Execution-Verträge sowie exakte interne Referenzbackends | `:regelsuche-core`, `:regelsuche-search`, `:regelsuche-validation`, `:regelsuche-math-algorithms` |
| `:regelsuche-solver-portfolio` | `de.regelsuche.solver.portfolio` | Capability-basierte Solver-Auswahl, Budgets, Timeouts, Cache, Konflikte und externer Z3-Adapter | `:regelsuche-solver-ir` |
| `:regelsuche-benchmarks` | `de.regelsuche.benchmarks` | Track-spezifische Vergleichsverträge, Informationsparität, externe SymPy-/Z3-Baselines und kanonische Benchmark-Evidence | `:regelsuche-search`, `:regelsuche-solver-ir`, `:regelsuche-solver-portfolio` |
| `:regelsuche-math-algorithms` | `de.regelsuche.math.algorithms` | Reine Java-Implementierungen (direkte Polynom-Normalform-Identitäten, kleine Gröbner-Idealreduktion, Knuth-Bendix/Critical-Pairs, PSLQ-ähnliche numerische Relationssuche) | `:regelsuche-core`, `:regelsuche-validation` |
| `:regelsuche-math-jas` | `de.regelsuche.math.jas` | Optional isolierte Adapter-Schicht für JAS-nahe Backends | `:regelsuche-validation` |
| `:regelsuche-persistence` | `de.regelsuche.persistence.PersistenceConfig`, `GraphPersistenceMode`; `de.regelsuche.checkpoint` | Persistenz-Ports/-Konfiguration und checkpointfähige Suchjob-Snapshots ohne Datenbanktreiber | `:regelsuche-core` |
| `:regelsuche-persistence-hibernate` | `de.regelsuche.persistence.relational` | PostgreSQL-/Hibernate-ORM-Entities, MigrationRunner, Repositories und Hibernate-Search-Facettenindex | `:regelsuche-persistence`, `:regelsuche-learning`, `:regelsuche-validation`, `:regelsuche-core` |
| `:regelsuche-learning` | portable Teile von `de.regelsuche.mining` | Hypothesis-/Rule-Candidate-Mining und Anti-Unification-Primitiven ohne App-Orchestrierung | `:regelsuche-core`, `:regelsuche-search`, `:regelsuche-validation`, `:regelsuche-solver-ir` |
| `:regelsuche-experiments` | `de.regelsuche.benchmark`, `de.regelsuche.example`, portable Autopilot-Kernverträge | Experiment-/Benchmark-Primitiven, wissenschaftliches Seed-Corpus, reproduzierbarer Runner sowie Plan-, Budget- und Evidence-DAG-Verträge | `:regelsuche-search`, `:regelsuche-validation`, `:regelsuche-math-algorithms`, `:regelsuche-discovery` |
| `:regelsuche-autopilot` | Composition-Anteile von `de.regelsuche.experiments.autopilot` | Verbindung der unabhängigen Autopilot-Verträge mit Mining, Project-Novelty, Proof, Lifecycle und äußeren Campaign-Artefakten | `:regelsuche-experiments`, `:regelsuche-learning` |
| `:regelsuche-release` | `de.regelsuche.release` | Fail-closed Releaseprofile, Kandidatenqualifikation und gepaarte Utility | Autopilot-/Learning-/Search-/Solver-Evidence nach explizitem Build-Vertrag |
| `:regelsuche-cli` | `de.regelsuche.cli.core` | CLI-neutrale Command-Registry und Optionsparser-Primitiven | keine Projektabhängigkeiten |
| `:regelsuche-discovery` | `de.regelsuche.discovery` | Portable Discovery-Pfad-DTOs, Discovery-Optionen/Profile und Hypothesenoperator-Metadaten | `:regelsuche-core`, `:regelsuche-search`, `:regelsuche-validation` |
| `:app` | Runtime-Wiring, Web/CLI, Bootstrap und noch zyklische obere Schichten | Composition Root, Web/API/Docker/Bootstrap-Wiring | die produktiven Fachmodule plus technische Adapter |

## Solver- und Benchmark-Grenze

`:regelsuche-solver-ir` definiert mathematische Problem- und Ergebnisverträge. `:regelsuche-solver-portfolio` entscheidet nur über Ausführung; seine Reports sind Telemetrie und keine mathematische Evidenz. `:regelsuche-benchmarks` verwendet beide Schichten, darf aber keine zweite Solver-IR und keinen universellen Capability-Score einführen.

Externe SymPy-/Z3-Prozesse bleiben Benchmark- beziehungsweise Solver-Adapter. Sie dürfen weder in `:regelsuche-core` noch in `:regelsuche-search` einsickern. Fehlende Verfügbarkeit, Übersetzungsverlust und mathematische Widerlegung bleiben getrennte Zustände.

## Autopilot-Grenze

Die Autopilot-Kernverträge bleiben in `:regelsuche-experiments`, weil sie nur Research Briefs, Budgets, unveränderliche Beobachtungsbranches, Aggregate-Entscheidungen, Receipts und Evidence-DAG-Provenienz beschreiben. Sie dürfen nicht von konkreten Mining-, Novelty-, Proof- oder Lifecycle-Implementierungen abhängen.

`:regelsuche-autopilot` ist die schmale Composition-Schicht darüber. Nur dort werden Open-Target-Evidence, Novelty-/Proof-Reports und der konservative Hypothesis-Handoff an die generischen Verträge gebunden.

## Noch nicht physisch getrennte Zielmodule

Das Zielmodul `regelsuche-web` bleibt vorerst in `app`, weil der aktuelle Code noch eine obere SCC enthält. `regelsuche-cli` existiert bereits für CLI-neutrale Primitiven; der app-spezifische `CliRouter` bleibt bis zur weiteren Entkopplung in `app`. `regelsuche-discovery` enthält portable Discovery-Pfad-DTOs; Graph-/Export-/Web-Orchestrierung bleibt in `app`.

Für die nächste Architekturwelle sind die Zielmodule `regelsuche-search-index`, `regelsuche-discovery-ranking`, `regelsuche-provenance`, `regelsuche-symbolic-regression`, `regelsuche-cas`, `regelsuche-cas-jas`, `regelsuche-cas-singular` und `regelsuche-dashboard` vorbereitet, aber noch nicht physisch eingeschaltet.

## Aktuelle Hauptrichtung

```text
regelsuche-core
  → regelsuche-egraph / regelsuche-search / regelsuche-validation
  → regelsuche-solver-ir
  → regelsuche-solver-portfolio
  → regelsuche-benchmarks

regelsuche-learning / regelsuche-discovery
  → portable Ports und DTOs

regelsuche-experiments
  → unabhängige Experiment-, Planner- und Evidence-DAG-Verträge

regelsuche-autopilot
  → regelsuche-experiments + regelsuche-learning Composition

app
  → Composition Root, Web/API/Docker/Bootstrap-Wiring
```
