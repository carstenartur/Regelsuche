# Budgettreue Komposition exakter Theorieschritte

## Zweck und Einstieg

`RewriteProgramInterpreter.executeBudgeted` führt den exakten Theoriepfad aus
#906 durch die vorhandenen `RewriteProgram`-Knoten. Der package-private Evaluator
ist ein typisierter Interpreterpfad, keine zweite Ausdruckssuche. Er verwendet
den bestehenden `BudgetedTransformationSourceExecutor` für jede Source.

```java
var run = new RewriteProgramInterpreter().executeBudgeted(
    program,
    "x",
    new BudgetedRewriteProgramExecution.PathBudget(0, 12),
    new BudgetedRewriteProgramExecution.ExplorationLimits(1000, 1000, 32)
);
```

Das Beispiel setzt ein bereits aufgebautes `program` voraus. Die beiden
mathematischen Budgets bedeuten primitive Rewrite-Einheiten und exakte
Theoriearbeit. Dieser erste Kompositionspfad verbraucht ausschließlich die
zweite Dimension. Er erzeugt weder `Transformation` noch `RewriteExecution`.

Explorationsgrenzen beschränken besuchte Knoten, Pfadverlängerungen und Schritte
pro Pfad. Sie sind explizite Eingaben, keine versteckten unbegrenzten Defaults.
Sie ersetzen weder die internen Grenzen einer Source noch einen Watchdog für
beliebigen Java-Code. Der strukturelle Preflight begrenzt Programme zusätzlich
auf 4096 Knoten und eine Tiefe von 128.

## Semantik

| Knoten | Verhalten |
| --- | --- |
| `BudgetedSource` | Erhält exakt den Rest nach dem konkreten Präfix; Ergebnis und eingefrorene Source-Identität müssen übereinstimmen. |
| `Choice` | Jede Alternative erhält dasselbe eingehende Präfixbudget. Die Explorationsarbeit sämtlicher ausgewerteter Alternativen wird gezählt. |
| `FirstApplicable` | Überspringt ausschließlich vollständige leere Ergebnisse. Ein unvollständiges leeres Ergebnis sperrt spätere Alternativen; ein unvollständiges nichtleeres Ergebnis bleibt unvollständig. |
| `Sequence` | Setzt jeden konkreten Präfix mit dessen Restbudget fort. Eine erfolgreiche andere Verzweigung löscht Budgetabbrüche nicht. |
| `Repeat` | Behält alle Endpunkte zwischen Minimum und Maximum samt vollständiger Präfixarbeit. Rückkehr zum gleichen Ausdruck setzt keine Arbeit zurück. |
| `Prune` | Behält die ersten Kandidaten in deklarierter Programm-/Source-Reihenfolge; tatsächliche Kürzung macht das Ergebnis unvollständig. Entfernte Pfade bleiben in separater Pruning-Evidence erhalten. |

Bei Kosten 7 und 5 gelingt eine Sequenz mit Budget 12. Bei Budget 11 erhält
die zweite Source nur 4 Einheiten und meldet `BUDGET_INCONCLUSIVE`. Der
Interpreter behält Node-ID, Präfix, Eingabe, angebotenes und benötigtes Budget
sowie die vollständige Source-Ausführung. Er macht daraus keinen vollständigen
Fehlschlag.

Zyklen werden nicht allein anhand gleicher Ausdruckstexte entfernt. Vollständige
Pfade bewahren Annahmen, Source- und Anwendungsidentitäten, Evidenz und positive
Arbeit. Die expliziten Grenzen beenden die Exploration; sie rechtfertigen keine
allgemeine Unerreichbarkeitsaussage. Auch die Rückkehr zur ursprünglichen
Darstellung ist hier nur ein dokumentierter kostenpflichtiger Pfad, kein neuer
mathematischer Gewinn.

## Preflight und Identitäten

Ein rein struktureller Durchlauf lehnt gewöhnliche `Source`, `Require` und
`Prioritize` ab, bevor irgendeine Source- oder Lambda-Funktion aufgerufen wird.
Strukturelle Node-IDs müssen eindeutig sein; die dynamische Wiederverwendung
innerhalb eines `Repeat` ist zulässig.

