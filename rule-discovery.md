# Regel-Entdeckung

`RuleCandidateMiner` arbeitet auf entdeckten Umformungsketten (`DiscoveredTransformation` + `TransformationStep`), normalisiert sie über den `ExpressionCanonicalizer`, anti-unifiziert AST-Strukturen und sammelt freie Parameterrelationen. Bekannte Identitäten landen ausschließlich als `RuleCandidate`-Statusbasis (vgl. `KnownRuleRepository`), niemals als direkte Transformationsschritte.

## Lebenszyklus (`CandidateProofStatus`)

```
REJECTED < OBSERVED < VALIDATED_BY_EXAMPLES < SYMBOLICALLY_VERIFIED < FORMALLY_PROVABLE < FORMALLY_PROVED
```

- `OBSERVED` – nur empirisch beobachtet.
- `VALIDATED_BY_EXAMPLES` – auf frischen Beispielen validiert (`CandidateValidator`).
- `SYMBOLICALLY_VERIFIED` – Äquivalenz über `SymPyEquivalenceService` bestätigt.
- `FORMALLY_PROVABLE` – Kandidatengleichung erfüllt nötige Voraussetzungen für einen formellen Beweis.
- `FORMALLY_PROVED` – formell verifiziert (Platzhalter für zukünftige Integration).
- `REJECTED` – Kandidat wurde explizit ausgeschlossen (z. B. Gegenbeispiel gefunden).

Validierung passiert AST-basiert über `RulePatternParser` + `RulePatternInstantiator`, ohne String-Substitution.

## Hypothesis-Lifecycle (Discovery Epic Teil 2)

Die vollständige Discovery-Pipeline folgt dem Muster:

```
Path-Mining (RuleCandidateMiner / PatternGeneralizer)
  → HypothesisCandidate (regelsuche-learning)
  → Counterexample Search (CounterexampleSearchService)
  → optional Proof (EquivalenceService / CandidateValidator)
  → Promotion → ReusableRule (MacroRuleLearningService)
```

### `HypothesisCandidate` (neu)

Record in `de.regelsuche.mining` mit:
- `id` – stabiler Canonical-Hash
- `leftPattern` / `rightPattern` – generalisierte Muster (nach Anti-Unifikation)
- `supportingPaths` – Ids der stützenden Pfade
- `supportingExpressions` – konkrete Zeugen-Paare
- `assumptions` – Voraussetzungen des Musters
- `noveltyScore` – 0.0–1.0 (1.0 = vollständig neu)
- `proofStatus` – aktueller Validierungsstatus
- `counterexampleStatus` – `null` wenn kein Gegenbeispiel-Check, `true` wenn gefunden
- `expressionPlaceholders` – Ausdruck-Level-Platzhalter aus der Anti-Unifikation
- `parameterRelations` – algebraische Relationen zwischen Platzhaltern

### `InMemoryHypothesisRepository` (neu)

In-Memory-Implementierung des `HypothesisRepository`-Ports für Tests und lokale Läufe.

### `HypothesisPromotionPipeline` (neu, in app)

Orchestriert den vollständigen Pipeline-Durchlauf:
1. Mining via `RuleCandidateMiner`
2. Novelty-Score-Berechnung
3. Gegenbeispiel-Suche via `CounterexampleSearchService`
4. Speicherung als `HypothesisCandidate`
5. Auto-Promotion via `MacroRuleLearningService` (wenn aktiviert)

### Annahmen als mathematische Identität (Issue #35)

Annahmen sind nicht nur Kommentartext an einer Hypothese. Sie gehören zur
Identität einer mathematischen Aussage:

- `TransformationStep` und `SuccessfulTransformationPath` tragen normalisierte
  Annahmen, damit Pfade wie `(a*b)/b → a` die Voraussetzung `b != 0`
  weiterreichen.
- `HypothesisRepository` speichert `HypothesisCandidate`, sodass inferred
  assumptions sowie Proof-/Counterexample-Status erhalten bleiben.
