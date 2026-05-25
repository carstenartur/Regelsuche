# ADR 0001: Logical module boundaries before physical split

- Status: Accepted
- Date: 2026-05-25

## Context

Discovery-, Persistenz- und Experiment-Funktionen erweitern Regelsuche stark.
Bei direkter Weiterentwicklung ohne zusätzliche Abstraktionsebenen steigt die
kognitive Last und es drohen Architekturdrift, God-Classes und langsame Tests.

## Decision

Wir führen verbindliche **logische Modulgrenzen** ein, bevor die physische
Aufteilung in mehrere Gradle-Module erfolgt.

Die Regeln sind in
- [module-structure.md](../module-structure.md),
- [dependency-rules.md](../dependency-rules.md),
- [testing-strategy.md](../testing-strategy.md)

festgehalten.

Zusätzlich wird per automatisiertem Test abgesichert, dass der mathematische
Kern keine Infrastruktur-Abhängigkeiten (Neo4j, JPA/Hibernate, Spring, Docker,
Testcontainers) importiert.

## Consequences

- Features können innerhalb klarer Grenzen entwickelt werden.
- Core-Logik bleibt isoliert und schneller testbar.
- Die spätere physische Modultrennung wird ein kontrollierter, inkrementeller
  Schritt statt eines Big-Bang-Refactorings.
