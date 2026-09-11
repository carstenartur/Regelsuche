# Sicherer Regelvorbereitungskoordinator

**Implementierungsstand: 11. September 2026**

Die Vorbereitungsschicht ersetzt die binäre Grenze
`matches -> apply or discard` durch einen begrenzten, nachprüfbaren Ablauf. Sie
soll eine vorhandene Regel anwendbar machen, ohne ein gewünschtes Endergebnis,
eine Benchmarkantwort oder eine nachträgliche Bewertung als Suchziel zu
verwenden.

## Zwei versionierte Coordinator-Verträge

Der historische `UnifiedRulePreparationCoordinator` behält die Identität
`regelsuche.unified-safe-rule-preparation-coordinator/v1`. Er führt den bounded
lokalen Fallback für jedes Principal-Schema separat aus. Diese Semantik bleibt
unverändert, damit vorhandene Evidence reproduzierbar bleibt.

`SharedUnifiedRulePreparationCoordinator` trägt den Vertrag
`regelsuche.unified-safe-rule-preparation-coordinator/v2`. v2 teilt physische
Arbeit, die unabhängig von der Principal-Identität ist, und behält für jeden
Principal Outcome, Guards, konkreten Replay und Zertifikat getrennt.

```text
konkreter Executor: direkter Replay-Versuch
  -> typisierte Guard-Prüfung für einen direkten Treffer
  -> theory-aware Source-Pattern-Analyse
  -> registrierter nativer Exact-Spezialist
  -> gemeinsame bounded Preparation-Frontier für ungelöste Principals
  -> terminale Guards pro Principal
  -> konkreter Principal-Replay pro Principal
  -> content-addressed Zertifikat pro Principal
  -> retained Outcome oder fail-closed Status
```

Das Schema lenkt Vorbereitung und Guard-Bindung; es ersetzt niemals den
konkreten Executor.

## Product-Qualification

PR #967 vergleicht `DIRECT_V1` und `SAFE_PREPARATION_V2` mit demselben sichtbaren
Regelinventar, denselben Annahmen und denselben Primitive-, Candidate-, State-
und Total-Work-Budgets. Der gehärtete Head
`51db948fb32d80b2a7fd6936481f71fe968afce0` bestand Gradle, Maven/Product/Docker,
SymPy, JMH und den abschließenden checkout-owned `ciCheck`; integriert wurde er
als `58b328293e402a78ba28ec5001a241811d1e7133`.

Die bounded Evidence ist grün, die Produktentscheidung bleibt
`KEEP_OPT_IN_PENDING_PRODUCT_COVERAGE`. Technische Ausfälle auf beiden
Vergleichsseiten werden fail-closed behandelt; ein technischer Fehler von
`DIRECT_V1` darf insbesondere nicht als zusätzliche SAFE-Reachability zählen.

## Explizite Applicability-Schemas und Coverage

`RewriteApplicabilitySchema` trennt Schema-ID, Applicability-Pattern,
`RecognitionProfile`, typisierte `RequiredAssumptionTemplate`s und den konkreten
`RewriteRule`-Executor. Ein positives Schema erzeugt selbst kein Ergebnis; der
konkrete Executor bleibt die fachliche Autorität.

Die Safe-Preparation-Coverage ist bewusst fail-closed:

- ausschließlich die exakte deklarative `PatternRewriteRule`-Klasse darf ihr
  Quellpattern als impliziten Vertrag verwenden;
- jede `PatternRewriteRule`-Subklasse sowie andere custom/algorithmische Regeln
  benötigen einen expliziten Vertrag über die in `RewriteRule` eingebettete
  Capability `RewriteApplicabilitySchemaProvider`;
- der Provider ist selbst die konkrete Regel; sein Schema muss dasselbe
  Executor-Objekt und dieselbe Regel-ID binden;
- aus Regel-ID, Java-Klasse, Beispiel, Benchmark oder beobachtetem Lauf wird
  niemals ein Schema abgeleitet;
- Regeln mit unvollständiger Domain-/Guard-Semantik bleiben als negative
  Coverage-Entscheidung sichtbar.

Die Coverage-Entscheidung liegt zentral in
`RewriteApplicabilitySchema.coverage(...)` / `coverageOf(...)`;
`RuleDomainRegistry.applicabilityCoverageFor(...)` liefert positive und negative
Entscheidungen, `applicabilitySchemasFor(...)` nur explizit zugelassene
Principals.

Explizit abgedeckt sind derzeit unter anderem:

