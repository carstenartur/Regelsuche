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

Neue große Komponenten starten mit stabilen Ports/Interfaces. Die folgenden
Ports sind bereits als Stubs verankert und müssen von neuen Features als
Abhängigkeit genutzt werden (statt konkrete Backends direkt zu importieren):

| Port | Paket | Zweck |
| --- | --- | --- |
| `RuleIndex` | `de.regelsuche.inventory` | Schneller Lookup-Index über das Regelinventar (id/tag/pattern-key). |
| `SearchTraceStore` | `de.regelsuche.search` | Persistenz von Search-Traces für Replay und Reports. |
| `CounterexampleSearchService` | `de.regelsuche.validation` | Suche nach Gegenbeispielen zu Kandidaten-Äquivalenzen. |
| `PolynomialEquivalenceService` | `de.regelsuche.equivalence` | Spezialisierte Äquivalenzprüfung für Polynome. |
| `HypothesisRepository` | `de.regelsuche.mining` | Persistenz gemeinter Hypothesen vor Promotion zu reusable rules. |
| `DiscoveryExperimentRunner` | `de.regelsuche.benchmark` | Reproduzierbare Discovery-Experimente über ein Seed-Corpus. |

Bestehende Typen wie `RuleInventoryRepository`, `SearchGraphRepository`,
`EquivalenceService` und `ExpressionGraphStore` sind die vorhandenen
Anknüpfungspunkte und werden bei Bedarf auf diese Ports ausgerichtet.