Anschließend werden sämtliche Source-Identitäten eingefroren. Der Interpreter
prüft sie vor und nach konkreten Aufrufen sowie vor und nach der gesamten
Ausführung. Das umfasst nicht ausgewählte Alternativen. Eine zwischenzeitlich
beobachtete andere Revision, Autorität oder Resultatidentität führt zu einem
technischen Fehler, nicht zu einem mathematischen Suchresultat. Die Prüfung ist
keine kryptografische Attestierung beliebigen Erweiterungscodes.

Die kanonische Programmrepräsentation bindet geordnete Topologie, sämtliche
Node-Metadaten und Quellpositionen, Source-Identitäten, Protokollrevisionen,
Wiederholungsgrenzen und Pruning-Regeln. Die Ausführungsidentität bindet zusätzlich
Eingabe, beide Budgets, Explorationsgrenzen, alle Source-Ausführungen, Kandidaten,
Abbrüche, entfernte Pfade und die vollständige Arbeitsbilanz. Listen sind
gezählt, Felder UTF-8-längenpräfixiert; fehlerhafte Unicode-Surrogate werden
abgewiesen. Öffentliche Ergebnislisten und Arbeitsbilanzen sind unveränderlich.

## Ergebnisse und Arbeitsbilanz

Die vier Zustände bleiben getrennt:

```text
COMPLETE_WITH_CANDIDATES
COMPLETE_WITHOUT_CANDIDATES
INCOMPLETE_WITH_CANDIDATES
INCOMPLETE_WITHOUT_CANDIDATES
```

`COMPLETE` bezieht sich ausschließlich auf das deklarierte endliche Programm
und die Resultate seiner Sources, nicht auf den gesamten Umformungsraum.

Die mathematische Arbeit steht auf jedem einzelnen Pfad. Die getrennte
mechanische Bilanz zählt Interpreter-/Evaluatoraufrufe, Preflight und
Knotenbesuche, zusätzliche Identitätsprüfungen, Source-Aufrufe und delegierte
Mechanik, Kandidatenprojektion und Pfadverlängerung, Komposition, Deduplizierung,
Wiederholungen und Endpunkte, ausgewertete/gewählte/übersprungene Alternativen,
Pruning sowie Budget- und Limitabbrüche. Summen verwenden `Math.addExact`;
Überlauf führt nicht zu einem kleineren oder gesättigten Erfolgswert.

Diese Einheiten sind deklarierte Operationen. Sie messen weder CPU-Instruktionen
noch versteckte native Solverarbeit. Die vollständige delegierte Mechanik wird
übernommen, nicht aus einer Kandidatenzahl geschätzt. Entfernte oder erfolglose
Alternativen verbrauchen Explorationsarbeit, aber nicht nachträglich die
mathematische Pfadautorität eines anderen Kandidaten.

## Reproduktion und Grenze

```bash
./gradlew :regelsuche-search:test \
  --tests '*BudgetedRewriteProgramCompositionTest' \
  --tests '*BudgetedTransformationSourceRewriteProgramTest' \
  --tests '*BudgetedTransformationSourceExecutorTest'

./gradlew --no-configuration-cache ciCheck
```

Die oben aufgeführten Search-Modultests verwenden kontrollierte Sources. Sie
prüfen Ausführungssemantik und Provenienzbindungen, nicht die Wahrheit
beliebiger Source-Behauptungen. Die folgende Integration ergänzt reale
verifier-gebundene Polynomquellen. Maßgeblich für die vollständige Qualifikation
bleibt die CI des konkreten Commits; lokale Teilprüfungen ersetzen sie nicht.

Der isolierte [Ein-Source-Einstieg](budgeted-rewrite-program-source.md) bleibt
bestehen. Gemischte primitive/Theorie-Pfade, gewöhnliche Search-Frontier-
Integration, Lernen einer Taktik, unabhängiger Beweis einer beliebigen Source,
Produktionspromotion und externe mathematische Neuheit sind nicht geliefert.
Die nachfolgende Strategie-Lernaufgabe bleibt in #874; die unabhängige
historische Studie bleibt in #750.

## Integration mit realen endlichen Polynomplänen

`VerifiedFinitePolynomialProgramCompositionTest` im Learning-Modul verbindet
nun alle bestehenden Grenzen, ohne mathematische Sources zu simulieren:

