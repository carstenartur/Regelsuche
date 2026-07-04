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
  <- regelsuche-persistence-hibernate
  <- regelsuche-learning
  <- regelsuche-experiments
  <- regelsuche-discovery
  <- app
regelsuche-egraph <- regelsuche-search
regelsuche-search <- regelsuche-experiments
regelsuche-discovery <- regelsuche-experiments
regelsuche-validation <- regelsuche-learning
regelsuche-search <- regelsuche-learning
regelsuche-validation <- regelsuche-experiments
regelsuche-search <- regelsuche-discovery
regelsuche-validation <- regelsuche-discovery
regelsuche-persistence <- regelsuche-persistence-hibernate
regelsuche-learning <- regelsuche-persistence-hibernate
regelsuche-validation <- regelsuche-persistence-hibernate
regelsuche-cli <- app
```

- `:regelsuche-core` darf keine Projektabhängigkeiten und keine technischen
  Infrastruktur-Libraries deklarieren.
- `:regelsuche-egraph` darf nur vom Core abhängen.
- `:regelsuche-search` darf vom Core und vom E-Graph-Modul abhängen; JSON-/Neo4j-TranspositionTable-Adapter bleiben außerhalb.
- `:regelsuche-validation` darf vom Core abhängen; konkrete Validierungsadapter
  dürfen hier zusätzliche technische Libraries kapseln.
- `:regelsuche-persistence` darf vom Core abhängen und enthält Ports/Konfiguration ohne Hibernate/JPA/Datenbanktreiber.
- `:regelsuche-persistence-hibernate` darf Hibernate/JPA/PostgreSQL und Hibernate Search kapseln und hängt von Persistence-Ports, Learning-Hypothesen und Validation-Status ab.
- `:regelsuche-learning` darf von Core, Search und Validation abhängen; Discovery-/Graph-/Inventory-Orchestrierung bleibt außerhalb.
- `:regelsuche-experiments` darf von Search, Validation und Discovery-Metadaten abhängen; Web-/CLI-/Persistenzadapter bleiben außerhalb.
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

`SymPyTransformationEngine` bleibt deshalb als App-Adapter unter
`de.regelsuche.app.transform`, während die reinen `TransformationEngine`-/Rewrite-Typen
in `:regelsuche-core` liegen.

## Search-Regel

`:regelsuche-search` enthält Strategien, Profile, CostModels und die
`TranspositionTable`-Abstraktion. Technische Speicheradapter (`JsonFileTranspositionTable`,
`Neo4jTranspositionTable`) bleiben in `:app` bzw. später in `:regelsuche-persistence`;
der Neo4j-Adapter liegt aktuell unter `de.regelsuche.app.persistence.neo4j`.

## Persistence-Regel

`:regelsuche-persistence` enthält persistenznahe, aber leichtgewichtige Bausteine
(`PersistenceConfig`, `GraphPersistenceMode`, Search-Checkpoint-Port). Hibernate,
JPA-Entities, PostgreSQL-Migrationen, Repositories und Hibernate Search liegen in
`:regelsuche-persistence-hibernate`. App-spezifische Composition (`PersistenceContext`)
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
`DiscoveredTransformation` und `TransformationStep`, Discovery-Profile/-Optionen
sowie Operator-Deskriptoren. Repositories, Web-APIs und app-spezifische
Discovery-Orchestrierung bleiben in `:app`, solange die oberen SCCs bestehen.

## CLI-Regel

`:regelsuche-cli` enthält nur CLI-neutrale Bausteine wie Command-Registry und
Optionsparser. Subcommands, die Discovery, Webserver, Persistence oder Proof
Workbench starten, bleiben im app-spezifischen Router.

## Experiments-Regel

`:regelsuche-experiments` enthält den benchmarkfähigen Experiment-Kern
(`SearchBenchmark`, Result-DTOs, Report-Renderer, `DiscoveryExperimentRunner`,
`DeterministicDiscoveryExperimentRunner`) sowie den portablen Seed-Corpus
(`AlgebraicExampleGenerator`, `SeedExpression`, `ScientificSeedCorpora` inkl.
lokaler YAML/JSON-Kataloge). Report- und Gallery-Metadaten dürfen die portablen
Discovery-Deskriptoren verwenden; app-spezifische Demo-Szenarien und Web-/CLI-Ausgabe
bleiben in `:app`.

## Interface-first für große Erweiterungen

Neue große Komponenten starten mit stabilen Ports/Interfaces. Die folgenden
Ports sind bereits als Stubs verankert und müssen von neuen Features als
Abhängigkeit genutzt werden (statt konkrete Backends direkt zu importieren):

| Port | Paket | Zweck |
| --- | --- | --- |
| `RuleIndex` | `de.regelsuche.inventory` | Schneller Lookup-Index über das Regelinventar (id/tag/pattern-key). |
| `SearchTraceStore` | `de.regelsuche.search` | Persistenz von Search-Traces für Replay und Reports. |
| `TermRuleIndex` | `de.regelsuche.search.index` | Suchkern-Port für atomare Regeln und gelernte Makrozüge mit indexseitigem Filtering. |
| `CounterexampleSearchService` | `de.regelsuche.validation` | Suche nach Gegenbeispielen zu Kandidaten-Äquivalenzen. |
| `PolynomialEquivalenceService` | `de.regelsuche.equivalence` | Spezialisierte Äquivalenzprüfung für Polynome. |
| `CompletionService` | `de.regelsuche.validation` | Knuth-Bendix-Completion-/Konfluenzberichte als Port. |
| `CriticalPairService` | `de.regelsuche.validation` | Kritische-Paar-Analyse als separater Port. |
| `NumericRelationService` | `de.regelsuche.validation` | PSLQ-/numerische Relationshypothesen als Port (kein Beweis). |
| `MathematicalAlgorithmRegistry` | `de.regelsuche.validation` | Konfigurations-/Budget-Registry für einzeln aktivierbare Mathe-Algorithmen. |
| `HypothesisRepository` | `de.regelsuche.mining` | Persistenz gemeinter Hypothesen vor Promotion zu reusable rules. |
| `DiscoveryExperimentRunner` | `de.regelsuche.benchmark` | Reproduzierbare Discovery-Experimente über ein Seed-Corpus. |

Bestehende Typen wie `RuleInventoryRepository`, `SearchGraphRepository`,
`EquivalenceService` und `ExpressionGraphStore` sind die vorhandenen
Anknüpfungspunkte und werden bei Bedarf auf diese Ports ausgerichtet.
