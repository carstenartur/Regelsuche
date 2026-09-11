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

`SharedUnifiedRulePreparationCoordinator` führt den Vertrag
`regelsuche.unified-safe-rule-preparation-coordinator/v2` ein. Die mathematischen
Autoritäten ändern sich dadurch nicht. v2 teilt ausschließlich physische Arbeit,
die unabhängig von der Principal-Identität ist, und behält für jeden Principal
ein eigenes Outcome, eigene Guards, konkreten Replay und ein eigenes
Zertifikat.

Die gestufte Reihenfolge von v2 ist:

```text
konkreter Executor: direkter Replay-Versuch
  -> typisierte Guard-Prüfung für einen direkten Treffer
  -> theory-aware Source-Pattern-Analyse
  -> registrierter nativer Exact-Spezialist
  -> alle noch ungelösten Principals
       in eine gemeinsame bounded Preparation-Frontier
  -> terminale Guards pro Principal
  -> konkreter Principal-Replay pro Principal
  -> content-addressed Zertifikat pro Principal
  -> retained Outcome oder fail-closed Status
```

Die konkrete Regel wird vor der vorbereitenden Suche ausgeführt. Das Schema
lenkt Vorbereitung und Guard-Bindung; es ersetzt niemals den konkreten Executor.

## Product-Qualification

PR #967 vergleicht `DIRECT_V1` und `SAFE_PREPARATION_V2` mit demselben sichtbaren
Regelinventar, denselben Annahmen und denselben Primitive-, Candidate-, State-
und Total-Work-Budgets. Der gehärtete Head
`51db948fb32d80b2a7fd6936481f71fe968afce0` bestand Gradle, Maven/Product/Docker,
SymPy, JMH und den abschließenden checkout-owned `ciCheck`. Der integrierte
Commit ist `58b328293e402a78ba28ec5001a241811d1e7133`.

Die bounded Evidence ist grün, die Produktentscheidung bleibt jedoch
`KEEP_OPT_IN_PENDING_PRODUCT_COVERAGE`. SAFE v2 wird daher noch nicht still zum
Workbench-/CLI-Default. Die verbleibenden Blocker sind bewusst als
Produktabdeckung modelliert und nicht als verdeckte Sicherheitsausnahme.

Die Qualifikation behandelt technische Ausfälle auf beiden Vergleichsseiten
fail-closed. Ein technischer Fehler von `DIRECT_V1` darf insbesondere nicht als
zusätzliche Reachability von SAFE gezählt werden.

## Explizite Applicability-Schemas

`RewriteApplicabilitySchema` trennt:

```text
Schema-ID
Applicability-Pattern
RecognitionProfile
RequiredAssumptionTemplate-Liste
konkreter RewriteRule-Executor
```

Ein positives Schema erzeugt selbst kein Ergebnis. Ein Kandidat wird erst durch
den konkreten Executor autorisiert.

Für die Safe-Preparation gilt eine explizite Coverage-Grenze:

- deklarative `PatternRewriteRule`s dürfen ihr vorhandenes Quellpattern nutzen;
- algorithmische Java-Regeln müssen am konkreten Regelobjekt
  `RewriteApplicabilitySchemaProvider` implementieren;
- das Schema muss exakt dieses Executor-Objekt und dieselbe Regel-ID binden;
- Regeln ohne vollständigen expliziten Vertrag bleiben im Coverage-Bericht
  sichtbar, aber außerhalb des Safe-Preparation-Profils;
- es wird kein Schema aus Regel-ID, Java-Klasse, Beispiel, Benchmark oder
  beobachtetem Lauf abgeleitet.

`RewriteApplicabilityCatalog` stellt diese Entscheidung zentral bereit.
`RuleDomainRegistry.applicabilityCoverageFor(...)` liefert positive und negative
Entscheidungen, `applicabilitySchemasFor(...)` nur die explizit zugelassenen
Schemas.

