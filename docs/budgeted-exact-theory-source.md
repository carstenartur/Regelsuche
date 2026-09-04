# Budgetierte Source für exakte Theorieschritte

## Motivation

Ein durch den endlichen Polynomsolver bestätigter Kandidat ist keine Folge
gewöhnlicher AST-Regelanwendungen. Seine Ausführung darf deshalb weder eine
fiktive `primitiveRuleIds`-Liste erhalten noch als kostenlose Makrokante in die
Suche gelangen.

Die eigenständige Source-Grenze lautet:

```text
explizite mathematische Arbeitsautorität
+ eine identifizierte budgetierte Source
  -> CANDIDATES
  -> NO_MATCH
  -> BUDGET_INCONCLUSIVE
```

Die Source kann zusätzlich als expliziter top-level
`RewriteProgram.BudgetedSource` ausgeführt werden. Zusammengesetzte
Programmknoten und eine gewöhnliche Search Frontier bleiben bis zur
Pfadbudget-Propagation ausgeschlossen.

## `BudgetedTransformationSource`

Die Source besitzt eine vor und nach jedem Aufruf geprüfte `SourceIdentity`:

```text
sourceId
revisionHash
authorityHash
```

`authorityHash` beschreibt die konkrete vorgelagerte Autorität. Das generische
Protokoll macht eine beliebige Implementierung dadurch noch nicht
vertrauenswürdig. Ein konkreter Adapter muss den Hash an unabhängig geprüfte
Evidence binden.

Ein Kandidat ist ein `ExactTheoryTransition` mit:

- Quell- und Ergebnisdarstellung;
- benanntem Theorieschritt;
- Evidence-Hash;
- Annahmen;
- positiver mathematischer Arbeit;
- Anwendungsschlüssel;
- kanonischem Content-Hash.

Er enthält absichtlich keine primitive Regel-ID und ist kein
`Transformation`-Objekt.

## Terminale Zustände

`Result` hält drei Zustände auseinander:

```text
CANDIDATES
  mindestens ein Kandidat
  jeder Kandidat passt zur Quelle
  jeder Kandidat liegt innerhalb des angebotenen Budgets

NO_MATCH
  vollständiges kandidatfreies Resultat
  keine Aussage über zu wenig Budget

BUDGET_INCONCLUSIVE
  kandidatfreies unvollständiges Resultat
  minimumRequiredMathematicalWorkUnits > angebotene Arbeit
```

Kandidaten werden über ihren Content-Hash kanonisch geordnet und müssen
eindeutig sein. Resultat und Ausführung besitzen eigene Content-Hashes.

## Getrennte Arbeit

`BudgetedTransformationSourceExecutor` trennt mathematische Arbeit von
mechanischer Ausführung:

```text
mathematische Arbeit
  auf jedem ExactTheoryTransition

mechanische Arbeit
  Executor-Aufruf
  zwei Source-Identity-Lesungen
  Source-Aufruf
  beobachtete Kandidaten
  Source-interne Mechanik
```

Die Source-Identität wird vor und nach dem Aufruf gelesen. Ein zustandsbehafteter
Adapter kann daher nicht zunächst eine zulässige Autorität melden und danach
unter einer anderen Identität Kandidaten ausgeben.

## Finite-Plan-Adapter

`VerifiedFinitePolynomialCandidateSource` akzeptiert ausschließlich die
versiegelte `VerifiedCandidateEvidence` aus dem gemergten Kandidaten-Verifier.
Eine Source-Instanz ist genau an diesen bereits explizit ausgewählten Kandidaten
gebunden:

```text
authorityHash = evidenceHash
mathematicalWorkUnits = evidence.canonicalWork.totalWorkUnits
```

Der Adapter führt keine erneute Kandidatenauswahl durch. Bei einer anderen
Quelle liefert er `NO_MATCH`. Reicht die angebotene mathematische Arbeit nicht
für die vollständige gebundene Evidence-Arbeit, liefert er
`BUDGET_INCONCLUSIVE`. Nur bei ausreichender Autorität wird genau der bereits
bestätigte Ergebnisausdruck ausgegeben.

Die Source-Mechanik zählt getrennt:

- einen Quellvergleich;
- bei passender Quelle einen Budgetvergleich;
- bei Erfolg eine Kandidatenmaterialisierung.

## Explizite Programmausführung

Der Programmknoten

```text
RewriteProgram.BudgetedSource
```

bindet gewöhnliche `NodeMetadata` an genau eine
`BudgetedTransformationSource`. Er wird nur über den explizit budgetierten
Einstieg ausgeführt:

```java
interpreter.executeBudgetedSource(
    program,
    expression,
    availableMathematicalWorkUnits
);
```

Das Ergebnis ist ein `BudgetedTransformationSourceProgramExecution`, kein
gewöhnliches `RewriteExecution` und keine Liste von `Transformation`-Objekten.
Jeder Kandidat bewahrt den vollständigen `ExactTheoryTransition` sowie seine
Evidence- und Anwendungsidentitäten. Die Schrittzahlen bleiben ausdrücklich
getrennt:

```text
primitiveRewriteSteps = 0
exactTheorySteps = 1
```

Die mathematische Arbeit bleibt auf dem Kandidaten. `ProgramWork` hält davon
getrennt den Interpreter-Aufruf, den besuchten Programmknoten, die Delegation an
den Source-Executor, die Kandidatenprojektionen und die vollständige delegierte
Mechanik fest. Öffentliche Record-Rekonstruktionen, inkonsistente Summen und
Überläufe schlagen geschlossen fehl.

Der gewöhnliche unbudgetierte Interpreter prüft den vollständigen Programmbaum,
bevor er eine gewöhnliche oder budgetierte Source aufruft. Jeder enthaltene
`BudgetedSource`-Knoten wird abgelehnt. Dadurch kann weder ein direkter noch ein
hinter `Choice`, `FirstApplicable`, `Sequence`, `Repeat`, `Require`,
`Prioritize` oder `Prune` verborgener Theorieschritt ohne explizite
Arbeitsautorität ausgeführt werden.

Die vollständige Programmknoten- und Arbeitssemantik ist in
`docs/budgeted-rewrite-program-source.md` beschrieben.

## Claim-Grenze

Diese Stufe etabliert einen ehrlichen, budgetierten Transport eines verifizierten
exakten Theorieschritts und seine explizite top-level Einbindung in die
`RewriteProgram`-IR. Sie etabliert noch nicht:

- Budgetpropagation durch `Choice`, `FirstApplicable`, `Sequence` oder `Repeat`;
- gewöhnliche Search-Frontier-Integration;
- primitive Rewrite-Provenienz für einen exakten Theorieschritt;
- formale Proof-Evidence;
- gelernte Programmautorisierung;
- Promotion oder mathematische Neuheit.

Der nächste Slice muss das mathematische Pfadbudget ohne Reset durch die
Kompositionsknoten tragen. Alternativen sehen dasselbe eingehende Budget;
Fortsetzungen und Wiederholungen sehen nur den nach der jeweiligen
Präfixarbeit verbleibenden Rest. Mechanische Explorationsarbeit bleibt davon
getrennt.

## Reproduktion

```bash
./gradlew :regelsuche-search:test \
  --tests '*BudgetedTransformationSourceExecutorTest' \
  --tests '*BudgetedTransformationSourceRewriteProgramTest'

./gradlew :regelsuche-learning:test \
  --tests '*VerifiedFinitePolynomialCandidateSourceTest'

./gradlew --no-configuration-cache ciCheck
```