| Domain | Algorithmische Regeln | Guards |
| --- | --- | --- |
| Trigonometrie | `trig_tan_to_sin_over_cos` | `cos(A) != 0` |
| Analysis | `calculus_exp_of_ln` | `X > 0` |
| Analysis | `calculus_ln_of_exp`, `calculus_exp_of_zero` | keine |
| Logarithmen | Produkt-/Quotientenregeln für `log` und `ln` | Argumente `> 0` |
| Logarithmen | Potenzregel für `log` und `ln` | Basis `> 0` |
| Wurzeln | `sqrt(A*B)` | `A >= 0`, `B >= 0` |
| Rationale Ausdrücke | Bruchmultiplikation | Nenner `!= 0` |
| Rationale Ausdrücke | Division durch Bruch | innerer Zähler/Nenner `!= 0` |

`rational_cancel_common_factor` bleibt absichtlich außerhalb der
schema-directed Preparation: Der Executor akzeptiert zwei strukturell
verschiedene Orientierungen, während der heutige Principal-Vertrag genau ein
Quellpattern trägt. Explizite Nullfaktoren im Nenner werden vor jeder
Orientierungswahl fail-closed abgelehnt. Ebenso bleiben whole-sum-/numeric-shape-
Regeln wie `polynomial_collect_like_terms` außerhalb, solange ihre vollständige
Anwendbarkeit nicht ehrlich durch den Patternvertrag ausdrückbar ist.

### Korrektur der Logarithmus-Semantik

Die Qualifikation hat einen älteren fachlichen Fehler offengelegt. In der
numerischen Regelsuche-Semantik ist `log` der Zehnerlogarithmus und `ln` der
natürliche Logarithmus. Die früher vorhandenen Regeln
`exp(log(x)) -> x` und `log(exp(x)) -> x` waren damit falsch. Sie wurden
entfernt, nicht in SAFE übernommen. Gültig bleiben `exp(ln(x)) -> x` mit
`x > 0` und `ln(exp(x)) -> x`. Regressionstests halten diese Trennung fest.

## Deterministisches Near-Match-Ranking

Die strukturbezogene Reihenfolge ist unter
`regelsuche.preparation-near-match-ranking/v1` charakterisiert. Für ein
Principal gilt lexikographisch:

1. vollständiger Match vor Near-Match;
2. mehr gematchte Pattern-Knoten;
3. mehr gebundene Variablen;
4. weniger Residual-Obligations;
5. kleinere Residual-Untergrenze.

| Residual | Kostenuntergrenze |
| --- | ---: |
| `LITERAL_MISMATCH` | 1 |
| `BINDING_CONFLICT` | 2 |
| `SHAPE_MISMATCH` | 3 |
| `FUNCTION_SHAPE_MISMATCH` | 4 |

Das sind deterministische strukturelle Ranking-Einheiten, keine CPU-Zeit und
keine Behauptung, dass genau so viele Primitive-Schritte zum Match genügen.
AST-Wachstum, Primitive-Path-Work und stabile strukturelle Tie-Breaker bleiben
separate Suchkriterien. Die Multi-Principal-Traversal aggregiert dieselben
Fortschrittssignale über ungelöste Principals, ohne Principal-Identitäten
zusammenzuführen. Charakterisierungstests binden sowohl die Single- als auch die
Multi-Principal-Produktionsordnung an den versionierten Rank, ohne die bereits
qualifizierte v1/v2-Semantik rückwirkend umzudeuten.

## Native exakte Spezialsolver

`SafePreparationEngineRegistry` bindet die vorhandene zertifikatstragende
Engine-Kette content-addressed:

| Stage | Spezialist | Native Hauptregel |
| --- | --- | --- |
| Direkte AST-Ausführung | `AstRewriteTransformationEngine` | alle sichtbaren Regeln |
| Exakter Polynomquotient | `RulePreparationPlanner` | `ast_cancel_division_factor` |
| AC-Faktorexposition | `AcNormalizationPreparationSolver` | `ast_cancel_division_factor` |
| Gemeinsamer Monomfaktor | `MonomialCommonFactorPreparationSolver` | `ast_factor_common_left` |
| Exakte Quadratexposition | `PerfectSquareStructurePreparationSolver` | `ast_square_difference_factor` |
| Gemeinsamer Nenner | `RationalCommonDenominatorPreparationSolver` | `hypothesis_rational_normalization` |

Stage-Reihenfolge, Solver-IDs, Engine-Klassen, native Principal-IDs und sichtbares
Regelinventar sind Teil der Identität. Certificates, Annahmen und primitive
Lineage der Spezialsolver bleiben die mathematische Autorität.

## Gemeinsamer Multi-Principal-Fallback

`SharedMultiPrincipalPreparationTraversal` ersetzt N voneinander unabhängige
bounded Fallback-Sessions durch eine gemeinsame physische Preparation-Frontier.
Geteilt werden normalisiertes Parsing, strukturelle Fingerprints,
vertragsidentische Pattern-Analysen, Expansionen, Visited-State-Deduplication und
das bounded Traversal-Budget. Principal-Identität, terminale Analyse, Guards,
Replay, Lineage und Zertifikat bleiben getrennt.

`SharedPreparationGuardFacts` teilt Guard-Fakten nur bei identischen
Template-Hashes, Matcher-Bindings, Matchstatus und Annahmensignatur.

