# Polynomstrategien aus Trainingsaufgaben auswählen

## Was sich gegenüber einer handgeschriebenen Schrittfolge ändert

`FinitePolynomialStrategySearch` im Learning-Modul erzeugt alle geradlinigen
Ansatzfolgen innerhalb einer vorab festgelegten endlichen Grammatik. Jede Folge
wird auf allen TRAIN-Eingaben mit demselben Budget ausgeführt. Erst danach wird
eine Folge ausgewählt und unveränderlich eingefroren. Auf einer späteren Eingabe
werden ihre Koeffizienten neu bestimmt; die Auswahl wird nicht erneut trainiert.

Das ist eine begrenzte, datenabhängige Strategieauswahl. Die Ansatzformen, das
Suchziel und die Rangfolge der Bewertungskriterien sind weiterhin vorgegeben.
Der Baustein ist keine neue evolutionäre Population, keine zweite Ausdruckssuche
und kein Nachweis, dass die Grammatik oder eine verzweigende Taktik gelernt wurde.
Er ergänzt die [budgetierte Programmkomposition](budgeted-rewrite-program-composition.md)
und verwendet deren bestehenden Interpreter.

## Ausführbares Java-Beispiel

```java
import de.regelsuche.evolution.FinitePolynomialStrategySearch;
import de.regelsuche.evolution.FinitePolynomialStrategySearch.*;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import java.util.List;

var completion = new Template("completion", "(@v+${shift})^2+${constant}",
    List.of(HoleDomain.integerRange("shift", -4, 4),
            HoleDomain.integerRange("constant", -6, 6)));
var factors = new Template("factors", "(@v+${left})*(@v+${right})",
    List.of(HoleDomain.integerRange("left", -6, 6),
            HoleDomain.integerRange("right", -6, 6)));
var grammar = new Grammar(List.of(completion, factors), 2, 2000, 1000);
var search = new FinitePolynomialStrategySearch();
var selection = search.train(grammar, List.of(
    new TrainingInput("train-one", "x^2+6*x+5"),
    new TrainingInput("train-two", "x^2-2*x-3"),
    new TrainingInput("train-negative", "x^2+1")));

System.out.println(selection.selectedSequence());
var application = search.apply(selection, "y^2+y-6");
System.out.println(application.trial().outcome());
System.out.println(application.trial().execution().orElseThrow()
    .candidates().getFirst().transformedExpression());
```

`@v` ist ausschließlich ein Platzhalter für den Namen der einzelnen,
parsergebundenen Polynomvariablen. `${...}` bleiben die vorhandenen endlichen
Koeffizientenlücken des Solvers. Der ausgewählte Ansatz ist dadurch nicht an den
Trainingsbuchstaben `x` oder an die dort gefundenen Koeffizienten gebunden.

Die Zahlen in `Grammar` sind maximale Folgenlänge, Gesamtzahl der
Koeffizientenprüfungen pro Versuch und maximales verifizierergebundenes
Theoriepfadbudget. Ein Versuch darf diese Budgets zwischen seinen Schritten
nicht neu beginnen.

## Entscheidung aus vollständigen TRAIN-Zeilen

Bei zwei Vorlagen und Folgenlänge zwei werden sechs Folgen erzeugt: jede Vorlage
allein sowie alle vier geordneten Zweierfolgen einschließlich Wiederholung.
Mit drei Eingaben entstehen 18 Ergebniszeilen. Ein früher Abbruch bleibt als
Zeile erhalten; spätere Schritte werden dann nicht als ausgeführt ausgegeben.

Die festgelegte Bewertung bevorzugt mehr erfolgreiche TRAIN-Aufgaben, danach
weniger unentschiedene Aufgaben, weniger ausgeführte Koeffizientenprüfungen,
weniger aufbewahrte Pfadarbeit und kürzere Folgen. Stabile Vorlagen-IDs lösen
verbleibende Gleichstände. Gibt es keinen einzigen Erfolg, entsteht ausdrücklich
keine verwendbare Auswahl. Ein billiger erfolgloser Versuch kann nicht gewinnen.

Der Entwicklungsfall wählt die direkte Faktorisierung. Auf den beiden positiven
TRAIN-Aufgaben erreicht sie dasselbe Faktorisierungsziel wie die Folge
„quadratisch ergänzen, dann faktorisieren“. Pro positiver Aufgabe führt sie
507 statt 858 Koeffizientenprüfungen einschließlich der beiden Wiederholungen
aus. Über alle sechs Folgen und drei Eingaben benötigt die Auswahl selbst
12.012 solche Prüfungen. Die Kosten des Lernversuchs werden nicht verschwiegen.

Das Resultat ist bewusst kein erzwungener Mehrschritterfolg. Der bestehende
exakte Koeffizientensolver erkennt Gleichheit unabhängig davon, ob die Quelle
vorher quadratisch ergänzt wurde. Zusätzliche Vorbereitung kann hier daher
überflüssig sein. Ein zweiter Test zeigt mit nichtmonischen Polynomen, dass bei
anderen Trainingsdaten tatsächlich eine andere Vorlage ausgewählt wird.

## Mathematische Prüfung und Wiederverwendung

