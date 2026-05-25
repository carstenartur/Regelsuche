# Architektur

Regelsuche ist jetzt ein Gradle-Multi-Projekt. Die ersten physischen Grenzen
sind dort gezogen, wo der aktuelle Code bereits azyklisch und stabil genug ist:

- `regelsuche-core` enthält den mathematischen Kern ohne Neo4j/GraalVM/Web/Testcontainer-Abhängigkeiten.
- `regelsuche-egraph` hängt nur vom Core ab.
- `regelsuche-validation` hängt vom Core ab und kapselt die aktuellen Validierungs-/Äquivalenzadapter.
- `app` bleibt die Laufzeit-Hülle für CLI, Web, Persistence, Learning, Search und die noch zyklisch gekoppelten oberen Schichten.

Damit ist Issue #41 nicht mehr nur dokumentiert: Gradle erzwingt die wichtigsten
Grenzen bereits beim Kompilieren.

## Architektur-Leitplanken

- mathematischer Kern bleibt technologie-agnostisch,
- E-Graph und Validierung benutzen Core-Typen über explizite Projektabhängigkeiten,
- Infrastruktur bleibt in `app` bzw. in Adapter-Modulen,
- neue große Komponenten bekommen zuerst stabile Interfaces,
- Tests sind nach Modul und Laufzeit/Kosten geschichtet.

## Modulstruktur

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
