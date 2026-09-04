# Budgetierte exakte Theorieschritte in `RewriteProgram`

## Zweck

Ein vom endlichen Polynomsolver bestätigter Kandidat ist keine Folge gewöhnlicher
AST-Regelanwendungen. Seine Ausführung darf deshalb weder eine erfundene Liste
primitiver Regeln erhalten noch als kostenlose Makrokante in die Suche gelangen.

Die Ausführung ist in klar getrennte Grenzen zerlegt:

```text
verifier-gebundene Evidence
  -> BudgetedTransformationSource
  -> BudgetedTransformationSourceExecutor
  -> RewriteProgram.BudgetedSource
  -> explizit budgetierter top-level Interpreter-Einstieg
```

## Typisierter Programmknoten

`RewriteProgram.BudgetedSource` bindet `NodeMetadata` und genau eine
`BudgetedTransformationSource`. Version 1 ist ausschließlich über diesen
Einstieg ausführbar:

```java
interpreter.executeBudgetedSource(
    budgetedSource,
    expression,
    availableMathematicalWorkUnits
);
```

Der Aufrufer muss die mathematische Arbeitsautorität explizit angeben. Es gibt
keinen unbegrenzten Standardwert.

## Eigenständiges Ergebnis

`BudgetedTransformationSourceProgramExecution` bewahrt:

- die vollständige Ausführung des budgetierten Source-Executors;
- den Programmknoten und dessen Quellposition;
- unveränderte Theorie-, Evidence- und Anwendungsidentitäten;
- die Annahmen und mathematische Arbeit jedes Kandidaten;
- null primitive Rewrite-Schritte und genau einen exakten Theorieschritt;
- getrennte delegierte Source- und zusätzliche Programmechanik;
- `CANDIDATES`, `NO_MATCH` und `BUDGET_INCONCLUSIVE` ohne Zustandskollaps;
- eine kanonische, content-addressed Ausführungsidentität.

Das Ergebnis bietet absichtlich keine Konvertierung zu `Transformation` oder
`RewriteExecution`.

## Fail-closed unbudgetierte Grenze

Der gewöhnliche Interpreter-Einstieg

```java
interpreter.execute(program, expression)
```

prüft den vollständigen Programmbaum rein strukturell vor. Enthält er irgendwo
einen `BudgetedSource`, wird er abgelehnt, bevor eine gewöhnliche
`TransformationEngine` oder die budgetierte Source aufgerufen wird.

Dadurch werden verhindert:

- ein implizit unbegrenztes mathematisches Budget;
- Budget-Reset in verschachtelten Knoten;
- partielle Seiteneffekte vor später Ablehnung;
- die Umdeutung eines exakten Theorieschritts in eine primitive Makrokante.

`ProgrammedTransformationEngine` bleibt damit vorerst der gewöhnlichen
Transformationsebene vorbehalten.

## Getrennte Arbeit

Die Programmechanik zählt:

```text
Interpreter-Aufruf
+ besuchter top-level Programmknoten
+ Delegation an den budgetierten Executor
+ Projektion jedes exakten Theoriekandidaten
+ vollständige delegierte Executor-Mechanik
= totalMechanicalWorkUnits
```

Die mathematische Arbeit verbleibt auf jedem `ExactTheoryTransition` und wird
nicht in diese mechanische Summe eingerechnet.

## Claim-Grenze

Diese Stufe etabliert isolierte top-level Ausführung eines verifizierten exakten
Theorieschritts. Sie etabliert noch nicht:

- Budgetpropagation durch `Choice`, `FirstApplicable`, `Sequence` oder `Repeat`;
- gewöhnliche Search-Frontier-Integration;
- primitive Rewrite-Provenienz;
- formale Proof-Evidence;
- gelernte Programmautorisierung;
- Promotion oder mathematische Neuheit.

Der nächste Slice muss mathematische Pfadautorität durch Kompositionsknoten
tragen. Alternativen erhalten dasselbe eingehende Budget; Fortsetzungen und
Wiederholungen nur den nach der konkreten Präfixarbeit verbleibenden Rest.
Mechanische Explorationsarbeit bleibt separat.

## Reproduktion

```bash
./gradlew :regelsuche-search:test \
  --tests '*BudgetedTransformationSourceExecutorTest' \
  --tests '*BudgetedTransformationSourceRewriteProgramTest'

./gradlew :regelsuche-learning:test \
  --tests '*VerifiedFinitePolynomialCandidateSourceTest'

./gradlew --no-configuration-cache ciCheck
```
