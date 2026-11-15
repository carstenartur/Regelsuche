# Aus verifizierten Spuren gelernte Polynomplan-Vorlagen

## Zweck

`ExactFinitePolynomialTraceLearner` ergänzt #874 um einen begrenzten Lernschritt:
Aus mehreren tatsächlich ausgeführten, verifier-gebundenen linearen Pfaden
werden gemeinsame Schrittformen mit variablen Koeffizienten abgeleitet. Der
Lerner erhält keine Ansatztemplates der ursprünglichen Solverläufe und keine
späteren Testeingaben. Seine Eingaben sind vollständige Programmausführungen,
explizit ausgewählte Pfade und die versiegelte Kandidatenevidenz ihrer Schritte.

Dies folgt auf die [budgetierte Programmkomposition](budgeted-rewrite-program-composition.md).
Die bestehenden Parser-, Solver-, Replay-, Verifier- und Interpreterklassen
bleiben unverändert. Der Lernbaustein erstellt keine neue mathematische
Autorität und keine gewöhnliche Suchkante.

## Konkreter Entwicklungsfall

Drei Trainingspfade starten mit:

```text
x^2 + 6*x + 5
y^2 + 8*y + 12
u^2 + 8*u + 7
```

Jeder Pfad verwendet zunächst eine quadratische Ergänzung und anschließend eine
Faktorisierung. Die Erzeugung dieser Trainingspfade ist weiterhin durch
handgeschriebene Ansatzformen vorbereitet; die neue Komponente bekommt aber
nur die resultierenden geprüften Spuren.

Aus den verschiedenen Zahlen und Variablennamen gewinnt sie sinngemäß:

```text
v^2 + A*v + B
  -> (v + h)^2 - r
  -> (v + p)*(v + q)
```

`h`, `r`, `p` und `q` sind neu zu lösende Koeffizientenlücken, keine gespeicherten
Trainingswerte. Die tatsächliche erste Ausgabe erhält die vorhandene
Formatterstruktur `((v+h)^2 + 0) - r`. Die zweite Stufe prüft genau diese
Syntaxform; es gibt keinen impliziten Normalisierungs- oder AC-Schritt.

Nach dem Einfrieren der Vorlage prüft der Integrationstest unter anderem:

```text
z^2 + 10*z + 16 -> (z + 8)*(z + 2)
t^2 + 12*t + 35 -> (t + 7)*(t + 5)
```

Auf diesen Eingaben liefert der Lernbaustein nur Ansatzanfragen. Die Zahlen
werden erneut durch den bestehenden endlichen Solver bestimmt. Danach folgen
kanonische Artefaktprüfung, vollständige Replay-Bestätigung,
`VerifiedCandidateEvidence` und budgetierte Programmkomposition.

Die Faktorordnung darf sich unterscheiden. Historische Namen oder ein
vorgegebener fertiger Endausdruck entscheiden nicht über die Kandidatenauswahl;
der Test wählt nach der vorher festgelegten Content-Hash-Reihenfolge.

## Lernverfahren und Anwendbarkeit

Der erste Umfang ist bewusst eng: eine Variable, exakte ganzzahlige Literale,
Addition, Subtraktion, Multiplikation und literal gebundene Potenzen bis 32.
Divisionen, Funktionen, nichtganzzahlige Koeffizienten und mehrere Variablen
werden ausdrücklich abgewiesen. Die bestehende exakte Parserprovenienz liefert
die Zahlen; `NumberExpr.value()` ist keine Zahlenautorität dieses Lerners.

