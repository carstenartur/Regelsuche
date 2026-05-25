# ADR 0001: Physical core/egraph/search/validation modules first

- Status: Accepted
- Date: 2026-05-25

## Context

Discovery-, Persistenz- und Experiment-Funktionen erweitern Regelsuche stark.
Bei direkter Weiterentwicklung ohne zusätzliche Abstraktionsebenen steigt die
kognitive Last und es drohen Architekturdrift, God-Classes und langsame Tests.

Der aktuelle Code enthält noch Import-Zyklen in den oberen Schichten
(`api`, `discovery`, `export`, `graph`, `inventory`, `mining`, orchestration-nahe `search`, …).
Ein vollständiger Big-Bang-Split in alle Zielmodule würde deshalb zyklische
Gradle-Projektabhängigkeiten erzeugen.

## Decision

Wir ziehen die bereits azyklischen Grenzen physisch als Gradle-Subprojekte:

- `:regelsuche-core`
- `:regelsuche-egraph`
- `:regelsuche-search`
- `:regelsuche-validation`

Die oberen, noch zyklischen Schichten bleiben in `:app` und werden über die
Teil-0-Ports entkoppelt, bevor sie in eigene Projekte extrahiert werden.

Die Regeln sind in
- [module-structure.md](../module-structure.md),
- [dependency-rules.md](../dependency-rules.md),
- [testing-strategy.md](../testing-strategy.md)

festgehalten.

Zusätzlich wird per automatisiertem Test abgesichert, dass der mathematische
Kern keine Infrastruktur-Abhängigkeiten (Neo4j, JPA/Hibernate, Spring, Docker,
Testcontainers, GraalVM) importiert und dass die neuen Gradle-Projekte korrekt
verdrahtet bleiben.

## Consequences

- Core-, E-Graph-, Search- und Validation-Code sind nicht mehr nur logisch, sondern auch
  durch Gradle getrennt.
- Core-Logik bleibt isoliert und schneller testbar.
- Die obere SCC kann gezielt über Ports aufgelöst werden, ohne die grüne Build-
  Linie zu verlieren.
