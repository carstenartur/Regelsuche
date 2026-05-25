# Testing Strategy (Teil 0)

Die Testpyramide folgt den Architekturgrenzen und hält den mathematischen Kern
schnell und deterministisch.

| Schicht | Ziel | Infrastruktur | Gradle-Task |
| --- | --- | --- | --- |
| Core-Tests | reine Logik, deterministisch, schnell | keine Container, kein Browser, keine technischen Adapter | `./gradlew :regelsuche-core:test` |
| E-Graph-Tests | Equality-Saturation-/E-Graph-Logik gegen Core-Typen | keine Container, kein Browser | `./gradlew :regelsuche-egraph:test` |
| Search-Tests | Suchstrategien, CostModels und TranspositionTable-Abstraktion | keine Container, kein Browser, keine Persistenzadapter | `./gradlew :regelsuche-search:test` |
| Validation-Tests | Äquivalenz-/Rewrite-Validierung inklusive Adapter-Fallbacks | JVM, optionale technische Adapter | `./gradlew :regelsuche-validation:test` |
| Persistence-Tests | Persistenz-Konfiguration und Search-Checkpoint-Repositories | lokale Temp-Dateien, keine Container/Datenbanktreiber | `./gradlew :regelsuche-persistence:test` |
| Learning-Tests | Rule-Candidate-Mining, Pattern-/Anti-Unification-Primitiven, Hypothesis-Ports | keine Container, kein Browser, keine App-Orchestrierung | `./gradlew :regelsuche-learning:test` |
| Experiment-Tests | Benchmark-Kern, Report-Rendering, Experiment-Ports | keine Container, kein Browser, keine Persistenzadapter | `./gradlew :regelsuche-experiments:test` |
| CLI-Kern-Tests | Command-Registry, Optionsparser und andere CLI-neutrale Primitiven | keine Container, kein Browser, keine App-Orchestrierung | `./gradlew :regelsuche-cli:test` |
| App-/Integrationstests | Runtime-Wiring, Persistence, Search/Learning/Discovery, Web-APIs | JVM + optionale externe Systeme je Testklasse | `./gradlew :app:test` |
| Browser-E2E | echte Nutzerflüsse, Replay/Reports/UI | Playwright + in-process `WebWorkbenchServer` | `./gradlew :app:e2eTest` |
| Container-E2E | Docker-Image- und Asset-Serving-Absicherung | Testcontainers + Docker | `./gradlew :app:dockerE2eTest` |

## Regeln

1. Core-Tests müssen ohne Container und ohne technische Adapter reproduzierbar bleiben.
2. E-Graph, Search, Validation, Persistence, Learning, Experiments und CLI testen ihre Modulgrenzen über Projektabhängigkeiten.
3. Integrations-/Persistenztests dürfen Infrastruktur verwenden, aber klar von
   Core-Tests getrennt.
4. Browser-E2E testet End-to-End-Flows inkl. Replay/Report-Pfade.
5. Neue Features bekommen zuerst schnelle, fokussierte Unit-Tests; schwere
   Infrastrukturtests ergänzen nur das notwendige Wiring.

Die praktische Task-Referenz bleibt zusätzlich in [testing.md](testing.md).
