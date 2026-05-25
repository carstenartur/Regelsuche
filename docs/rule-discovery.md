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