## Work Accounting

Gemeinsame Fallback-Arbeit wird im Aggregate genau einmal gezählt. Direct- und
Exact-Arbeit bleibt principal-lokal. `SharedExecutionWork` weist zusätzlich
Source-Analyse, physische Fallback-Arbeit, Limits und Guard-Cache-Arbeit aus.

Für zwei vollständig überlappende Difference-of-Squares-Principals reduziert v2
die physische Fallback-Arbeit von zwei expandierten States/zwei generierten
Transitions auf einen State/eine Transition, während zwei Principal-Outcomes und
zwei Zertifikate erhalten bleiben. Das ist eine Work-Charakterisierung, kein
allgemeiner Wall-Clock-Speedup-Claim.

## Guards, Replay und Fail-closed-Verhalten

```text
keine Voraussetzung     -> autorisiert
Voraussetzung bestätigt -> autorisiert
Voraussetzung unbekannt -> kein Kandidat
Binding inconclusive    -> kein Kandidat
Template ungültig       -> technischer Fehler
```

Der allgemeine Bridge-Pfad instanziiert Guards an der vorbereiteten
Terminalexpression und prüft sie gegen Ausgangs- plus Vorbereitungsannahmen.
Technische Exceptions werden als `TECHNICAL_FAILURE` retained und nicht als
gewöhnliche Nichttreffer interpretiert. Nicht äquivalenzbewahrende
Vorbereitungsregeln, doppelte IDs und nicht reviewfähige Principals werden vor
der Ausführung abgelehnt.

`verify(...)` berechnet die Evaluation mit frischen per-Evaluation-Caches erneut.
Zertifikate binden Repository-Revision, Principal-Schema,
Preparation-Inventar, Budget, Source-/Terminalanalyse, Annahmen,
Vorbereitungspfad, konkreten Principal-Replay, primitive Lineage und Work-
Kontext.

## Typisierte Repräsentationskandidaten

Die in #746 / PR #946 gelieferte `RepresentationPreparation` ist bereits der
typisierte Entry-Point für Gleichungssystem-, Matrix- und Operator-Kandidaten.
Sie akzeptiert nur ausdrücklich erlaubte `RepresentationBridge.Relation`s,
verifiziert die Formation unabhängig, führt anschließend den konkreten
typisierten Downstream-Principal mit dem Restbudget aus und akzeptiert nur nach
dessen eigener Verifikation. `UnifiedRulePreparationCoordinator` stellt diesen
Pfad über `prepareRepresentation(...)` bereit. Solution-set-, Linear-map-,
Basis-, Spektral- und Model-Interpretation-Relationen werden dabei nicht in
skalare Ausdrucksgleichheit umgedeutet.

## Gelernte Regeln und Programme

Exakt autorisierte gelernte Pattern-Regeln können denselben Applicability- und
Preparation-Pfad verwenden. Rohe `CompiledGenomeRule`-Objekte bleiben nicht
äquivalenzbewahrend und werden abgelehnt. Gelernte `RewriteProgram`s besitzen
seit #965 einen eigenen programmbasierten Authorization-/Replay-Vertrag;
Sequence, Choice, Repeat, Guards, Priorisierung und Pruning werden nicht als
scheinbar atomare Pattern-Regel maskiert.

## Noch offene Produktintegration

Nach #967 und der Applicability-Coverage bleiben insbesondere:

- occurrence-lokale Guard-Bindungen für verschachtelte guarded Principals; diese
  werden in #971 als neue versionierte SAFE-Authority qualifiziert, ohne die
  historische V2-Evidence umzudeuten;
- ein gemeinsamer qualifizierter Workbench-/CLI-Runtime-Adapter für diese neue
  Authority (#972);
- abschließende clean-checkout/pinned-container Reproduktion und synchronisierte
  Produktdokumentation vor einer erneuten Default-Entscheidung.

Historische Läufe behalten ihre ursprünglichen Engine- und
Inventaridentitäten.

## Prüfung aus dem Checkout

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.rules.DomainRulesTest

./gradlew :regelsuche-search:test \
  --tests de.regelsuche.search.reachability.SharedPreparationTraversalTest \
  --tests de.regelsuche.search.reachability.SharedMultiPrincipalPreparationTraversalTest \
  --tests de.regelsuche.search.reachability.SharedUnifiedRulePreparationCoordinatorTest

./gradlew --no-configuration-cache ciCheck
```

## Siehe auch

- [Rule-directed Preparation Planning](rule-directed-preparation-planning.md)
- [Promotion gelernter Pattern-Regeln](learned-pattern-rule-promotion.md)
- [Authorization gelernter RewritePrograms](learned-rewrite-program-authorization.md)
- [Search Intelligence](search-intelligence.md)
- [Architektur](architecture.md)
- [Unterstützte Grenzen](limits.md)
