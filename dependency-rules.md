# Dependency Rules (Teil 0)

Diese Regeln definieren die erlaubten Richtungen zwischen den Gradle-Modulen und den noch logischen Zielmodulen.

## Physisch erzwungene Hauptrichtung

```text
regelsuche-core
  <- regelsuche-egraph
  <- regelsuche-search
  <- regelsuche-validation
  <- regelsuche-solver-ir
  <- regelsuche-solver-portfolio
  <- regelsuche-benchmarks

regelsuche-search <- regelsuche-learning <- regelsuche-experiments
regelsuche-discovery <- regelsuche-experiments
regelsuche-experiments + regelsuche-learning <- regelsuche-autopilot
regelsuche-persistence <- regelsuche-persistence-hibernate
regelsuche-cli <- app
```

- `:regelsuche-core` darf keine Projektabhängigkeiten und keine technischen Infrastruktur-Libraries deklarieren.
- `:regelsuche-egraph` darf nur vom Core abhängen.
- `:regelsuche-search` darf vom Core und vom E-Graph-Modul abhängen; JSON-/Neo4j-Adapter bleiben außerhalb.
- `:regelsuche-validation` darf vom Core abhängen; konkrete Validierungsadapter dürfen hier zusätzliche technische Libraries kapseln.
- `:regelsuche-solver-ir` darf mathematische Kern-, Search-, Validation- und reine Algorithmusverträge verwenden. Es enthält keine Portfolio- oder Campaign-Entscheidungen.
- `:regelsuche-solver-portfolio` darf nur auf der Solver-IR aufbauen. Sein Planner-Report ist Ausführungstelemetrie, kein Ersatz für Solver-Resultate.
- `:regelsuche-benchmarks` darf Search, Solver-IR und Solver-Portfolio verbinden, weil Vergleichsläufe konkrete Strategien und externe Backends ausführen. Es darf keine zweite mathematische Problem-IR und keine Rückabhängigkeit in die Fachmodule einführen.
- `:regelsuche-persistence` enthält Ports/Konfiguration ohne Hibernate/JPA/Datenbanktreiber.
- `:regelsuche-persistence-hibernate` kapselt Hibernate/JPA/PostgreSQL und hängt von den benötigten Fachports ab.
- `:regelsuche-learning` darf von Core, Search, Validation und der solver-neutralen IR abhängen; App-/Graph-/Inventory-Orchestrierung bleibt außerhalb.
- `:regelsuche-experiments` darf von Search, Validation, mathematischen Algorithmen und Discovery-Metadaten abhängen; Web-/CLI-/Persistenzadapter bleiben außerhalb.
- `:regelsuche-autopilot` ist die Composition-Schicht über Experiment- und Learning-Verträgen.
- `:regelsuche-cli` bleibt projektabhängigkeitsfrei; app-spezifisches Routing bleibt in `:app`.
- `:regelsuche-discovery` darf von Core, Search und Validation abhängen.
- `:app` ist die Composition Root und darf die Module verdrahten.

## Solver-Regel

Die Solver-IR besitzt die Bedeutung einer Obligation, strukturierten Annahmen, Übersetzung, eines Ergebnisses und einer konkreten Execution. Kein Portfolio- oder Benchmark-Report darf einen `UNKNOWN`-, Search- oder Planner-Zustand in mathematische Bestätigung umetikettieren.

```text
solver-obligation
  -> solver-translation
  -> solver-result
  -> solver-execution
```

`regelsuche-solver-portfolio` wählt, budgetiert und aggregiert Backends. Der ausgewählte mathematische Nachweis bleibt stets das konkrete `solver-execution`-Artefakt.

## Benchmark-Regel

Vergleichende Benchmarks sind nach Capability und Informationsregime getrennt. Ein Benchmark darf:

- mehrere Search-Strategien unter identischen Inputs, Targets, Inventaren und Budgets ausführen;
- externe CAS-/SMT-/Prover-Adapter auf einem explizit gemeinsamen Fragment vergleichen;
- Laufzeit als nichtkanonische Telemetrie erfassen;
- ungemessene Tracks als Coverage Gaps ausweisen.

Ein Benchmark darf nicht:

- target-directed Search mit targetfreier Discovery in einem Score vermischen;
- Validierung als Discovery-Erfolg zählen;
- Search-Erfolg als Proof zählen;
- fehlende, übersprungene, nicht unterstützte oder inconclusive Baselines aus dem Report entfernen;
- Qualification-, TEST-, Review- oder Hidden-Reference-Informationen entgegen dem Parity Manifest verwenden;
- interne Legacy-Verträge parallel zum autoritativen Reportformat pflegen.

Die feste Score-Policy lautet:

```text
NO_UNIVERSAL_SCORE_TRACK_SCOPED_CLAIMS_ONLY
```

## Verbindliche Kernregel

Der mathematische Kern bleibt frei von Infrastruktur-Details:

- keine Hibernate-/JPA-Annotationen;
- keine Spring-REST-Abhängigkeiten;
- keine Docker-/Containerlogik;
- keine Neo4j-spezifischen Klassen;
- keine GraalVM/SymPy-/Z3-Prozessadapter.

Externe SymPy- und Z3-Aufrufe liegen deshalb ausschließlich in oberen Adaptermodulen.

## Persistence-, Learning- und Discovery-Regeln

`:regelsuche-persistence` enthält persistenznahe, aber leichtgewichtige Ports. Datenbankgebundene Implementierungen liegen in `:regelsuche-persistence-hibernate` oder app-spezifischen Adaptern.

`:regelsuche-learning` enthält portable Mining-Bausteine wie `RuleCandidate`, Pattern-/Anti-Unification-Typen und `HypothesisRepository`. Promotion, Graph-Speicherung und Runtime-Orchestrierung bleiben außerhalb.

`:regelsuche-discovery` enthält portable Pfad- und Schritt-DTOs, Discovery-Profile und Operator-Deskriptoren. Repositories, Web-APIs und app-spezifische Orchestrierung bleiben in `:app`.

## Interface-first für große Erweiterungen

Neue große Komponenten starten mit stabilen Ports. Vorhandene Anknüpfungspunkte sind unter anderem `RuleIndex`, `SearchTraceStore`, `TermRuleIndex`, `CounterexampleSearchService`, `PolynomialEquivalenceService`, `CompletionService`, `CriticalPairService`, `NumericRelationService`, `MathematicalAlgorithmRegistry`, `HypothesisRepository` und `DiscoveryExperimentRunner`.
