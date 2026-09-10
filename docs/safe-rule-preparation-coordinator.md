# Sicherer Regelvorbereitungskoordinator

**Implementierungsstand: 10. September 2026**

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

`SharedUnifiedRulePreparationCoordinator` führt den neuen Vertrag
`regelsuche.unified-safe-rule-preparation-coordinator/v2` ein. Die mathematischen
Autoritäten ändern sich dabei nicht. v2 teilt ausschließlich physische Arbeit,
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

Die konkrete Regel wird **vor** dem Applicability-Schema ausgeführt. Ein zu
enges oder veraltetes Schema darf daher eine direkt mögliche algorithmische
Regel nicht blockieren. Das Schema lenkt nur Vorbereitung und Guard-Bindung; es
ist keine zweite Ausführung der Regel.

v2 ist in diesem Stand eine explizite Authority und noch keine stille
Umstellung des allgemeinen Workbench-/CLI-Defaults. Eine Produktumschaltung
benötigt weiterhin eine eigene Auswahl- und Qualifikationsentscheidung.

## Applicability-Schema

`RewriteApplicabilitySchema` trennt:

```text
Schema-ID
Applicability-Pattern
RecognitionProfile
RequiredAssumptionTemplate-Liste
konkreter RewriteRule-Executor
```

Das Schema enthält bewusst kein erfundenes Zielpattern. Ein positiver Kandidat
wird nur durch den konkreten Executor erzeugt. Schema, Executor, Guard-
Templates, Metadaten und Repository-Revision fließen in die retained
Identitäten ein.

Deklarative `PatternRewriteRule`s können ihr vorhandenes Quellpattern direkt
verwenden. Algorithmische Java-Regeln benötigen ein ausdrücklich deklariertes
Applicability-Schema. Nennerfaktoren deklarativer Regeln werden im derzeit
unterstützten Fragment als typisierte Nichtnull-Voraussetzungen gebunden.

## Vorbereitende Ausführungsschichten

### 1. Native exakte Spezialsolver

`SafePreparationEngineRegistry` versieht die vorhandene zertifikatstragende
Engine-Kette mit einer gemeinsamen, content-addressed Registry. Die Reihenfolge
und die jeweils zulässige native Hauptregel sind explizit:

| Stage | Spezialist | Native Hauptregel |
| --- | --- | --- |
| Direkte AST-Ausführung | `AstRewriteTransformationEngine` | alle sichtbaren Regeln |
| Exakter Polynomquotient | `RulePreparationPlanner` | `ast_cancel_division_factor` |
| AC-Faktorexposition | `AcNormalizationPreparationSolver` | `ast_cancel_division_factor` |
| Gemeinsamer Monomfaktor | `MonomialCommonFactorPreparationSolver` | `ast_factor_common_left` |
| Exakte Quadratexposition | `PerfectSquareStructurePreparationSolver` | `ast_square_difference_factor` |
| Gemeinsamer Nenner | `RationalCommonDenominatorPreparationSolver` | `hypothesis_rational_normalization` |

Die Registry bindet Stage-Reihenfolge, Solver-IDs, Engine-Klassen, native
Principal-IDs und das geordnete sichtbare Regelinventar. Sie ersetzt die
Spezialsolver nicht: deren eigene Certificates, konkreter Principal-Replay,
Annahmen und primitive Lineage bleiben die mathematische Autorität.

Ein ähnlich aussehendes Pattern erhält nicht automatisch die Autorität eines
nativen Spezialsolvers. Beispielsweise wird die importierte Regel
`sympy.poly.factor.diff_squares` nicht als nativer
`ast_square_difference_factor`-Fall ausgegeben. Für solche Regeln bleibt der
allgemeine Bridge-Pfad zuständig.

### 2. Gemeinsamer Multi-Principal-Bridge-Fallback in v2

`SharedMultiPrincipalPreparationTraversal` ersetzt für v2 die N voneinander
unabhängigen bounded Fallback-Sessions durch eine gemeinsame physische
Preparation-Frontier. Sie teilt:

- normalisiertes Parsing und strukturelle Fingerprints;
- Pattern-Analyse nur bei identischem Pattern-, Recognition- und Matcher-Budget-Vertrag;
- Expansionen des eingefrorenen Preparation-Inventars;
- ein gemeinsames Visited-State-Set und Deduplication;
- das bounded Traversal-Budget;
- deterministische Kandidatenordnung über den besten sichtbaren Fortschritt der
  noch ungelösten Principals.

