# Budgetierte exakte Theorieschritte in `RewriteProgram`

## Zweck

Ein vom endlichen Polynomsolver bestätigter Kandidat ist keine Folge gewöhnlicher
AST-Regelanwendungen. Seine Ausführung darf deshalb weder eine erfundene Liste
primitiver Regeln erhalten noch als kostenlose Makrokante in die Suche gelangen.

```text
verifier-gebundene Evidence
  -> BudgetedTransformationSource
  -> BudgetedTransformationSourceExecutor
  -> RewriteProgram.BudgetedSource
  -> explizit budgetierter Interpreter-Einstieg
```

## Ein einzelner Source-Knoten

Der bestehende isolierte Einstieg lautet:

```java
interpreter.executeBudgetedSource(
    budgetedSource,
    expression,
    availableMathematicalWorkUnits
);
```

Er akzeptiert einen einzelnen `RewriteProgram.BudgetedSource`, keine
Kompositionsknoten. Der Aufrufer gibt die mathematische Arbeitsautorität
explizit an. Es gibt keinen unbegrenzten Standardwert.

`BudgetedTransformationSourceProgramExecution` bewahrt die vollständige
Source-Ausführung, Node-Metadaten und Quellposition, Theorie-, Evidence- und
Anwendungsidentitäten, Annahmen sowie die mathematische Arbeit jedes Kandidaten.
Der Kandidat besitzt null primitive Rewrite-Schritte und genau einen exakten
Theorieschritt. Das Ergebnis unterscheidet `CANDIDATES`, `NO_MATCH` und
`BUDGET_INCONCLUSIVE` und besitzt eine kanonische Ausführungsidentität.

Die mechanische Arbeitsbilanz zählt Interpreter-Aufruf, Knotenbesuch,
Executor-Delegation, Projektion jedes Kandidaten und die vollständige delegierte
Executor-Mechanik. Die mathematische Arbeit verbleibt separat auf jedem
`ExactTheoryTransition`.

## Komposition unter expliziter Pfadautorität

Für `Choice`, `FirstApplicable`, `Sequence`, `Repeat` und `Prune` existiert der
separate Einstieg `RewriteProgramInterpreter.executeBudgeted` mit zwei
mathematischen Budgetdimensionen und expliziten Explorationsgrenzen. Er
verwendet denselben Source-Executor und liefert typisierte exakte Theoriepfade,
keine gewöhnlichen Transformationen.

[Budgettreue Komposition](budgeted-rewrite-program-composition.md) beschreibt
Preflight, vollständige Programmidentität, Restbudgetpropagation, die vier
vollständig/unvollständig × mit/ohne Kandidaten-Zustände und die getrennten
mechanischen Arbeitszähler. Dieser Entwicklungsschritt gehört nicht rückwirkend
zum isolierten Einstieg des Releases 0.4.0.

## Unbudgetierte Grenze

Der gewöhnliche Aufruf `interpreter.execute(program, expression)` prüft weiterhin
den vollständigen Programmbaum. Enthält dieser irgendwo einen `BudgetedSource`,
wird er abgewiesen, bevor eine gewöhnliche `TransformationEngine` oder eine
budgetierte Source ausgeführt wird. `ProgrammedTransformationEngine` bleibt
auf gewöhnliche Transformationen beschränkt.

Weder der isolierte noch der komponierte Einstieg konvertiert das Ergebnis zu
`Transformation` oder `RewriteExecution`. Die Source-Protokollprüfung allein
beweist keine mathematische Wahrheit; konkrete Adapter müssen ihre Autorität an
unabhängig überprüfte Evidenz binden.

## Reproduktion und verbleibende Grenze

```bash
./gradlew :regelsuche-search:test \
  --tests '*BudgetedTransformationSourceExecutorTest' \
  --tests '*BudgetedTransformationSourceRewriteProgramTest' \
  --tests '*BudgetedRewriteProgramCompositionTest'

./gradlew :regelsuche-learning:test \
  --tests '*VerifiedFinitePolynomialCandidateSourceTest'

./gradlew --no-configuration-cache ciCheck
```

Gewöhnliche Search-Frontier-Integration, gemischte primitive/Theorie-Pfade,
gelernte Programmautorisierung, formale Proof-Evidence, Promotion und
mathematische Neuheit bleiben eigenständige offene Aufgaben.