Die aktuelle algorithmische Coverage sowie bewusst ausgeschlossene Regeln sind
unter [Applicability-schema coverage](applicability-schema-coverage.md)
dokumentiert.

## Deterministisches Near-Match-Ranking

Die strukturbezogene Reihenfolge ist unter
`regelsuche.preparation-near-match-ranking/v1` charakterisiert. Für ein
Principal gilt lexikographisch:

1. vollständiger Match vor Near-Match;
2. mehr gematchte Pattern-Knoten;
3. mehr gebundene Variablen;
4. weniger Residual-Obligations;
5. kleinere Residual-Untergrenze.

Die Residual-Untergrenze verwendet ausschließlich den Typ der noch offenen
Strukturbedingung:

| Residual | Kostenuntergrenze |
| --- | ---: |
| `LITERAL_MISMATCH` | 1 |
| `BINDING_CONFLICT` | 2 |
| `SHAPE_MISMATCH` | 3 |
| `FUNCTION_SHAPE_MISMATCH` | 4 |

Das sind deterministische Ranking-Einheiten, keine CPU-Zeit und keine Behauptung,
dass genau so viele Primitive-Schritte zum Match genügen. AST-Wachstum,
Primitive-Path-Work und stabile strukturelle Tie-Breaker bleiben getrennte
Suchkriterien.

Die Multi-Principal-Traversal aggregiert dieselben Fortschrittssignale über die
noch ungelösten Principals, ohne Principal-Identitäten zusammenzuführen.

## Native exakte Spezialsolver

`SafePreparationEngineRegistry` versieht die vorhandene zertifikatstragende
Engine-Kette mit einer gemeinsamen, content-addressed Registry:

| Stage | Spezialist | Native Hauptregel |
| --- | --- | --- |
| Direkte AST-Ausführung | `AstRewriteTransformationEngine` | alle sichtbaren Regeln |
| Exakter Polynomquotient | `RulePreparationPlanner` | `ast_cancel_division_factor` |
| AC-Faktorexposition | `AcNormalizationPreparationSolver` | `ast_cancel_division_factor` |
| Gemeinsamer Monomfaktor | `MonomialCommonFactorPreparationSolver` | `ast_factor_common_left` |
| Exakte Quadratexposition | `PerfectSquareStructurePreparationSolver` | `ast_square_difference_factor` |
| Gemeinsamer Nenner | `RationalCommonDenominatorPreparationSolver` | `hypothesis_rational_normalization` |

Die Registry bindet Stage-Reihenfolge, Solver-IDs, Engine-Klassen, native
Principal-IDs und das geordnete sichtbare Regelinventar. Die Spezialsolver
behalten ihre eigenen Certificates, Annahmen und primitive Lineage.

## Gemeinsamer Multi-Principal-Fallback

`SharedMultiPrincipalPreparationTraversal` ersetzt N voneinander unabhängige
bounded Fallback-Sessions durch eine gemeinsame physische Preparation-Frontier.
Sie teilt:

- normalisiertes Parsing und strukturelle Fingerprints;
- Pattern-Analyse nur bei identischem Pattern-, Recognition- und Matcher-Budget-Vertrag;
- Expansionen des eingefrorenen Preparation-Inventars;
- ein gemeinsames Visited-State-Set und Deduplication;
- das bounded Traversal-Budget;
- deterministische Kandidatenordnung über den besten sichtbaren Fortschritt.

Nicht geteilt werden Principal-Identität und fachliche Autorisierung. Für jeden
Principal bleiben initiale/terminale Matchanalyse, Terminalexpression,
Annahmen, Vorbereitungspfad, Guard-Entscheidung, konkreter Replay, primitive
Lineage und Zertifikat getrennt erhalten.

`SharedPreparationGuardFacts` teilt Guard-Fakten nur bei identischen
Template-Hashes, Matcher-Bindings, Matchstatus und Annahmensignatur.

## Work Accounting

