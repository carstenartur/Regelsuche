# Testing Strategy (Teil 0)

Die Testpyramide folgt den Architekturgrenzen und hält den mathematischen Kern
schnell und deterministisch.

| Schicht | Ziel | Infrastruktur | Gradle-Task |
| --- | --- | --- | --- |
| Core-Tests | reine Logik, deterministisch, schnell | keine Container, kein Browser | `./gradlew test` (fokussiert auf `ast/parse/transform/search/egraph/validation`) |
| Integrations-Tests | Persistenz- und API-Wiring | JVM + optional externe Systeme (z. B. Neo4j/Testcontainers je nach Testklasse) | `./gradlew test` |
| Browser-E2E | echte Nutzerflüsse, Replay/Reports/UI | Playwright + in-process `WebWorkbenchServer` | `./gradlew e2eTest` |
| Container-E2E | Docker-Image- und Asset-Serving-Absicherung | Testcontainers + Docker | `./gradlew dockerE2eTest` |

## Regeln

1. Core-Tests müssen ohne Container reproduzierbar bleiben.
2. Integrations-/Persistenztests dürfen Infrastruktur verwenden, aber klar von
   Core-Tests getrennt.
3. Browser-E2E testet End-to-End-Flows inkl. Replay/Report-Pfade.
4. Neue Features bekommen zuerst schnelle, fokussierte Unit-Tests; schwere
   Infrastrukturtests ergänzen nur das notwendige Wiring.

Die praktische Task-Referenz bleibt zusätzlich in [testing.md](testing.md).
