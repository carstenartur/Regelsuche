# Dependency Rules (Teil 0)

Diese Regeln definieren die erlaubten Richtungen zwischen den Gradle-Modulen und
den noch logischen Zielmodulen.

## Bereits physisch erzwungene Richtungen

```text
regelsuche-core
  <- regelsuche-egraph
  <- regelsuche-search
  <- regelsuche-validation
  <- app
regelsuche-egraph <- regelsuche-search
```

- `:regelsuche-core` darf keine Projektabhängigkeiten und keine technischen
  Infrastruktur-Libraries deklarieren.
- `:regelsuche-egraph` darf nur vom Core abhängen.
- `:regelsuche-search` darf vom Core und vom E-Graph-Modul abhängen; JSON-/Neo4j-TranspositionTable-Adapter bleiben außerhalb.
- `:regelsuche-validation` darf vom Core abhängen; konkrete Validierungsadapter
  dürfen hier zusätzliche technische Libraries kapseln.
- `:app` ist die Composition Root und darf die Module verdrahten.

## Zielrichtung für die noch nicht extrahierten Schichten

```text
core <- search <- learning <- experiments
core <- egraph
validation hängt von core/search ab
web hängt von allem ab
cli verdrahtet Laufzeit-Komponenten
```

## Verbindliche Kernregel

Der mathematische Kern (`:regelsuche-core`) bleibt frei von Infrastruktur-Details:

- keine Hibernate-/JPA-Annotationen,
- keine Spring-REST-Abhängigkeiten,
- keine Docker-/Containerlogik,
- keine Neo4j-spezifischen Klassen,
- keine GraalVM/SymPy-Adapter.

`SymPyTransformationEngine` bleibt deshalb als Adapter in `:app`, während die
reinen `TransformationEngine`-/Rewrite-Typen in `:regelsuche-core` liegen.

## Search-Regel

`:regelsuche-search` enthält Strategien, Profile, CostModels und die
`TranspositionTable`-Abstraktion. Technische Speicheradapter (`JsonFileTranspositionTable`,
`Neo4jTranspositionTable`) bleiben in `:app` bzw. später in `:regelsuche-persistence`.

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
