# Architektur

Regelsuche läuft aktuell in einem Gradle-Setup mit einem produktiven
Code-Modul (`app`). Damit Discovery-, Persistenz- und Experiment-Features
trotzdem isoliert weiterentwickelt werden können, wird die Architektur über
**logische Module mit klaren Grenzen** geführt.

## Architektur-Leitplanken

- mathematischer Kern bleibt technologie-agnostisch,
- Infrastruktur bleibt austauschbar (JSON/Neo4j/Prover/HTTP),
- neue große Komponenten bekommen zuerst stabile Interfaces,
- Tests sind klar nach Laufzeit/Kosten geschichtet.

## Logische Module (innerhalb des aktuellen `app`-Moduls)

Die Zielstruktur und aktuelle Paket-Zuordnung steht in
[module-structure.md](module-structure.md).

## Abhängigkeitsregeln

Die verbindlichen Richtungen und verbotenen Kanten stehen in
[dependency-rules.md](dependency-rules.md).

## Test-Schichtung

Die Teststrategie inkl. schneller Core-Tests, Integrations- und Browser-E2E
steht in [testing-strategy.md](testing-strategy.md).

## ADRs

Architekturentscheidungen werden unter [`docs/adr/`](adr/) versioniert.