- `ReusableRule` und `MacroRuleCandidate` enthalten normalisierte Annahmen.
  Macro-Moves dürfen nur ausgewählt werden, wenn ihre Annahmen bereits im
  Suchkontext erfüllt sind oder explizit weitergetragen werden.
- E-Graph-Merges und Transposition-Table-Identitäten berücksichtigen den
  `AssumptionSignature`-Fingerprint. Dieselbe Expression unter verschiedenen
  Annahmen darf nicht als derselbe Zustand behandelt werden.

`AssumptionSignature` normalisiert derzeit einfache textuelle Varianten wie
`b≠0`, `b != 0`, `0 != b`, doppelte Leerzeichen und Klammerformen wie
`(b) != 0` auf denselben Fingerprint.

### Counterexample Search ist kein Beweis

Die deterministische Gegenbeispielsuche ist endlich budgetiert. Ein leerer
Fund bedeutet daher nur: Unter den aktivierten Quellen wurde kein
Gegenbeispiel gefunden. Das ist kein mathematischer Beweis.

Aktuell unterstützte Quellen:

- numerische Edge-Cases und deterministische Random-Samples,
- optionale nicht-kommutative Matrix-Samples für uppercase Variablen,
- optionale einfache komplexe Samples für `+`, `-`, `*`, `/`, sichere
  Integer-Potenzen und `sqrt`/`abs`,
- optionaler externer Solver-Port (`ExternalSolverCounterexampleBackend`),
  standardmäßig deaktiviert/no-op, bis Z3/cvc5 lokal verfügbar ist.

Nicht vollständig unterstützt sind u. a. allgemeine Quantoren, vollständige
Inequality-Semantik über komplexen Zahlen, vollständige Transzendenten im
Komplexen und formale Beweise. SMT/Z3/cvc5-Setup und tiefere komplexe
Domänenmodellierung bleiben Follow-up-Scope.

### Aktuelle No-DB-Grenze

Issue #34 ist bewusst ohne Datenbank-Persistenz umgesetzt. `HypothesisCandidate`
und die Demo-Regeln laufen über `InMemoryHypothesisRepository` bzw.
`InMemoryRuleInventoryRepository`. Persistente PostgreSQL-/Neo4j-Ablage bleibt
außerhalb dieses Scopes.

### Evidenz

`HypothesisCandidate.supportingExpressions` wird jetzt aus den geminten
`SuccessfulTransformationPath`s befüllt. Jede Hypothese enthält damit neben den
`supportingPaths` auch konkrete Vorher/Nachher-Zeugen, z. B. alle drei
binomischen Beispiele `(x+1)^2`, `(x+2)^2`, `(x+3)^2`.

## Anti-Unifikation (Discovery Epic Teil 2)

`PatternGeneralizer` unterstützt jetzt robuste Anti-Unifikation über:

- **Zahlen** (bestehend): unterschiedliche Integer-Werte → Platzhalter `N1`, `N2`, … mit Parameterrelationen.
- **Strukturell verschiedene Teilbäume** (neu): wenn Knoten an derselben Position unterschiedliche Shapes haben (z. B. `x` vs. `x+1` als Basis einer Potenz), werden sie zu einem Ausdruck-Platzhalter (`B`, `C`, …) abstrahiert.
- **Verschiedene Variable-Namen** (neu): wenn Variablen an strukturell gleicher Position verschiedene Namen haben, entstehen Ausdruck-Platzhalter.

Ausdruck-Platzhalter werden in `GeneralizedPattern.expressionPlaceholderValues()` gespeichert und in den `parameterRelations` als Mengenzugehörigkeit dokumentiert (z. B. `B ∈ {x, x + 1, x + 2}`).

## Demos ohne DB

`DiscoveryDemos` enthält reproduzierbare In-Memory-Szenarien:

- **Rationalvereinfachung**: Beispiele wie `(x*x)/x → x`, `(a*b)/a → b`,
  `(x+1)/(x+1) → 1` sowie zusätzliche gleichförmige Stützbeispiele. Daraus wird
  eine wiederverwendbare Makroregel zur Kürzung gemeinsamer Faktoren in das
  Inventar promoted. Wenn der derzeitige Miner wegen Nebenbedingungen
  (`A != 0`) keine validierte Regel emittiert, nutzt die Demo einen explizit
  annotierten Fallback mit derselben Evidenzbasis.
- **Geometrische Reihe**: `1+x`, `1+x+x^2`, `1+x+x^2+x^3` erzeugen aktuell eine
  strukturelle `HypothesisCandidate` mit der Relation
  `S_(n+1) = S_n + x^n`. Die geschlossene Form `(1-x^n)/(1-x)` wird noch nicht
  automatisch hergeleitet oder bewiesen.

## Reproduzierbare wissenschaftliche Experimente

Der portable Experiment-Kern liegt in `:regelsuche-experiments`:

- `SeedExpression` beschreibt wissenschaftliche Seeds mit Quelle, Kategorie,
  Tags und Annahmen.
- `ScientificSeedCorpora.curated()` bündelt kuratierte Seeds für bekannte
  Identitäten, DLMF-/OEIS-Proben, Matrix-/Operatorfälle und
  Gegenbeispiel-Fallen. `fromCatalogs(...)` lädt zusätzlich lokale YAML-/JSON-
  Kataloge.
- `DeterministicDiscoveryExperimentRunner` sortiert Seeds deterministisch,
  erzwingt ein globales Budget und kann Seeds parallel auswerten, ohne die
  Report-Reihenfolge zu verändern.
- `ScientificDiscoveryWorkflow` (app) bootet die produktive
  `PersistenceContext`-Komposition im In-Memory-, JSON- oder
  PostgreSQL-Hybrid-Modus und führt den kompletten Pfad
  **Seed → Discovery/Search/Validation → Replay → Persistenz → Report** aus.
- `DiscoveryReplayArtifactWriter` schreibt CI-taugliche Replay-Artefakte:
  `discovery-report.json`, `discovery-report.html`, `discovery-report.md`,
  `discovery-replay.json`, `discovery-summary.png` und ein mehrstufiges
  `discovery-replay.gif`.

Lokale Befehle:

```bash
./gradlew :app:test --tests de.regelsuche.discovery.ScientificDiscoveryReproductionTest
./gradlew :app:dockerE2eTest --tests de.regelsuche.dockere2e.ScientificDiscoveryPostgresE2ETest
```

Die wissenschaftlichen Reproduktions-Tests gehen über Runner-Mechanik hinaus:
`ScientificDiscoveryReproductionTest` speist `SeedExpression` direkt in die
App-Wiring-Schicht ein und deckt Binom, geometrische Reihe, Faktorisierung,
Trigonometrie, Matrixidentität und rationale Vereinfachung ab. Zusätzlich
prüft der Test Byte-Stabilität der deterministischen JSON-Reports,
`parallelism=1` vs. `parallelism=4`, globale Budgets und konsistente
Abbruch-Reports mit Budget `0`.

Der Testcontainers-basierte `ScientificDiscoveryPostgresE2ETest` startet
PostgreSQL, bootet den PostgreSQL+JSON-Hybrid-Modus über `PersistenceContext`,
persistiert Seeds, Experiment, Search-Runs, Hypothesen, Gegenbeispiele,
Proof-Worker-Metadaten sowie JSON/HTML/PNG/GIF-Artefakt-Metadaten und lädt sie
anschließend wieder aus PostgreSQL. Das Projekt nutzt keine Spring-Boot-Runtime;
die Container-Integration erfolgt daher über JUnit/Testcontainers plus die
Produktions-Persistenzadapter statt über `@SpringBootTest`.

Grenze: `discovery-summary.png` und `discovery-replay.gif` werden aus dem
Report-Renderer synthetisch gerendert. Echte Browser-/UI-Screenshots bleiben im
separaten Playwright-`e2eTest`-Pfad.
