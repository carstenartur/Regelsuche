# Dependency Rules (Teil 0)

Diese Regeln definieren die erlaubten Richtungen zwischen den Gradle-Modulen und
den noch logischen Zielmodulen.

## Bereits physisch erzwungene Richtungen

```text
regelsuche-core
  <- regelsuche-egraph
  <- regelsuche-search
  <- regelsuche-validation
  <- regelsuche-persistence
  <- regelsuche-learning
  <- regelsuche-experiments
  <- regelsuche-discovery
  <- app
regelsuche-egraph <- regelsuche-search
regelsuche-search <- regelsuche-experiments
regelsuche-validation <- regelsuche-learning
regelsuche-search <- regelsuche-learning
regelsuche-validation <- regelsuche-experiments
regelsuche-search <- regelsuche-discovery
regelsuche-validation <- regelsuche-discovery
regelsuche-cli <- app
```

- `:regelsuche-core` darf keine Projektabhängigkeiten und keine technischen
  Infrastruktur-Libraries deklarieren.
- `:regelsuche-egraph` darf nur vom Core abhängen.
- `:regelsuche-search` darf vom Core und vom E-Graph-Modul abhängen; JSON-/Neo4j-TranspositionTable-Adapter bleiben außerhalb.
- `:regelsuche-validation` darf vom Core abhängen; konkrete Validierungsadapter
  dürfen hier zusätzliche technische Libraries kapseln.
- `:regelsuche-persistence` darf vom Core abhängen und enthält nur einfache Datei-/In-Memory-Ports sowie Konfiguration; Datenbanktreiber bleiben vorerst in `:app`.
- `:regelsuche-learning` darf von Core, Search und Validation abhängen; Discovery-/Graph-/Inventory-Orchestrierung bleibt außerhalb.
- `:regelsuche-experiments` darf von Search und Validation abhängen; Web-/CLI-/Persistenzadapter bleiben außerhalb.
- `:regelsuche-cli` bleibt projektabhängigkeitsfrei; app-spezifisches Routing und Serverstart-Wiring bleiben in `:app`.
- `:regelsuche-discovery` darf von Core, Search und Validation abhängen; Graph-/Export-/Web-Orchestrierung bleibt außerhalb.
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

## Persistence-Regel

`:regelsuche-persistence` enthält persistenznahe, aber leichtgewichtige Bausteine
(`PersistenceConfig`, `GraphPersistenceMode`, Search-Checkpoint-Port und
In-Memory-/JSON-Datei-Repository). App-spezifische Composition (`PersistenceContext`)
und Neo4j-/Graph-/Inventory-Adapter bleiben in `:app`, bis die oberen SCCs
entkoppelt sind.

## Learning-Regel

`:regelsuche-learning` enthält portable Mining-Bausteine wie `RuleCandidate`,
Pattern-/Anti-Unification-Typen, `HypothesisRepository`, `RuleCandidateMiner`
und `SuccessfulTransformationPath`. App-spezifische Discovery-Orchestrierung,
Graph-Speicherung und Inventory-Promotion bleiben in `:app`, solange die oberen
SCCs bestehen.

## Discovery-Regel

`:regelsuche-discovery` enthält portable Pfad- und Schritt-DTOs wie
`DiscoveredTransformation` und `TransformationStep`. Repositories,
Export-Adapter, Web-APIs und Discovery-Orchestrierung bleiben in `:app`, solange
die oberen SCCs bestehen.

## CLI-Regel

`:regelsuche-cli` enthält nur CLI-neutrale Bausteine wie Command-Registry und
Optionsparser. Subcommands, die Discovery, Webserver, Persistence oder Proof
Workbench starten, bleiben im app-spezifischen Router.

## Experiments-Regel

`:regelsuche-experiments` enthält den benchmarkfähigen Experiment-Kern
(`SearchBenchmark`, Result-DTOs, Report-Renderer, `DiscoveryExperimentRunner`).
App-spezifische Demo-Szenarien und Web-/CLI-Ausgabe bleiben in `:app`.

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
