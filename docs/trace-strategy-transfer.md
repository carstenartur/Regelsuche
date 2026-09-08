# Regelfolgen lernen und auf neue Zusammensetzungen anwenden

Regelsuche kann aus realen Suchwegen ein verzweigendes Programm bilden und es
auf neue Ausdrücke anwenden. Der neue `TraceRewriteStrategyLearner` erhält nur
ein fixes Regelinventar, TRAIN-Eingaben und Budgets. Er erhält weder Zielausdruck
noch Evaluationsaufgaben. Anders als die
[Koeffizientenvorlagen](trace-derived-polynomial-plans.md) lernt er die Reihenfolge
und Verzweigung vorhandener Regeln. Die algebraischen Identitäten selbst bleiben
vorgegeben.

Der Entwicklungsvergleich zeigt bereits erfolgreiche Anwendung auf neue
Zusammensetzungen. **Ein Suchvorteil ist hier nicht belegt:** Alle drei Profile
liefern dieselben Endausdrücke, und das gelernte Programm verursacht mehr
Sucharbeit. Die öffentliche Fallserie ist kein versiegelter VALIDATION- oder
FINAL-TEST-Bestand und keine unabhängige Prüfung anderer Mathematikfamilien.

## Demo ausführen

Mit Java 25 im Projektverzeichnis:

```bash
./gradlew :regelsuche-learning:strategyTransferDemo
```

Alternativ mit Maven 3.9.9 oder neuer aus der 3.x-Linie; dieser Befehl führt auch
die Integrationsprüfung mit vollständigem Replay aus:

```bash
mvn --batch-mode --no-transfer-progress -pl regelsuche-learning -am \
  -Dtest=TraceStrategyTransferExampleTest \
  -Dregelsuche.strategyTransfer.output="$PWD/build/reports/strategy-transfer" test
```

Der ausgegebene Pfad führt zur lokalen `index.html`. Sie benötigt keinen Server,
keinen API-Schlüssel und keine externen Webressourcen. Zu jeder Aufgabe lassen
sich Ausgang, Endausdruck, primitive Regeln, konkrete Anwendungen und Sucharbeit
aufklappen. Auch die inneren Schritte einer Programmkante bleiben sichtbar.

Das Verzeichnis wird nach dem Hash des Artefaktmanifests benannt. Es enthält `protocol.json`,
`strategy.json`, `report.json`, `report.md`, `index.html`, alle vier Trainings-
und 21 unterstützten Anwendungssuchen sowie ein abschließendes Hashmanifest.
Eine Wiederholung muss dieselben Bytes erzeugen; abweichende vorhandene Dateien
werden nicht überschrieben. Die JSON-Artefakte schaffen allein keine ausführbare
oder mathematische Autorität: Replay rekonstruiert die Quellen aus dem geprüften
Inventar und führt sie erneut aus.

## Was tatsächlich gelernt wird

1. Acht feste Polynomregeln werden symbolisch über exakten rationalen
   Koeffizienten geprüft. Bedingungen, feste Symbolnamen und Funktionen in
   Regeln werden abgelehnt.
2. Die bestehende arbeitsbegrenzte Best-First-Suche untersucht vier
   Trainingsausdrücke ohne Zielausdruck. Die Priorität ist der vorhandene
   Ausdrucksscore plus zwei pro primitivem Schritt und fünf pro expandierendem
   Schritt; eine syntaktische Zieldistanz entfällt.
3. Der jeweils ausgewählte Pfad wird mit seinen tatsächlichen primitiven Regeln
   erneut ausgeführt und jeder Schritt exakt geprüft. Auch unveränderte,
   einstufige und budgetbegrenzte Beobachtungen bleiben gespeichert. Nur
   verbessernde Pfade mit mindestens zwei Schritten tragen zum Programm bei.
4. Gleiche Präfixe der beobachteten Regelfolgen werden zusammengefasst. Daraus
   entstehen `Source`, `Sequence` und `Choice` im vorhandenen
   `EvolutionRewriteProgramPlan`; Compiler und Interpreter werden wiederverwendet.
5. Das Modell bindet Inventar, Budgets, Trainingsspuren und Ausschlüsse vor
   weiteren Anwendungen. Unterschiedliche Trainingsaufgaben können andere
   Programme erzeugen. Ohne geeignete Spur entsteht kein Programm.

Im aktuellen Lauf liefern drei der vier Trainingsaufgaben mehrstufige Pfade.
Es entstehen zwei Zweige: `add-zero → factor-left` und
`difference-product → square-product → cancel-addend`.
Die Programme enthalten Regelnamen, keine auswendig gelernten Trainingsausdrücke.
Ihre Quellen wenden die Regeln an passenden AST-Positionen neu an.

