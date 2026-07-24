# Discovery Engine

Die produktive Discovery-Pipeline in Regelsuche folgt dem Pfad:

`Seed → Discovery/Search/Validation → Replay → Persistenz → Report`

Die technische Pipeline allein definiert noch keine mathematische Entdeckung.
[Von Umformungen zu mathematischen Entdeckungen](from-transformations-to-discovery.md)
beschreibt die geschlossene Zielkette von besserer Pfadsuche über Mining,
Falsifikation und Beweis bis zur versionierten Promotion und gepaarten
Wiederholungsmessung. Dort ist auch dokumentiert, welchen Beitrag die aktuellen
Search-/Learning-Schritte leisten und welche Discovery-Behauptungen daraus noch
nicht folgen.

Der [aktuelle Discovery-Stand](discovery-status.md) fasst die gemessenen
Ergebnisse, die bereits gemergten Open-Target-Stufen und die verbleibenden
wissenschaftlichen Grenzen zusammen.

Zentrale Bausteine:

- **Layer 1 – TransformationEngine:** lokale Rewrites, Hypothesenoperatoren und
  Wiederverwendung bereits gelernter Makrozüge.
- **Layer 2 – Search:** Suchstrategien explorieren die von der Engine gelieferten
  Transformationen unter Tiefe/Budget.
- **Layer 3 – Open-Target-Mining:** untargetete Suchgraphen liefern konvergente,
  alpha-distinkte Zeugen; daraus entstehen parametrisierte Hypothesen ohne
  Zielausdruck oder versteckte erwartete Antwort.
- **Layer 4 – Validierung und Lifecycle:** kompilierte Kandidaten durchlaufen
  frische Holdouts, Gegenbeispielsuche, projektinterne Novelty, symbolische
  Proof-Obligations und anschließend den bestehenden Hypothesen-Lebenszyklus.
- **Layer 5 – Discovery-Orchestrierung:** Workflows klassifizieren Ergebnisse,
  validieren Äquivalenz und lernen/promoten Makros nur, wenn
  `DiscoveryLearningOptions` dies explizit erlaubt.
- **Layer 6 – Reporting/Gallery:** Reports rendern ausschließlich
  `DiscoveryTrace`-/Replay-Evidence und Descriptor-Metadaten, keine hart
  codierten Seed-Sonderfälle. Public Evidence bleibt ein eigener Gate-Schritt.
- `ScientificDiscoveryWorkflow` bootet die produktive App-Wiring-Schicht und führt reproduzierbare Discovery-Läufe aus.
- `DeterministicDiscoveryExperimentRunner` wertet Seeds deterministisch, budgetiert und optional parallel aus.
- `DiscoveryReplayArtifactWriter` erzeugt `discovery-report.{json,html,md}`, `discovery-replay.json`, `discovery-summary.png` und `discovery-replay.gif`.
- `PathReplayDto` und die Web-Workbench liefern das interaktive Schritt-für-Schritt-Replay inklusive Makrozügen, Domänenkarten und Proof-Status.
- `DomainAwareCasRouter` ist der produktive Einstieg für Polynomidentitäten, kleine Gröbner-Idealprüfungen und numerische Relationssuche; numerische Treffer werden in Reports/Provenance als Hypothesen mit Koeffizienten und Residual geführt.
- Symbolic-Regression-Proposals laufen über Backend-Ports und bleiben im Discovery-Workflow Evidence-only: Sample-Fit → Hypothese → Counterexample-Suche → optionaler unterstützter Proof-Versuch.
- Counterexample Search wird als Angriff auf Hypothesen durchgereicht. Der Status ist tri-state:
  `COUNTEREXAMPLE_FOUND` widerlegt die Hypothese, `NO_COUNTEREXAMPLE_FOUND`
  bedeutet nur "innerhalb Budget/Quellen nicht widerlegt", und `INCONCLUSIVE`
  ist keine belastbare Aussage.
- Reports, Replay-JSON, Hypothesen-/Counterexample-Exports und
  `provenance.graph.json` enthalten `counterexampleStatus`, attempted
  sources, inferred assumptions und explanation. `NO_COUNTEREXAMPLE_FOUND`
  wird bewusst nicht als Beweis dargestellt.
- Der Provenance-Graph unterscheidet Hypothesen, Counterexample-Search-Attempts,
  Gegenbeispiele, Regression-Proposals, numerische Relationskandidaten und
  CAS-Validierungsversuche, damit Reports und spätere UI-Queries Quelle und
  Beweissemantik getrennt darstellen können.
- `DiscoveryOptions` liegt in `de.regelsuche.discovery` und aggregiert
  `DiscoveryEngineOptions` (Hypothesenoperatoren, Makro-Reuse, Tiefe/Budget) und
  `DiscoveryLearningOptions` (Makro-Lernen, Validierung generierter Instanzen,
  Promotion). `DiscoveryEngineFactory` konsumiert nur Engine-Optionen und führt
  kein Lernen aus.
- `de.regelsuche.search.index.RuleCandidateIndex` erweitert den bisherigen
  `TermRuleIndex`-Pfad um `CandidateSet`, `SearchContext`, `CandidateBudget`
  und detaillierte `IndexMetrics`. `RootSymbolTermRuleIndex` bleibt die
  rückwärtskompatible Implementierung, führt aber optional eine mehrstufige
  Verengung aus Root-Symbol, Operator-Signatur, Feature-Vektor,
  Discrimination-Tree-Key und Goal-Ranking aus.
- `RuleIndexBenchmark` vergleicht naive Scans, Root-Symbol-Index und
  Multi-Stage-Index über wachsende Makroregel-Inventare, makrolastige
  Discovery-Szenarien und wachsende E-Graph-Matcher-Indizes. Exportierte
  Metriken sind u. a. `rulesSkippedByIndex`, `nodesScanned`,
  `matcherCacheHits` und durchschnittliche Kandidatenmengen.
- Die `HypothesisOperatorRegistry` enthält neben den polynomialen
  Bridge-Operatoren auch `telescoping-fraction` und `rationalization`. Beide
  bleiben konservativ: Telescoping deckt die Unit-Step-Form
  `1 / (u * (u + 1)) → 1/u - 1/(u + 1)` ab, Rationalization deckt
  `1 / (sqrt(u) ± c)` ab und trägt die benötigte Annahme wie `u != c^2` im
  Transformationsschlüssel.
- `DiscoveryBenchmarkDashboard` aggregiert Operator-Korpora ausschließlich aus
  erzeugten Corpus-/Replay-Zeilen: Cases, Kandidaten, Bridge-/Transform-Status,
  Makro-Lernen/-Reuse, False Positives und Durchschnittszeit werden nicht
  statisch erfunden.

Wichtige Einstiegspunkte:

- [docs/discovery-status.md](discovery-status.md)
- [docs/from-transformations-to-discovery.md](from-transformations-to-discovery.md)
- [docs/rule-discovery.md](rule-discovery.md)
- [docs/experiment-runner.md](experiment-runner.md)
- [docs/replay-and-reports.md](replay-and-reports.md)
- [docs/scientific-reproducibility.md](scientific-reproducibility.md)

Reproduktionskommandos:

```bash
./gradlew :app:test --tests de.regelsuche.discovery.ScientificDiscoveryReproductionTest
./gradlew :app:dockerE2eTest --tests de.regelsuche.dockere2e.ScientificDiscoveryPostgresE2ETest
```