Jeder ausgeführte Ansatz durchläuft die tatsächliche vorhandene Pipeline:

```text
Planbildung und vollständige endliche Koeffizientensuche
  -> vollständiger Replay und Receipt
  -> getrennte Prüfung der kanonischen Receipt-/Planlaufbytes
  -> vollständige Replay-Confirmation
  -> explizit ausgewählte verifier-eigene Kandidatenevidence
  -> VerifiedFinitePolynomialCandidateSource
  -> vorhandener budgetierter RewriteProgram-Interpreter
```

Die Kandidatenwahl verwendet den kleinsten aufbewahrten Kandidatenhash, nicht
eine gewünschte Zieldarstellung. Ergibt dieser Kandidat keine Änderung, bleibt
das als `NO_CHANGE` sichtbar; es wird nicht nach einem günstigeren Ersatz
weitergesucht. Ein leerer Solversatz wird unabhängig erneut ausgeführt und bleibt
`COMPLETE_NO_SOLUTION` für genau diesen endlichen Ansatzbereich.

Das Ziel verlangt eine Multiplikation an der Ausdruckswurzel mit zwei exakt
nichtkonstanten Polynomfaktoren. Ein Faktor `1` oder `x-x+1` genügt nicht. Bereits
faktorisiert eingegebene Quellen bekommen `ALREADY_SATISFIED` statt eines
Wiederentdeckungserfolgs bei Tiefe null. Kandidaten mit beschränkt aufbewahrtem
Solversatz behalten den entsprechenden Status in ihrer Plan-Evidence.

`apply` erhält ausschließlich die eingefrorene Auswahl und die neue Eingabe.
Es löst deren Koeffizienten neu und erzeugt neue source-gebundene Evidence.
TRAIN-Duplikate werden anhand exakter Koeffizienten und Exponenten abgewiesen,
auch bei anderer Schreibweise oder umbenannter Variable. Benachbarte große
Ganzzahlen dürfen dabei nicht über `double` zusammenfallen. Ein späterer
Fehlschlag verändert weder Auswahl noch Trainingsbericht.

## Grenzen und Arbeitsbilanz

Die Grammatik ist auf vier Vorlagen, drei Schritte, 256 Belegungen je Vorlage,
16 TRAIN-Eingaben und 512 komplette Versuchszeilen begrenzt. Eingaben werden vor
der Suche hinsichtlich Größe, exakter univariater Unterstützung und Duplikaten
geprüft. Ungültige oder nicht unterstützte Konfigurationen brechen ausdrücklich
ab; technische Exceptions werden nicht in vorteilhafte negative Fitnesswerte
umgedeutet.

Vor jedem Ansatz werden alle drei realen Solverdurchläufe gegen das verbleibende
Belegungsbudget zugelassen. Generation, Receipt-Replay und Confirmation-Replay
werden gezählt. Das Theoriepfadbudget bleibt hiervon verschieden: Es übernimmt
die Arbeit aus der ausgewählten `VerifiedCandidateEvidence`. Die vorhandene
Programmmechanik und die exakte Eingabe-/Zielprüfung bleiben separat sichtbar.

Diese Zähler sind keine vollständige Ende-zu-Ende-Kostenrechnung. Insbesondere
machen sie keine Aussage über sämtliche Parser-, Serialisierungs-,
BigInteger- oder nativen Operationen, Speicherbedarf oder Laufzeit. Daraus wird
kein allgemeiner Geschwindigkeitsvorteil und keine optimale mathematische
Strategie abgeleitet.

Die Grammatik-, TRAIN-, Versuch- und Auswahlidentitäten sind kanonisch gebunden.
Vollständige Plan-, Run-, Receipt-, Confirmation- und Kandidatenobjekte sind in
den Versuchen verfügbar. Die JSON-Berichte sind Entwicklungsdiagnostik; sie
ersetzen keine unabhängige Artefakt-Importprüfung oder produktive Promotion.

## Reproduktion und offene Forschungsstufe

```bash
./gradlew :regelsuche-learning:test --tests '*FinitePolynomialStrategySearchTest'
./gradlew --no-configuration-cache ciCheck
```

Der Test schreibt `grammar.json`, `selection.json` und sämtliche `trial-*.json`
unter `build/reports/finite-polynomial-strategy-selection` des Testarbeitsordners.
Die Ergebnisse sind an die konkreten ausgeführten Tests und deren Commit
gebunden. Lokal ausgeführte Teilprüfungen ersetzen keine vollständige CI.

Die späteren Beispiele mit neuen Koeffizienten sind Entwicklungs- und
Regressionstests derselben algebraischen Familie. Sie sind kein versiegelter,
präregistrierter FINAL TEST und keine nachgewiesene familienfremde Übertragung.
#874 behält das eigentliche Lernen von Strukturwahl, Resttermstrategie,
Verzweigungen und neuen Taktiken. #750/#235 behalten die vollständige Studie mit
unabhängigen Kontrollen und gemeinsamer Arbeitsbilanz. Bestehende historische
Ergebnisse, Release-Artefakte, Inventare und FINAL-TEST-Material bleiben unverändert.