Nicht geteilt werden Principal-Identität und fachliche Autorisierung. Für jedes
Principal-Schema bleiben getrennt erhalten:

- initiale und terminale Matchanalyse;
- Terminalexpression und Annahmen;
- konkreter Vorbereitungspfad;
- Guard-Entscheidung;
- konkreter Principal-Replay;
- komplette primitive Lineage;
- eigenes content-addressed Zertifikat.

Ein erfolgreicher gemeinsamer Pfad ist damit kein kostenloser Makroschritt. Die
Vorbereitungsregeln und der anschließende Principal-Schritt bleiben als primitive
Regel-IDs erhalten.

`SharedPreparationGuardFacts` teilt Guard-Fakten nur, wenn Template-Hashes,
Matcher-Bindings, Matchstatus und Annahmensignatur identisch sind. Gleiche
Principal-Namen oder ähnliche Java-Klassen reichen nicht aus. Unterschiedliche
Guard-Arten wie `NON_ZERO` und `POSITIVE` erhalten getrennte Fakten.

## Work Accounting: logisch referenzieren, physisch einmal zählen

Jedes v2-Fallback-Outcome referenziert den Work-Kontext der gemeinsamen
Traversal. Würde man diese Outcome-Work-Werte einfach über alle Principals
summieren, würde dieselbe physische Arbeit wieder N-mal gezählt. Deshalb hat v2
einen eigenen Aggregate-Vertrag:

- Direct- und Exact-Arbeit wird wie bisher pro tatsächlich ausgeführtem
  Principal gezählt;
- die gemeinsame bounded Fallback-Arbeit wird im `aggregateWork` **genau einmal**
  addiert;
- `SharedExecutionWork` weist zusätzlich Cache-/Physical-Work aus, darunter
  Source-Analyse, Fallback-Expansionen, erreichte Limits und Guard-Cache-Treffer.

Ein deterministischer Zwei-Principal-Charakterisierungstest macht den Unterschied
explizit. Beide Coordinator-Versionen erhalten dieselben zwei Principals,
dasselbe Preparation-Inventar, denselben Ausdruck und dasselbe Budget. Beide
liefern dieselben Ergebnisexpressionen und dieselbe primitive Lineage. Für den
gemeinsam benötigten Difference-of-Squares-Vorbereitungsschritt gilt jedoch:

| Messgröße | v1: zwei unabhängige Fallbacks | v2: gemeinsame Frontier |
| --- | ---: | ---: |
| Principal-Outcomes | 2 | 2 |
| expandierte Fallback-States | 2 | 1 |
| generierte Fallback-Transitions | 2 | 1 |
| unterschiedliche Principal-Zertifikate | 2 | 2 |

Das ist eine gezielte Work-Charakterisierung für vollständig überlappende
Vorbereitungsarbeit, **kein** allgemeiner Laufzeit- oder Speedup-Claim. Bei
Principals mit wenig gemeinsamer Struktur kann die Einsparung entsprechend
kleiner sein.

## Guards und Annahmen

Eine syntaktische Übereinstimmung autorisiert keine bedingte Identität. Für
typisierte Voraussetzungen gilt:

```text
keine Voraussetzung     -> autorisiert
Voraussetzung bestätigt -> autorisiert
Voraussetzung unbekannt -> kein Kandidat
Binding inconclusive    -> kein Kandidat
Template ungültig       -> technischer Fehler
```

Der allgemeine Bridge-Pfad kann Guards nach der vorbereiteten
Terminalexpression instanziieren und gegen Ausgangs- plus
Vorbereitungsannahmen prüfen. v2 kann dafür die bereits retained terminale
`AnalysisSnapshot` verwenden, statt die vollständige Patternanalyse für die
Guard-Phase erneut auszuführen.

Die nativen Exact-Spezialisten besitzen eigene Annahmen- und
Zertifikatsverträge. Der Unified Coordinator verwendet den Exact-Pfad derzeit
nur für deren ausdrücklich native Hauptregeln ohne zusätzliche
`RequiredAssumptionTemplate`s. Eine spätere Erweiterung kann terminale
Matcher-Bindungen aus Exact-Spezialisten exponieren; bis dahin bleiben guarded
fremde Principal-Schemata im allgemeinen Fallback-Pfad.

## Fail-closed Verhalten

Die Ausführung interpretiert technische Exceptions nicht als gewöhnlichen
Nichttreffer. Sie werden als retained `TECHNICAL_FAILURE` mit einem
stadienspezifischen Detailcode ausgegeben. Nicht äquivalenzbewahrende
Vorbereitungsregeln, doppelte IDs und nicht reviewfähige Principals werden vor
der Ausführung abgelehnt.

