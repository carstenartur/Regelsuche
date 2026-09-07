# Polynomstrategien aus Trainingsaufgaben auswählen

## Was sich gegenüber einer handgeschriebenen Schrittfolge ändert

`FinitePolynomialStrategySearch` im Learning-Modul erzeugt alle geradlinigen
Ansatzfolgen innerhalb einer vorab festgelegten endlichen Grammatik. Jede Folge
wird auf allen TRAIN-Eingaben mit demselben Budget ausgeführt. Erst danach wird
eine Folge ausgewählt und unveränderlich eingefroren. Auf einer späteren Eingabe
werden ihre Koeffizienten neu bestimmt; die Auswahl wird nicht erneut trainiert.

Das ist eine begrenzte, datenabhängige Strategieauswahl. Die Ansatzformen werden
vor der Auswahl eingefroren; Suchziel und Rangfolge der Bewertungskriterien
bleiben vorgegeben. Vorlagen können deklariert oder aus verifizierten Spuren
abgeleitet sein.
Der Baustein ist keine neue evolutionäre Population, keine zweite Ausdruckssuche
und kein Nachweis, dass die Grammatik oder eine verzweigende Taktik gelernt wurde.
Er ergänzt die [budgetierte Programmkomposition](budgeted-rewrite-program-composition.md)
und verwendet deren bestehenden Interpreter.

## Gemeinsamer Vorlagenvertrag und getrennte Lernphasen

`FinitePolynomialTemplate` ist der gemeinsame unveränderliche Vertrag von
`ExactFinitePolynomialTraceLearner` und Selektor. Er bindet den Inhalt samt Hash,
den Variablenslot `@v`, die vollständigen endlichen Koeffizientendomänen sowie
Herkunft und Anwendbarkeit. Eine deklarierte Vorlage hat `DECLARED_GRAMMAR` und
`ANY_SUPPORTED_UNIVARIATE`. Nur ein vom Lerner ausgestellter `LearnedPlan` kann
`VERIFIED_TRACE_DERIVED` mit `EXACT_SOURCE_SHAPE` liefern. Diese Vorlage behält
Quellform, Formationshash und verifier-gebundene Provenienzwurzeln.

Der Ablauf ist: verifizierte Formationsspuren, Freeze ihrer Vorlagen, davon
getrennte Selektor-TRAIN-Matrix, Freeze der Auswahl, neue Anwendung. Nach
`learner.learn(verifiedTrainingTraces, limits)` können dessen `stages()` direkt
als Vorlagen einer `Grammar` übergeben werden. Es gibt keine zweite
Solver- oder Interpreterimplementierung für gelernte Vorlagen.

```java
var limits = new ExactFinitePolynomialTraceLearner.Limits(3, 8, 4, 4, 0, 12, 10_000);
var learned = new ExactFinitePolynomialTraceLearner().learn(verifiedTrainingTraces, limits);
var grammar = new FinitePolynomialStrategySearch.Grammar(learned.stages(), 2, 2000, 1000);
var search = new FinitePolynomialStrategySearch();
var selected = search.train(grammar, List.of(
    new FinitePolynomialStrategySearch.TrainingInput("select-one", "x^2+10*x+16"),
    new FinitePolynomialStrategySearch.TrainingInput("select-two", "y^2+12*y+35")));
var application = search.apply(selected, "u^2+14*u+45");
```

`verifiedTrainingTraces` bezeichnet hier die real geprüften Pfade des
[Lernerbeispiels](trace-derived-polynomial-plans.md). Die ausführbare gemeinsame
Fixture steht in `TraceDerivedPolynomialStrategyTest`. Aus drei Formationspfaden
entstehen zwei Vorlagen und zwölf Selektorzeilen. Deren Quellformvertrag führt
zur Folge Ergänzung/Faktorisierung; die Anwendung erreicht `(u+5)*(u+9)` mit
zwei erneut verifizierten Theorieschritten und null primitiven Regelschritten.
Die deklarierte Grammatik bleibt eine eigene Kontrolle, in der direkte
Faktorisierung gewinnt. Der Vergleich beweist keine Überlegenheit der gelernten
Grammatik; deren engere Anwendbarkeit ist Teil der Versuchskonfiguration.

Jede Vorlage behält getrennt die Identitäten ihrer Formationswurzeln und aller
in den geprüften Pfaden beobachteten Polynomzustände, einschließlich Zwischen-
und Endausdrücken. Die Vereinigung über **alle** Grammatikvorlagen ist für
Selektor-TRAIN und Anwendung gesperrt, auch wenn eine Vorlage nicht gewinnt.
Äquivalenzerhaltende Schritte können dieselbe Polynomidentität besitzen;
Beobachtungszahl und tatsächlich geleistete Projektionsarbeit bleiben erhalten.

Beide Lernphasen verwenden dieselbe variable-neutrale exakte Identität und
denselben begrenzten Polynomview: Grad 64, 4096 Koeffizientenbits, 256 Knoten,
50.000 Arithmetikoperationen. Eine erschöpfte Identitätsprüfung bricht mit
`BUDGET_INCONCLUSIVE` ab. Sie erlaubt weder einen Neuheitsanspruch noch einen
übersprungenen Ausschluss. Die Selektorrevision steigt deshalb auf v2; die
frühere eigene Identitätskodierung wird nicht weitergeführt.

Eine unpassende gelernte Quellform wird als `APPLICABILITY_MISMATCH` ohne
Solverlauf aufbewahrt. `COMPLETE_NO_SOLUTION` verlangt dagegen den tatsächlich
vollständigen endlichen Solverlauf. Jede Anwendbarkeitsprüfung bindet den vollen
Vorlagenhash, ihre Quelle und Identität sowie die besuchten Formknoten und die
exakte View-Arbeit. Die schon geprüfte Anfangsprojektion wird wiederverwendet
und einmal bei `inputViewWork` gezählt; spätere Stufen behalten zusätzliche
Projektionen in ihren Versuchen. Diese getrennten Zähler ändern die bestehende
Rangfolge nicht und sind keine vollständige Gesamtkostenmessung.

## Ausführbares Java-Beispiel

```java
import de.regelsuche.evolution.FinitePolynomialStrategySearch;
import de.regelsuche.evolution.FinitePolynomialTemplate;
import de.regelsuche.evolution.FinitePolynomialStrategySearch.*;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import java.util.List;

var completion = FinitePolynomialTemplate.declared("completion", "(@v+${shift})^2+${constant}",
    List.of(HoleDomain.integerRange("shift", -4, 4),
            HoleDomain.integerRange("constant", -6, 6)));
var factors = FinitePolynomialTemplate.declared("factors", "(@v+${left})*(@v+${right})",
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
./gradlew :regelsuche-learning:test --tests '*TraceDerivedPolynomialStrategyTest'
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
