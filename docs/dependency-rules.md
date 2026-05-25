# Dependency Rules (Teil 0)

Diese Regeln definieren die erlaubten Richtungen zwischen den logischen
Modulen.

## Erlaubte Hauptrichtungen

```text
core <- search <- learning <- experiments
core <- egraph
validation hängt von core/search ab
web hängt von allem ab
cli verdrahtet Laufzeit-Komponenten
```

## Verbindliche Kernregel

Der mathematische Kern (`core`) bleibt frei von Infrastruktur-Details:

- keine Hibernate-/JPA-Annotationen,
- keine Spring-REST-Abhängigkeiten,
- keine Docker-/Containerlogik,
- keine Neo4j-spezifischen Klassen.

## Interface-first für große Erweiterungen

Neue große Komponenten starten mit stabilen Ports/Interfaces. Kandidaten:

- `RuleIndex`
- `SearchTraceStore`
- `CounterexampleSearchService`
- `PolynomialEquivalenceService`
- `HypothesisRepository`
- `DiscoveryExperimentRunner`

Bestehende Typen wie `RuleInventoryRepository`, `SearchGraphRepository`,
`EquivalenceService` und `ExpressionGraphStore` sind die vorhandenen
Anknüpfungspunkte und werden bei Bedarf auf diese Ports ausgerichtet.