Direct-, Exact- und Fallback-Konfigurationen bleiben getrennt identifizierbar.
`verify(...)` berechnet die vollständige Evaluation mit frischen per-Evaluation
Caches erneut. Die v2-Shared-Zertifikate binden zusätzlich die
Repository-Revision, das Principal-Schema, Preparation-Inventar, Budget,
Source-/Terminalanalyse, Annahmen, Vorbereitungspfad, konkreten Principal-Replay,
primitive Lineage und den geteilten Work-Kontext.

## SymPy-Amplifikationsmatrix

Das retained v1-Experiment verwendet drei unveränderte importierte Regeln:

```text
sympy.trig.pythagorean
sympy.poly.factor.diff_squares
sympy.rational.partial_fraction.telescoping
```

Elf deklarierte Fälle umfassen vier direkte Anwendungen, vier zusätzliche
lokal vorbereitete Anwendungen und drei konklusive Near-Misses. Das gemeinsame
lokale Vorbereitungsinventar enthält nur `ast_cancel_division_factor`.
Rationale Fälle behalten ihre Nichtnullbedingungen ausdrücklich.

Reproduktion:

```bash
./gradlew :regelsuche-experiments:symPyRuleAmplification
```

Die Matrix belegt eine begrenzte Verstärkung deklarierter Anwendbarkeit. Sie
belegt keine allgemeine Überlegenheit, Vollständigkeit oder bessere Laufzeit
gegenüber SymPy. Die historische Experimentidentität wird durch v2 nicht
rückwirkend verändert.

## Gelernte Regeln und Programme

Eine exakt promovierte gelernte Pattern-Regel kann dasselbe
`RewriteApplicabilitySchema` und den Bridge-Fallback verwenden. Der
charakterisierte Promotionspfad zeigt dies für eine gelernte
Differenz-von-Quadraten-Regel nach einem exakten Polynomidentitätsnachweis.

Rohe `CompiledGenomeRule`-Objekte bleiben dagegen
`isEquivalencePreservingByConstruction() == false` und werden abgelehnt.

Gelernte `RewriteProgram`s besitzen seit #965 einen separaten
programmbasierten Authorization-/Replay-Vertrag. Sie werden nicht als eine
scheinbar atomare Pattern-Regel maskiert; Sequence, Choice, FirstApplicable,
Repeat, Guards, Priorisierung und Pruning bleiben Bestandteil ihrer kanonischen
Programmtopologie und Work-Evidence.

Details stehen unter
[Promotion exakt bewiesener gelernter Pattern-Regeln](learned-pattern-rule-promotion.md)
und [Authorization gelernter RewritePrograms](learned-rewrite-program-authorization.md).

## Gegenwärtige Grenzen

Der v2 Shared Coordinator ist implementiert und charakterisiert, aber noch nicht
als allgemeiner Workbench-/CLI-Standard ausgewählt. Weiter offen sind:

- eine mögliche zusätzliche Cross-Stage-Wiederverwendung desselben physischen
  Caches zwischen Source-Analyse und Fallback-Root; v2 teilt die Arbeit bereits
  zwischen allen Principals innerhalb der jeweiligen Stufe;
- terminale Matcher-Bindungen für guarded native Exact-Spezialisten;
- direkte Teilnahme typisierter Repräsentationsbrücken für Gleichungssysteme,
  Matrizen und Operatoren;
- eine matched-work Produktqualifikation von `SAFE_PREPARATION_V1` bzw. einer
  späteren v2-Produktpolicy gegenüber `DIRECT_V1`.

Historische Läufe behalten ihre ursprünglichen Engine- und Inventaridentitäten.
Der v2-Vertrag deutet bestehende Evidence nicht rückwirkend um.

## Prüfung aus dem Checkout

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.transform.SafePreparationEngineRegistryTest

./gradlew :regelsuche-search:test \
  --tests de.regelsuche.search.reachability.UnifiedRulePreparationCoordinatorTest \
  --tests de.regelsuche.search.reachability.SharedPreparationTraversalTest \
  --tests de.regelsuche.search.reachability.SharedMultiPrincipalPreparationTraversalTest \
  --tests de.regelsuche.search.reachability.SharedPreparationGuardFactsTest \
  --tests de.regelsuche.search.reachability.SharedPreparedPrincipalReplayTest \
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