Die gemeinsame Fallback-Arbeit wird im Aggregate genau einmal gezählt. Direct-
und Exact-Arbeit bleibt principal-lokal. `SharedExecutionWork` weist zusätzlich
Source-Analyse, physische Fallback-Arbeit, erreichte Limits und Guard-Cache-
Arbeit aus.

Für zwei vollständig überlappende Difference-of-Squares-Principals reduziert v2
die physische Fallback-Arbeit von zwei expandierten States/zwei generierten
Transitions auf einen State/eine Transition, während zwei Principal-Outcomes und
zwei Zertifikate erhalten bleiben. Das ist eine gezielte Work-Charakterisierung,
kein allgemeiner Wall-Clock-Speedup-Claim.

## Guards und Annahmen

Eine syntaktische Übereinstimmung autorisiert keine bedingte Identität:

```text
keine Voraussetzung     -> autorisiert
Voraussetzung bestätigt -> autorisiert
Voraussetzung unbekannt -> kein Kandidat
Binding inconclusive    -> kein Kandidat
Template ungültig       -> technischer Fehler
```

Der allgemeine Bridge-Pfad instanziiert Guards an der vorbereiteten
Terminalexpression und prüft sie gegen Ausgangs- plus Vorbereitungsannahmen.
v2 kann dafür die retained `AnalysisSnapshot` verwenden.

## Fail-closed Verhalten

Technische Exceptions werden nicht als gewöhnliche Nichttreffer interpretiert,
sondern als retained `TECHNICAL_FAILURE`. Nicht äquivalenzbewahrende
Vorbereitungsregeln, doppelte IDs und nicht reviewfähige Principals werden vor
der Ausführung abgelehnt.

`verify(...)` berechnet die vollständige Evaluation mit frischen per-Evaluation
Caches erneut. Zertifikate binden Repository-Revision, Principal-Schema,
Preparation-Inventar, Budget, Source-/Terminalanalyse, Annahmen,
Vorbereitungspfad, konkreten Principal-Replay, primitive Lineage und Work-
Kontext.

## Gelernte Regeln und Programme

Exakt autorisierte gelernte Pattern-Regeln können denselben Applicability- und
Preparation-Pfad verwenden. Rohe `CompiledGenomeRule`-Objekte bleiben
nicht äquivalenzbewahrend und werden abgelehnt.

Gelernte `RewriteProgram`s besitzen seit #965 einen eigenen programmbasierten
Authorization-/Replay-Vertrag; Sequence, Choice, Repeat, Guards, Priorisierung
und Pruning werden nicht als scheinbar atomare Pattern-Regel maskiert.

## Noch offene Produktintegration

Nach #967 und der Applicability-Coverage bleiben insbesondere:

- terminale Matcher-Bindungen für guarded native Exact-Spezialisten;
- typisierte Repräsentationskandidaten für Gleichungssysteme, Matrizen und
  Operatoren ohne Reduktion auf skalare Ausdrucksgleichheit;
- ein qualifizierter Workbench-/CLI-Runtime-Adapter;
- abschließende clean-checkout/pinned-container Reproduktion und synchronisierte
  Produktdokumentation vor einer Default-Umschaltung.

Historische Läufe behalten ihre ursprünglichen Engine- und
Inventaridentitäten.

## Prüfung aus dem Checkout

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.transform.RewriteApplicabilityCatalogTest

./gradlew :regelsuche-search:test \
  --tests de.regelsuche.search.reachability.PreparationNearMatchRankingTest \
  --tests de.regelsuche.search.reachability.SharedUnifiedRulePreparationCoordinatorTest

./gradlew --no-configuration-cache ciCheck
```

## Siehe auch

- [Applicability-schema coverage](applicability-schema-coverage.md)
- [Rule-directed Preparation Planning](rule-directed-preparation-planning.md)
- [Promotion gelernter Pattern-Regeln](learned-pattern-rule-promotion.md)
- [Authorization gelernter RewritePrograms](learned-rewrite-program-authorization.md)
- [Search Intelligence](search-intelligence.md)
- [Architektur](architecture.md)
- [Unterstützte Grenzen](limits.md)