Anwendungen, die einem beobachteten Trainingspolynom mathematisch entsprechen,
werden auch nach Umformung oder Variablenumbenennung abgewiesen. Dazu verwendet
`ExactPolynomialAnalysis` die vorhandene exakte Polynomprojektion und minimiert
über alle Variablenpermutationen. Wiederholte Variablen und große benachbarte
Ganzzahlen bleiben unterscheidbar. Unterstützt sind höchstens vier verbleibende
Polynomvariablen innerhalb der vorhandenen Größen-, Grad- und Koeffizientenlimits.
Ein nicht unterstützter oder zu großer Ausdruck gilt niemals automatisch als
neuer, vom Training getrennter Fall.

## Fester Vergleich und Ergebnis

`TraceStrategyTransferExample.protocol()` bindet vor dem Training Inventar,
Eingaben, Profile, Zielkriterium und gemeinsame Budgets: sechs primitive Schritte,
80 Zustände, 32 Kandidaten je Zustand, sechs expandierende Schritte und
30.000 Sucharbeitseinheiten.

| Profil | Verfügbare Ausführung |
| --- | --- |
| `FLAT_RULES` | Acht Einzelregeln |
| `LEARNED_PROGRAM` | Dieselben Regeln plus das gelernte Programm |
| `SHUFFLED_PROGRAM` | Dieselben Regeln plus ein Programm mit veränderter Reihenfolge |

Die Ablation verwendet Fisher–Yates mit festem Seed. Bleibt eine einzelne Folge
zufällig gleich, wird sie einmal rotiert. Diese Vorschrift sieht keine
Anwendungsergebnisse. Das Modell weist zusätzlich aus, ob sich das gesamte
Programm tatsächlich geändert hat; bei Folgen gleicher Regeln ist das nicht
garantiert. Die Anzahl von Programmpräfixen und deren Arbeit können sich ändern
und werden deshalb gemessen, nicht als identisch angenommen.

Alle drei Profile erhalten dieselben Ausgangsinformationen und Budgets. Die
Lernkosten werden zusätzlich ausgewiesen. Die Fallbezeichnungen dienen nur dem
Bericht. Alle acht Fälle und alle drei Profile bleiben erhalten:

| Entwicklungsfall | Einzelregeln: Sucharbeit | Gelernt: Sucharbeit | Ablation: Sucharbeit | Gelernter Pfad verwendet |
| --- | ---: | ---: | ---: | --- |
| Neue Summe | 104 | 235 | 200 | Ja |
| Äußeres Produkt | 41 | 97 | 85 | Ja |
| Mehrere Vorkommen | 96 | 239 | 187 | Ja |
| Zusammengesetzte Basis | 41 | 97 | 85 | Ja |
| Wiederholte Faktoren | 228 | 412 | 460 | Nein |
| Nicht wegfallender Restterm | 14 | 34 | 30 | Nein |
| Bereits einfacher Ausdruck | 6 | 14 | 14 | Nein |
| `sin(x)+1` | Nicht unterstützt | Nicht unterstützt | Nicht unterstützt | — |

Für die sieben unterstützten Aufgaben betragen die Summen 530, 1.128 und 1.061
Sucharbeitseinheiten. Die Endausdrücke und ihre Scores sind in allen Profilen
gleich. Das Training benötigt zusätzlich 188 Einheiten Sucharbeit, 27 Einheiten
primitiver Replay-Arbeit und neun exakte Schrittprüfungen; die acht
Inventarprüfungen werden gesondert erfasst.

Der [vollständige generierte Bericht](generated/trace-strategy-transfer-reference.md)
und die [maschinelle Referenz](generated/trace-strategy-transfer-reference.json)
werden von der Integrationsprüfung gegen einen frisch ausgeführten Lauf geprüft.
Die vollständigen Suchartefakte sind mit dem Startbefehl reproduzierbar.

## Grenzen und nächster Forschungsnachweis

Die gezählten Einheiten beschreiben die vorhandene Suchmechanik und
Programmausführung. Sie sind weder Laufzeiten noch eine vollständige Bilanz von
Parsing, Identitätsprojektion, BigInteger-Arbeit, Kompilierung und Lernen.
Programmkanten erhalten keine kostenlose primitive Tiefe; abgewiesene und
erfolglose Programmarbeit bleibt sichtbar.

Die Ergebnisse sprechen dafür, als Nächstes die Anwendbarkeit und den Nutzen
eines Programms **vor seinem Aufruf** aus TRAIN-Daten abzuschätzen. Der aktuelle
Ansatz versucht beide Zweige zusätzlich zur flachen Suche an jedem Zustand und
bezahlt entsprechend. Eine solche Auswahl muss anschließend auf einem neu
festgelegten, unberührten Bestand mit vollständigerer Kostenbilanz geprüft werden.

Die öffentlichen Beispiele verwenden weiterhin bekannte algebraische Bausteine.
Sie ersetzen weder den familienübergreifenden Nachweis aus #874 noch die
informationstreuen externen Baselines aus #235 oder den versiegelten
Flagship-Prozess aus #533. Neue Identitäten, Schleifen, Strukturselektoren,
Darstellungswechsel zwischen Matrizen/Rekurrenzen/Polynomen und automatische
Produktivaktivierung sind nicht Teil dieses Bausteins.
