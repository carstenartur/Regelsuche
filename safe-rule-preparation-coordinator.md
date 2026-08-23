# Sicherer Regelvorbereitungskoordinator

**Implementierungsstand: 23. August 2026**

Die Vorbereitungsschicht ersetzt die binäre Grenze
`matches -> apply or discard` durch einen begrenzten, nachprüfbaren Ablauf. Sie
soll eine vorhandene Regel anwendbar machen, ohne ein gewünschtes Endergebnis,
eine Benchmarkantwort oder eine nachträgliche Bewertung als Suchziel zu
verwenden.

## Aktuelle Ausführungsreihenfolge

Der produktnahe `UnifiedRulePreparationCoordinator` führt für jedes explizit
sichtbare Principal-Schema dieselbe Reihenfolge aus:

```text
konkreter Executor: direkter Replay-Versuch
  -> typisierte Guard-Prüfung für einen direkten Treffer
  -> registrierter nativer Exact-Spezialist
  -> bounded pattern-targeted local bridge
  -> konkreter Principal-Replay
  -> unabhängige Zertifikatsprüfung
  -> retained Outcome oder fail-closed Status
```

Die konkrete Regel wird **vor** dem Applicability-Schema ausgeführt. Ein zu
enges oder veraltetes Schema darf daher eine direkt mögliche algorithmische
Regel nicht blockieren. Das Schema lenkt nur Vorbereitung und Guard-Bindung; es
ist keine zweite Ausführung der Regel.

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

## Zwei vorbereitende Ausführungsschichten

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
allgemeine lokale Bridge-Pfad zuständig.

### 2. Allgemeiner lokaler Pattern-Bridge-Fallback

`RulePreparationCoordinator` und `PatternTargetedLocalBridgeSearch` führen eine
begrenzte, deterministische Suche mit einem eingefrorenen Vorbereitungsinventar
aus. Ziel ist ausschließlich die Applicability-Struktur der Hauptregel.

Ein erfolgreicher Pfad behält:

- Ausgangs-, Terminal- und Ergebnisexpression;
- Ausgangs- und Ergebnisannahmen;
- jede primitive Vorbereitungsregel;
- den konkreten Hauptregelschritt;
- Matchanalyse und Bindungen;
- Work Accounting und erreichte Grenzen;
- Repository-, Regel- und Inventaridentitäten;
- ein unabhängig wiederholbares Bridge-Zertifikat.

Der Suchlauf unterscheidet insbesondere direkte Treffer, vorbereitete Treffer,
vollständig ausgeschöpfte endliche Closures, Budget-Inconclusive,
Unsupported, ungültige Zertifikate und technische Fehler.

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

Der allgemeine lokale Pfad kann Guards nach der vorbereiteten
Terminalexpression instanziieren und gegen Ausgangs- plus
Vorbereitungsannahmen prüfen.

Die nativen Exact-Spezialisten besitzen eigene Annahmen- und
Zertifikatsverträge. Der Unified Coordinator verwendet den Exact-Pfad derzeit
nur für deren ausdrücklich native Hauptregeln ohne zusätzliche
`RequiredAssumptionTemplate`s. Eine spätere Erweiterung kann terminale
Matcher-Bindungen aus Exact-Spezialisten exponieren; bis dahin bleiben guarded
fremde Principal-Schemata im allgemeinen lokalen Pfad.

## Fail-closed Verhalten

Die Ausführung interpretiert technische Exceptions nicht als gewöhnlichen
Nichttreffer. Sie werden als retained `TECHNICAL_FAILURE` mit einem
stadienspezifischen Detailcode ausgegeben. Nicht äquivalenzbewahrende
Vorbereitungsregeln, doppelte IDs, nicht reviewfähige Principals und externe
Principals oberhalb des v1-Risikolimits werden vor der Ausführung abgelehnt.

Direkt-, Exact- und Local-Konfigurationen bleiben getrennt identifizierbar.
`verify(...)` berechnet die vollständige Evaluation erneut. Exact-Zertifikate
binden zusätzlich die Repository-Revision, die Registry, das Principal-Schema,
Ausdruck, Annahmen und primitive Lineage.

## SymPy-Amplifikationsmatrix

Das retained Experiment verwendet drei unveränderte importierte Regeln:

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
gegenüber SymPy.

## Gelernte Regeln

Eine exakt promovierte gelernte Pattern-Regel kann dasselbe
`RewriteApplicabilitySchema` und denselben lokalen Bridge-Fallback verwenden.
Der charakterisierte Promotionspfad zeigt dies für eine gelernte
Differenz-von-Quadraten-Regel nach einem exakten Polynomidentitätsnachweis.

Rohe `CompiledGenomeRule`-Objekte bleiben dagegen
`isEquivalencePreservingByConstruction() == false` und werden abgelehnt.
Gelernte `RewriteProgram`s mit mehreren Eintrittspfaden benötigen einen eigenen
programmbasierten Applicability-/Replay-Vertrag.

Details stehen unter
[Promotion exakt bewiesener gelernter Pattern-Regeln](learned-pattern-rule-promotion.md).
Der öffentliche Capability-Claim `PROMOTION` bleibt bis zu einer realen
Qualification `NOT_EVALUATED`.

## Gegenwärtige Grenzen

Der Unified Coordinator ist implementiert und charakterisiert, aber noch nicht
als allgemeiner Workbench-/CLI-Standard ausgewählt. Weiter offen sind:

- eine gemeinsame Multi-Principal-Frontier statt je einer lokalen Session;
- eine einzige geteilte AST-/Value-Traversierung für Matching, Fingerprints und
  Deduplication;
- terminale Matcher-Bindungen für guarded native Exact-Spezialisten;
- direkte Teilnahme typisierter Repräsentationsbrücken für Gleichungssysteme,
  Matrizen und Operatoren;
- eine matched-work Produktqualifikation von `SAFE_PREPARATION_V1` gegenüber
  `DIRECT_V1`.

Historische Läufe behalten ihre ursprünglichen Engine- und Inventaridentitäten.
Die neue Registry deutet bestehende Evidence nicht rückwirkend um.

## Prüfung aus dem Checkout

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.transform.SafePreparationEngineRegistryTest

./gradlew :regelsuche-search:test \
  --tests de.regelsuche.search.reachability.UnifiedRulePreparationCoordinatorTest

./gradlew --no-configuration-cache ciCheck
```

## Siehe auch

- [Rule-directed Preparation Planning](rule-directed-preparation-planning.md)
- [Promotion gelernter Pattern-Regeln](learned-pattern-rule-promotion.md)
- [Search Intelligence](search-intelligence.md)
- [Architektur](architecture.md)
- [Unterstützte Grenzen](limits.md)
