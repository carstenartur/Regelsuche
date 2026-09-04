# Budgetierte Source für exakte Theorieschritte

## Motivation

Ein durch den endlichen Polynomsolver bestätigter Kandidat ist keine Folge
gewöhnlicher AST-Regelanwendungen. Seine Ausführung darf deshalb weder eine
fiktive `primitiveRuleIds`-Liste erhalten noch als kostenlose Makrokante in die
Suche gelangen.

Dieser Slice führt zunächst eine eigenständige Source-Grenze ein:

```text
explizite mathematische Arbeitsautorität
+ eine identifizierte budgetierte Source
  -> CANDIDATES
  -> NO_MATCH
  -> BUDGET_INCONCLUSIVE
```

Er integriert die Source noch nicht in zusammengesetzte `RewriteProgram`-Knoten
oder eine gewöhnliche Search Frontier.

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

## Claim-Grenze

Diese Stufe etabliert einen ehrlichen, budgetierten Transport eines verifizierten
exakten Theorieschritts. Sie etabliert noch nicht:

- einen `RewriteProgram.BudgetedSource`-Knoten;
- Budgetpropagation durch `Choice`, `Sequence` oder `Repeat`;
- gewöhnliche Search-Frontier-Integration;
- primitive Rewrite-Provenienz;
- formale Proof-Evidence;
- Promotion oder mathematische Neuheit.

Der nächste Slice darf die Source erst dann in `RewriteProgram` integrieren,
wenn der Interpreter das mathematische Pfadbudget ohne Reset durch die
Kompositionsknoten trägt. Mechanische Explorationsarbeit bleibt davon getrennt.

## Reproduktion

```bash
./gradlew :regelsuche-search:test \
  --tests '*BudgetedTransformationSourceExecutorTest'

./gradlew :regelsuche-learning:test \
  --tests '*VerifiedFinitePolynomialCandidateSourceTest'

./gradlew --no-configuration-cache ciCheck
```
