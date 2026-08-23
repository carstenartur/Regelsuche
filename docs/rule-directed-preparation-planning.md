# Rule-directed Preparation Planning

**Implementierungsstand: 23. August 2026**

Rule-directed Preparation bestimmt, ob eine fast passende Regel nach wenigen
legalen, begrenzten und unabhängig prüfbaren lokalen Schritten anwendbar wird.
Die Hauptregel bleibt unverändert; Vorbereitung, Annahmen und primitive Lineage
werden als Teil des tatsächlichen Zuges retained.

## Motivation

Eine rein binäre Regelgrenze verwirft strukturell nahe Fälle:

```text
rule.matches(subtree) -> apply or discard
```

Die Vorbereitungsschicht analysiert stattdessen die Anwendbarkeitsstruktur der
sichtbaren Regel. Beim Kürzungsschema

```text
(A * B) / A -> B    unter A != 0
```

kann beispielsweise der Ausdruck

```text
(x^3 - 1) / (x - 1)
```

die Residualbedingung

```text
x^3 - 1 = (x - 1) * B
```

erzeugen. Der exakte Polynomsolver bestimmt `B = x^2 + x + 1`, prüft den
Rest null und führt danach die gewöhnliche Kürzungsregel konkret aus.

## Implementierte Ausführung

Die aktuelle Architektur besitzt drei geordnete Stufen:

```text
1. konkrete direkte Hauptregel
2. nativer exakter Vorbereitungsspezialist
3. bounded pattern-targeted local bridge
```

Der `UnifiedRulePreparationCoordinator` orchestriert diese Stufen pro explizit
sichtbarem `RewriteApplicabilitySchema`. Direkte Ausführung hat Vorrang vor der
Schemaanalyse. Technische Fehler werden als `TECHNICAL_FAILURE` retained und
nicht als gewöhnlicher Nichttreffer umgedeutet.

### Native Exact-Spezialisten

`SafePreparationEngineRegistry` bindet die vorhandenen Solver an eine feste
Reihenfolge und an ihre jeweils native Principal-Regel:

| Spezialist | Beispiel | Native Hauptregel |
| --- | --- | --- |
| Exakter Polynomquotient | `(x^3-1)/(x-1)` | `ast_cancel_division_factor` |
| AC-Faktorexposition | `(b*(a*c))/a` | `ast_cancel_division_factor` |
| Gemeinsamer Monomfaktor | `x^2*y+x*z` | `ast_factor_common_left` |
| Exakte Quadratexposition | `4*x^4*y^2-9*z^2` | `ast_square_difference_factor` |
| Gemeinsamer Nenner | `a/b+c/d` | `hypothesis_rational_normalization` |

Jeder Spezialist besitzt einen eigenen mathematischen Fragmentvertrag, ein
Budget, Work Accounting, konkrete Principal-Wiederholung und ein
content-addressed Certificate. Die Registry zentralisiert Identität und
Reihenfolge, ersetzt diese Nachweise aber nicht.

Ein fremdes oder gelerntes Pattern mit ähnlicher Form erbt keinen nativen
Solververtrag. Nur die explizit registrierte Principal-ID kann eine native
Exact-Stage verwenden.

### Allgemeiner lokaler Bridge-Fallback

`PatternMatchAnalyzer` liefert strukturierte Ergebnisse für:

```text
EXACT_MATCH
MATCH_MODULO_THEORY
RESIDUAL_MATCH
NO_MATCH
INCONCLUSIVE
```

`PatternTargetedLocalBridgeSearch` untersucht anschließend einen eingefrorenen,
endlichen Bestand äquivalenzbewahrender Vorbereitungsregeln. Die Suche ist
zielblind bezüglich des Endergebnisses; ihr lokales Ziel ist ausschließlich die
Anwendbarkeit der Hauptregel.

Sie unterscheidet:

- direkt anwendbar;
- vorbereitet und konkret wiederholt;
- vollständige endliche Closure ohne Bridge;
- Budget-Inconclusive;
- Unsupported;
- ungültiges Zertifikat;
- technischen Fehler.

Ein positiver Pfad enthält jede primitive Vorbereitung und den abschließenden
Principal-Schritt. Ein Composite Move darf im Frontier als eine Kante behandelt
werden, sein mathematischer Arbeitsumfang bleibt jedoch vollständig sichtbar.

## Applicability-Schemata und Guards

Ein `RewriteApplicabilitySchema` bindet:

- Schema-ID;
- Applicability-Pattern;
- RecognitionProfile;
- typisierte Required-Assumption-Templates;
- konkreten Executor.

