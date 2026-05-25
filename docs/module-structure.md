# Module Structure (Teil 0)

Regelsuche nutzt jetzt echte Gradle-Subprojekte für die bereits sauber trennbaren
Schichten. Die restlichen Zielmodule bleiben bewusst in `app`, bis ihre heutigen
Import-Zyklen über die eingeführten Ports aufgelöst sind.

| Gradle-Projekt | Paketbasis | Verantwortung | Abhängigkeiten |
| --- | --- | --- | --- |
| `:regelsuche-core` | `de.regelsuche.ast`, `parse`, `canonical`, `rules`, `transform` (ohne `SymPyTransformationEngine`), `assumption`, `input`, `algebra`, `calculus`, `linalg`, `json`, `notify`, `util` | AST, Parser, kanonische Form, Rewrite-Regeln/Pattern-Ausdrücke, deterministische Mathematik-Helfer | keine Projektabhängigkeiten, keine Infrastruktur-Libraries |
| `:regelsuche-egraph` | `de.regelsuche.egraph` | E-Graph, Equality Saturation, Pattern-Matching | `:regelsuche-core` |
| `:regelsuche-validation` | `de.regelsuche.equivalence`, `validation` | Äquivalenzchecks, Gegenbeispiel-/Validierungsports und aktuelle SymPy-Validierung | `:regelsuche-core` |
| `:app` | `de.regelsuche.App`, `cli`, `web`, `api`, `search`, `mining`, `discovery`, `inventory`, `graph`, `persistence`, `export`, `didactic`, `proof`, `demo`, `equation`, `inequality`, `paths`, `jobs`, `benchmark`, plus `transform.SymPyTransformationEngine` | Runtime-Wiring, Web/CLI, Persistence, Search/Learning/Discovery und noch zyklische obere Schichten | `:regelsuche-core`, `:regelsuche-egraph`, `:regelsuche-validation`, Neo4j/GraalVM/WebAssets |

## Noch nicht physisch getrennte Zielmodule

Die Zielmodule `regelsuche-search`, `regelsuche-learning`,
`regelsuche-persistence`, `regelsuche-experiments`, `regelsuche-web` und
`regelsuche-cli` sind fachlich beschrieben, bleiben aber vorerst in `app`, weil
der aktuelle Code noch eine obere SCC enthält:

```text
api, discovery, explain, export, graph, inventory, mining, search
```

Diese Pakete werden über die Teil-0-Ports (`RuleIndex`, `SearchTraceStore`,
`HypothesisRepository`, `DiscoveryExperimentRunner`, …) entkoppelt, bevor sie als
eigene Gradle-Projekte herausgelöst werden.