Die Reihenfolge und Zahl der Stufen müssen in allen ausgewählten Spuren gleich
sein. Variablennamen werden abstrahiert. Im Standardmodus müssen Quellen
dieselbe geordnete AST-Form haben; bei der Anwendbarkeitsprüfung variieren Zahlen
außer Exponenten. Der [separate Monomträger-Modus](#übertragung-auf-andere-eingabeformen)
nutzt die exakten nicht verschwindenden Grade für den Einstieg.
Die Ausgaben werden gleichzeitig über alle Trainingszeilen verallgemeinert:
Gleiche Literale bleiben erhalten, unterschiedliche ganzzahlige Literale werden
zu Koeffizientenlücken. Gleiche Wertespalten verwenden dieselbe Lücke, sodass
wiederholte Koeffizientenbeziehungen erhalten bleiben. Unterschiedliche
Operatorstrukturen oder Exponenten werden nicht durch erfundene Termlücken
übergangen. Eine Stufe ohne variierenden Koeffizienten wird abgewiesen.

Trainingsquellen werden zusätzlich durch die vorhandene
`ExactParsedUnivariatePolynomialView` exakt in Koeffizienten und Exponenten
überführt. Die variable-neutrale Polynomidentität verhindert, dass Umbenennung,
Summandenreihenfolge, Ausmultiplizieren, Faktorisierung oder sich aufhebende
Terme denselben mathematischen Input mehrfach als Lernbeleg zählen lassen.
Sie ist kein Nachweis unterschiedlicher mathematischer Familien.

Jede Wiederverwendungsstufe prüft die eingefrorenen Identitäten aller in der
Formation beobachteten Zustände vor der Syntaxauswahl, einschließlich Quellen
und Ausgaben jedes verifizierten Schritts. Eine dazu äquivalente Eingabe wird
ausdrücklich abgewiesen statt als neuer Holdout oder bloßer Shape-Miss gewertet.
Wurzelidentitäten und vollständige Zustandsmenge bleiben getrennt verfügbar.
Der API-Einstieg ist kein allgemeiner Studien- oder Promotionscontroller.

Die Identitätsprojektion besitzt ein festes Budget für Grad 64, 4096
Koeffizientenbits, 256 Knoten und 50.000 Arithmetikoperationen. Erschöpfung
bleibt ein expliziter `BUDGET_INCONCLUSIVE`-Fehler und wird nicht als Nachweis
unterschiedlicher Inputs akzeptiert. Revision, Budget, Trainingsidentitäten
und die tatsächlich gezählte Arbeit der Trainingsprojektionen werden in der
Vorlage gebunden. Dieser einzelne Zähler ist nicht die Gesamtarbeit des Lernens.

Die Policy bindet minimale/maximale Trainingszahl, Schrittlänge, Lückenzahl,
Koeffizientenintervall und maximales kartesisches Belegungsvolumen pro Stufe.
Sie muss vor dem Lernen gewählt werden. Die Intervallbreite ist höchstens 128;
mehr als 100.000 Belegungen pro Stufe sind ausgeschlossen. Zusätzliche feste
Syntaxgrenzen beschränken Zeichen, strukturelle Tokens und AST-Knoten. Die
Grenzen werden nicht nach einem Wiederverwendungsfehlschlag erweitert.

Unverändert gebliebene Konstanten können weiterhin überangepasst sein. Das
Verfahren garantiert weder die allgemeinste sinnvolle Vorlage noch die beste
Strategie. Gerade dafür sind spätere Kontrollen und familienfremde Aufgaben
nötig.

## Vertrauens- und Freeze-Grenze

Jeder Trainingsschritt wird gegen seine verifier-eigene Evidenz und den realen
`VerifiedFinitePolynomialCandidateSource`-Vertrag gebunden. Fremde Evidenz,
fehlende Pfade, unvollständige Programmausführungen und abgeschnittene
Solver-Ergebnismengen werden nicht als Trainingsbeleg akzeptiert.

`LearnedPlan` kann nur der Lerner ausstellen. Vorlagen, Policy, Trainingswurzeln
und abhängige Revisionen besitzen eine kanonische JSON-Darstellung samt Hash.
Die Trainingsreihenfolge ändert die Identität nicht. Die Darstellung trägt
`NON_EXECUTABLE_REQUIRES_FRESH_VERIFICATION`. Ein Import-/Autorisierungsloader
oder automatische Registrierung als Produktionsregel ist nicht enthalten.

`instantiate(stage, input)` liefert eine nicht autorisierende Anfrage für den
vorhandenen `ExactFinitePolynomialPlanResolver`. Eine passende Syntaxform ist
kein Beweis. `Optional.empty()` bedeutet nur Syntax-Mismatch; nicht unterstützte
Syntax und Ressourcenverletzungen bleiben explizite Fehler. Ein späterer
vollständiger Solverlauf ohne Belegung bedeutet nur keine Lösung im deklarierten
endlichen Ansatzraum, nicht allgemeine Unzerlegbarkeit.

Die vollständige Wiederverwendungskette steht im Integrationstest. Sie prüft
jede neu erzeugte Kandidatenevidenz erneut und führt erst danach die bewährte
budgetierte Sequenz aus. Mathematische Source-Klassen werden dort nicht
simuliert. Replay verwendet dieselbe exakte Solverimplementierung, nicht ein
algorithmisch unabhängiges zweites CAS.

## Reproduktion

Im vollständigen JDK-25-Checkout:

```bash
./gradlew :regelsuche-learning:test \
  --tests '*ExactFinitePolynomialTraceLearnerTest'

./gradlew --no-configuration-cache ciCheck
```

Die 23 Testmethoden decken Ableitung, zwei neue Koeffizienteninstanzen,
Lückenwiederverwendung, Reihenfolgeunabhängigkeit, manipulierte/fehlende Evidenz,
Duplikate, unvollständige Spuren, endliche Arbeitsgrenzen, negative Ergebnisse,
Form-/Domänengrenzen und große exakt unterscheidbare Ganzzahlen ab. Hinzu kommen
semantische Trainingsduplikate, als neue Eingabe verkleidete Trainingspolynome,
Identitätsbudgeterschöpfung und unzulässige Evidenzmengen.

## Was noch nicht gezeigt wird

Die Stufenfolge stammt aus den Trainingspfaden; weder deren Auswahl noch ihre
Erzeugung ist hier autonom gelernt. Der Lerner hebt erfolgreiche lineare
Spuren zu neuen parametrisierten Ansatzfolgen, entdeckt aber keine beliebigen
Verzweigungen, Schleifen, Operatoren oder Beweisideen. Die beiden neuen
Eingaben sind nicht zur Vorlagenbildung verwendet, gehören jedoch derselben
quadratischen Familie an und sind bekannte Entwicklungsfälle. Sie sind kein
präregistrierter, familienfremder FINAL TEST.

Es gibt keinen kontrollierten Geschwindigkeits-, Arbeits- oder
Überlegenheitsnachweis. Der Lern- und Vorbereitungspfad besitzt noch keine
vollständige einheitliche Arbeitsbilanz. Die Arbeit der nachgelagerten
budgetierten Sources ersetzt diese Messung nicht. Weder formale Beweise neuer
allgemeiner Sätze noch externe mathematische Neuheit oder Produktionspromotion
werden durch diese Änderung beansprucht.


## Verhältnis zur Strategieauswahl

Die [Strategieauswahl](finite-polynomial-strategy-selection.md) nimmt die
`FinitePolynomialTemplate`-Objekte aus `LearnedPlan.stages()` direkt an. Der
gemeinsame Vertrag verwendet `@v` als Variablenslot und bindet die vollständigen
Koeffizientendomänen, die gelernte Quellform, Herkunft und Formationsidentitäten.
Die gemeinsame exakte Polynomprojektion verhindert inkompatible Hashschemata
zwischen Formation, Selektor-TRAIN und Anwendung. Lerner und Selektor tragen
dafür jeweils Revision v2.

Elf zusätzliche Tests in `TraceDerivedPolynomialStrategyTest` prüfen die
vollständige Verbindung mit getrennten Formations-, Auswahl- und Anwendungspolynomen.
Sie behalten alle zwölf Selektorzeilen, neue Kandidatenevidenz und die kumulativen
Belegungs-/Pfadbudgets. Überlappende oder nur anders geschriebene Formationszustände
werden auch bei null Solverbudget abgewiesen. Die Sperre umfasst nicht ausgewählte
Vorlagen. Formfehler, endliche Solvermisserfolge und Budgetabbrüche bleiben
verschiedene Ergebnisse. Ein geänderter Herkunfts- oder Domänenvertrag ändert
die eingefrorene Identität, auch bei gleichem sichtbaren Vorlagennamen.

Dies verbindet beide Bausteine in einem begrenzten Entwicklungsfall derselben
quadratischen Familie. Unabhängige Kontrollen, vollständige Kostenbilanz,
familienfremde Übertragung und Produktionspromotion bleiben unter #874/#750 offen.

## Übertragung auf andere Eingabeformen

`learnWithMonomialSupport(traces, limits)` lernt für die **erste** Stufe die
Grade der nicht verschwindenden Monome aus den geprüften TRAIN-Quellen. Dazu
verwendet der Lerner die ohnehin benötigte exakte Polynomprojektion. Ein
Produkt mit Summand kann damit zu derselben Vorlage passen wie seine
ausmultiplizierte Form. Die Folgestufen prüfen weiterhin die geordnete
AST-Form ihrer Zwischenquellen. Der Solver prüft jede neue Koeffizientenbelegung
gegen den tatsächlichen Quellausdruck. Trainingsidentitäten, Integer-Grenzen,
Koeffizientendomänen und kumulative Budgets gelten auch in diesem Modus.
`learn(traces, limits)` verwendet weiterhin den historischen Syntaxvertrag.

Der öffentliche Entwicklungsvergleich in `TracePlanTransferTest` friert die
Auswahl aus drei verifizierten Formationsspuren und zwei separaten
Selektor-TRAIN-Polynomen ein. Die sechs Auswertungsausdrücke gehen weder in
die Vorlagenbildung noch in die Auswahl ein. Alle drei Varianten haben
dasselbe Faktorisierungsziel und dieselben Belegungs- und Theoriepfadbudgets.
Die direkte Kontrolle nutzt denselben exakten Solver und Replay für eine
deklarierte Faktorisierungsvorlage mit denselben Faktorgrenzen.

| Auswertung | Gelernte Syntaxform | Gelernter Monomträger | Direkte Vorlage |
| --- | --- | --- | --- |
| `u*(u+16)+55` | Form passt nicht | Geprüfte Zwischenform und Faktoren 5, 11 | Faktoren 5, 11 |
| `(v+3)^2+10*v+54` | Form passt nicht | Geprüfte Zwischenform und Faktoren 7, 9 | Faktoren 7, 9 |
| `w*(w+11)+30` | Form passt nicht | Kein ganzzahliger Offset für die Zwischenform | Faktoren 5, 6 |
| `n*(n+14)+50` | Form passt nicht | Keine Belegung im begrenzten Ansatz | Keine Belegung im begrenzten Ansatz |
| `q*(q^2+1)+2` | Form passt nicht | Monomträger passt nicht | Keine Belegung im begrenzten Ansatz |
| `(r+2)*(r+7)` | Ziel bereits erfüllt | Ziel bereits erfüllt | Ziel bereits erfüllt |

| Ausgeführte Koeffizientenprüfungen | Gelernte Syntaxform | Gelernter Monomträger | Direkte Vorlage |
| --- | ---: | ---: | ---: |
| Formationsspuren einschließlich Replay | 1.548 | 1.548 | 0 |
| Vollständige Auswahl auf TRAIN | 4.056 | 5.070 | 1.014 |
| Sechs Auswertungsfälle einschließlich Fehlversuchen | 0 | 3.042 | 2.535 |
| Summe | 5.604 | 9.660 | 3.549 |

Der zweite Ansatz erweitert die Anwendbarkeit innerhalb univariater Polynome.
Er erreicht in diesem Vergleich zwei neue Faktorisierungen in je zwei
verifizierten Schritten. Die direkte Vorlage erreicht beide mit je einem
Schritt und löst zusätzlich den Fall mit ungeradem Linearkoeffizienten.
Für diesen Aufgabenstrom ist die direkte Vorlage die günstigere Wahl.

Die Tabelle zählt tatsächliche Solverbelegungen der Erzeugung und beider
Replay-Prüfungen. Die Testausgabe enthält zusätzliche getrennte Zähler für
Projektionen, AST-Besuche und belegte Pfadarbeit. Das Experiment erfasst weder
vollständige CPU-Kosten noch Transfer in eine andere mathematische Domäne.

```bash
mvn -B -pl regelsuche-learning -am -Dtest=TracePlanTransferTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