Das Schema besitzt kein alternatives Zielpattern. Nur der Executor erzeugt den
Ergebnis-AST.

Nichtnull-, Positivitäts-, Ganzzahligkeits- und weitere unterstützte Guards
werden aus vollständigen Bindings instanziiert. Fehlende oder unbekannte
Voraussetzungen autorisieren keinen Kandidaten. Ausgangsannahmen und durch
verifizierte Vorbereitungen erzeugte Annahmen werden gemeinsam geprüft.

## Retained Evidence

Ein vorbereiteter Zug behält mindestens:

```text
Principal-ID und Inventaridentität
Original-, Prepared- und Result-Repräsentation
AST-Position oder mathematische Objektposition
Matchbindungen und Residualbedingungen
Solver- oder Bridge-ID
konkrete primitive Vorbereitungsschritte
konkreten Principal-Schritt
Annahmen, Domains und Guard-Ausgang
Certificate oder unabhängigen Witness
konfiguriertes und verbrauchtes Work-Budget
Terminalstatus
Repository-Revision
```

Die spezialisierten Solver dokumentieren weitere fragmentbezogene Witnesses:

- [Exakter Polynomquotient und AC-Faktorexposition](#native-exact-spezialisten)
- [Gemeinsamer Monomfaktor](monomial-common-factor-preparation.md)
- [Exakte Quadratexposition](perfect-square-structure-preparation.md)
- [Gemeinsamer Nenner](rational-common-denominator-preparation.md)

## Gelernte Regeln

Eine gelernte Pattern-Regel kann den allgemeinen lokalen Bridge-Fallback nach
einer eigenständigen Promotion verwenden. Der erste implementierte Adapter
akzeptiert nur assumption-free Patternidentitäten, die im begrenzten
kommutativen Polynomfragment exakt bewiesen wurden.

Der charakterisierte Fall zeigt:

```text
promovierte Regel: A^2 - B^2 -> (A-B)*(A+B)
Eingabe:           ((x^2*a)/a) - y^2
Vorbereitung:      x^2 - y^2            unter a != 0
Ergebnis:          (x-y)*(x+y)
```

Rohe `CompiledGenomeRule`s bleiben nicht äquivalenzbewahrend und werden vom
sicheren Koordinator abgelehnt. Details:
[Promotion gelernter Pattern-Regeln](learned-pattern-rule-promotion.md).

## Repräsentationsbrücken

Skalare Gleichungssysteme können bereits exakt als `A*x=b`, unabhängige
Matrixblöcke, RREF-Lösungsräume und – bei expliziten Rollen – symbolische
Eigenprobleme dargestellt werden. Diese typisierten Brücken sind keine
skalaren AST-Rewrites und bleiben deshalb in einer eigenen
Repräsentationsschicht.

Die direkte Teilnahme solcher Objektbrücken am Unified Coordinator ist noch
offen. Sie darf nicht dadurch simuliert werden, dass Gleichungssysteme oder
Operatoren verlustbehaftet in einen einzelnen Ausdrucksstring gepresst werden.

## Aktivierung und historische Evidence

Die neuen Pfade verändern die mechanische Erreichbarkeit. Historische
Benchmarks behalten deshalb unverändert ihre damaligen Engine-, Regel- und
Budgetidentitäten.

Der Unified Coordinator ist implementiert und testbar, aber noch nicht als
allgemeiner Workbench-/CLI-Standard ausgewählt. Vor der Auswahl von
`SAFE_PREPARATION_V1` als Produktprofil fehlen noch:

- eine integrierte matched-work Charakterisierung gegen `DIRECT_V1`;
- eine geteilte Multi-Principal-Frontier und gemeinsame AST-/Value-Traversierung;
- eine Produktentscheidung über Default-Profile;
- die Integration typisierter Repräsentationsbrücken.

## Prüfung aus dem Checkout

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.transform.SafePreparationEngineRegistryTest

./gradlew :regelsuche-search:test \
  --tests de.regelsuche.search.reachability.UnifiedRulePreparationCoordinatorTest

./gradlew :regelsuche-experiments:symPyRuleAmplification

./gradlew --no-configuration-cache ciCheck
```

## Siehe auch

- [Sicherer Regelvorbereitungskoordinator](safe-rule-preparation-coordinator.md)
- [Search Intelligence](search-intelligence.md)
- [Architektur](architecture.md)
- [Discovery- und Forschungsstand](discovery-status.md)
- [Unterstützte Grenzen](limits.md)