```text
festgelegter Ansatz und endliche Koeffizientendomänen
  -> ExactFinitePolynomialPlanResolver
  -> vollständiger Solverlauf und ReplayReceipt
  -> getrennte Prüfung kanonischer Receipt- und Planlaufbytes
  -> vollständige erneute Resolver-/Solver-Ausführung
  -> VerifiedCandidateEvidence für explizit ausgewählte Kandidaten
  -> VerifiedFinitePolynomialCandidateSource
  -> executeBudgeted mit Sequence / Choice / FirstApplicable / Repeat / Prune
```

Der Test verwendet für jeden Schritt neue Plan-, Byte-, Receipt-,
Replay-Confirmation- und Kandidatenprüfungen. Die Auswahl aus mehreren
bestätigten Kandidaten erfolgt in vorab festgelegter Content-Hash-Reihenfolge,
nicht durch Vergleich mit dem später geprüften Endausdruck. Die Neuausführung
verwendet die vorhandene exakte Solverimplementierung; dies ist kein Vergleich
mit einer algorithmisch unabhängigen zweiten CAS-Implementierung.

### Konkrete Zweischrittkette

Die beiden Ansätze und ihre Koeffizientenbereiche werden vor dem ersten Lauf
festgelegt:

```text
(x + ${shift})^2 + ${constant}
    shift = 0..4, constant = -5..1

(x + ${left}) * (x + ${right})
    left = 0..6, right = 0..6
```

Aus `x^2 + 6*x + 5` bestimmt der erste Lauf die quadratische Ergänzung. Dessen
verifizierte Ausgabe ist anschließend die exakte Eingabe des zweiten Plans.
In mathematischer Schreibweise ergibt sich:

```text
x^2 + 6*x + 5
  -> (x + 3)^2 - 4
  -> (x + 1) * (x + 5)
```

Der exakte Formatter schreibt den mittleren Ausdruck derzeit als
`(x + 3) ^ 2 + 0 - 4`. Die Integration übernimmt diese tatsächlichen Bytes
statt eine nur äquivalente Darstellung als dieselbe Source auszugeben. Die
vertauschte Faktorordnung ist ebenfalls zulässig. Erst nach der Ausführung
werden die Referenzdarstellungen geprüft.

Die Sources übernehmen ihre Kosten aus `VerifiedCandidateEvidence`. Bei
Gesamtbudget `W1 + W2` erhält die zweite Source genau `W2`; eine Einheit
weniger erzeugt einen sichtbaren Budgetblock. Dieser Budgetvertrag zählt die
verifier-gebundene kanonische Arbeit. Er ersetzt keine vollständige Messung
aller vorgelagerten Planbildungs-, Serialisierungs- und Validierungskosten und
begründet keinen Geschwindigkeitsvorteil.

### Negative und Wiederverwendungskontrollen

Die zehn Integrationstests prüfen zusätzlich Vorzeichenalternativen mit
getrennten Evidenzidentitäten, Pruning samt verworfener verifizierter Pfade,
Wiederholung mit echten Source-Matches und Restbudgets, falsch angeordnete
Sequenzen, blockiertes `FirstApplicable`, manipulierte Planlaufbytes und
reproduzierbare Programm-/Ergebnisidentitäten nach erneuter vollständiger
Vorbereitung.

Ein weiterer Fall behält nur einen von zwei passenden Solverkandidaten. Die
Ausführung der explizit gewählten Source kann vollständig sein, während die
zugehörige Plan-Evidence weiterhin `COMPLETE_RESOLUTION_SET_TRUNCATED` meldet.
Programmvollständigkeit darf diese eingeschränkte Lösungsaufbewahrung nicht in
eine vollständige Ergebnismenge umdeuten.

```bash
./gradlew :regelsuche-learning:test \
  --tests '*VerifiedFinitePolynomialCandidateSourceTest' \
  --tests '*VerifiedFinitePolynomialProgramCompositionTest'
```

Die Zweischrittkette und ihre Ansatzgrammatik sind handgeschriebene
Entwicklungsfälle. Die Integration belegt weder das Lernen dieser Strategie
noch eine historische Wiederentdeckungsstudie. Sie erweitert keine
Produktionsschnittstelle und verändert weder Inventar noch FINAL TEST.
