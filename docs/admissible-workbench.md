# Zulässige Primzahlmuster in der Workbench untersuchen

Die zusätzliche Experimentansicht läuft im vorhandenen Workbench-Server unter
`/static/admissible-workbench.html`. Sie lädt keine Erweiterungen und benötigt
weder Python noch das Primachsenraum-Repository auf dem Server. Die Oberfläche
bleibt von Ausdruckssuche, Regelbestand und laufenden Proof-Jobs getrennt.
Ein optionaler lokaler Primachsenraum-Runner kann dieselben Oberflächenressourcen
verwenden, um neue Java-/SDK-Läufe zu starten; siehe den Abschnitt unten.

## Einstieg

Starte die normale Workbench mit `serve --host 127.0.0.1 --port 8080`.
Unter den Demos führt **Primzahlmuster und Optimalitätsbeweise erkunden** zur
Experimentansicht. Der Link öffnet sie mit `noopener` in einer neuen Ansicht,
damit der eingegebene Ausdruck und die bestehende Sitzung erhalten bleiben.
Direkter Einstieg: `http://127.0.0.1:8080/static/admissible-workbench.html`.

Mit **Kleines Beispiel erkunden** wird das Maximum vier im Fenster 0..8
nachgerechnet. Die Ansicht zeigt zunächst alle neun Positionen, dann den
Ausschluss der ungeraden Restklasse und schließlich die zwei Alternativen
modulo 3. Die grün markierten Positionen gehören zum zulässigen unteren Zeugen.
Mit **Einen Schritt zurück** und **Zum Anfang** lassen sich die Alternativen
vergleichen. Die Knöpfe sind auch per Tastatur bedienbar.

## Tatsächliche Experimente importieren

Primachsenraum PR #224 exportiert nach `strategyDiscovery` eine Datei
`workbench.json`. Der dortige Python-Exporter prüft zunächst den kompletten
Versuchsaufbau einschließlich TRAIN, VALIDATION und aller nativen Arbeitsdaten.
Die Datei enthält anschließend nur die beiden TEST-Vergleichsläufe pro Aufgabe:
bisherige und ausgewählte Strategie samt unveränderten Zertifikaten.

Wähle diese Datei in der Experimentansicht. Die Datei bleibt im Browser; kein
Upload, Serverprozess oder ausführbarer Importcode wird angestoßen. Die Tabelle
zeigt beide Strategien, ihren zulässigen Zeugen und den gemeldeten Arbeitswert.
Im Auswahlfeld darunter kann jeder vollständige Obergrenzenbeweis unabhängig
von der Größe oder Reihenfolge des ursprünglichen Suchlaufs erkundet werden.

## Optionaler lokaler Java-Runner

Der Primachsenraum-SDK-Consumer stellt den optionalen Auftrag `liveWorkbench`
bereit. Dieser Loopback-Server verwendet die fünf vorhandenen Viewer-Dateien
und ergänzt seine eigene Bedienung und HTTP-Verbindung. Der Ausdrucksserver
von Regelsuche erhält dadurch weder einen Prozessstart-Endpunkt noch eine
Abhängigkeit vom privaten Primachsenraum-Repository. Startanleitung und
Prozessgrenzen stehen dort in `docs/research/admissible-live-workbench.md`.

Ein fertiger Lauf übergibt ein `admissible:local-result`-Ereignis mit einer
begrenzten JSON-Zeichenkette. Dies ist kein vertrauenswürdiges Ergebnisobjekt:
Die Ansicht löscht den vorigen Zustand und ruft denselben unabhängigen Worker
wie beim Dateiimport auf. Beschädigte, zu große und nichttextuelle Daten werden
abgewiesen; ein übergebener Erfolgsstatus ersetzt keinen mathematischen Beweis.

Neue Aufgaben verwenden `admissible-workbench/v2` mit genau den bisherigen
Feldern und zusätzlich `scope: "exploratory"`. Andere Scopes werden abgewiesen.
Die Kennzeichnung überlebt Download und erneuten Import. Ein direkt übergebener
lokaler Lauf wird auch bei v1-Daten als explorativ angezeigt, nicht als
zurückgehaltener Testfall. Diese Kennzeichnung ist keine Herkunftssignatur.
Die mathematische Prüfung ist in beiden Formaten gleich.

Der tatsächliche SDK-Lauf, die getrennten nativen Strategievergleiche,
HTTP-Sicherheit und Prozessgrenzen liegen im Erzeugerprojekt. Die Ansicht
behauptet weder ein erneutes Lernen noch eine Messung sämtlicher SDK-Arbeit.
Ohne diesen ausdrücklich gestarteten optionalen Runner bleibt sie eine reine
Import- und Beweisansicht.

## Was tatsächlich geprüft wird

Ein separater Browser-Worker prüft mit BigInt-Arithmetik den unteren Zeugen und
jede notwendige Verzweigung. Für ein Maximum k muss jeder noch größere Knoten
auf eine Primzahl verzweigen, deren Restklassen vollständig besetzt sind. Da 0
enthalten bleibt, werden alle nichtnullten Restklassenentzüge rekonstruiert.
Ausgelassene, doppelte, nicht erreichbare oder mathematisch unzulässige
Beweisäste führen zur Ablehnung. Die Zuordnung von Aufgabe, Zeuge, Beweisdatei,
Prüfsumme und Knotenzahl zum importierten Arbeitsprotokoll wird geprüft.

Der Arbeitswert wird aus seinen deklarierten Zählern neu addiert. **Die
Richtigkeit der Arbeitszähler und die Auswahl im Training werden im Browser
nicht nachgespielt.** Das ist Aufgabe des unabhängigen Python-Prüfers im
Erzeugerprojekt. Eine Manifestkennung ist außerdem keine Signatur oder
Authentifizierung der Quelle. Die Oberfläche unterscheidet diese Grenzen
sichtbar von den neu nachgerechneten mathematischen Aussagen.

Ein Budgetabbruch weist nur einen zulässigen unteren Zeugen nach und erhält
keinen bestätigten Maximalwert. Die dargestellten Verzweigungen sind der
Obergrenzenbeweis, nicht die zeitliche Abfolge sämtlicher Suchentscheidungen.
Zulässige Plätze bedeuten nicht, dass an einer Verschiebung gleichzeitig lauter
Primzahlen stehen. Eine schnellere Primzahlprüfung wird nicht behauptet.

## Grenzen und Fehlerverhalten

Der Import ist auf 8 MB, 32 Läufe, 257 Positionen bis Offset 4096 und 20.000
Beweisknoten pro Lauf begrenzt. Ein gemeinsam gezähltes Prüfbudget gilt für den
ganzen Import. Zusätzlich beendet die Oberfläche den Worker nach zehn Sekunden.
Bei Timeout, Fehler oder Abbruch bleibt kein zuvor bestätigtes Ergebnis aktiv.
Eine verzögert fertig werdende Datei kann einen neueren Auftrag nicht ersetzen.

Die JSON-Formate sind absichtlich kompakt und kanonisch. Sie akzeptieren keine
zusätzlichen oder doppelten Felder und keine beliebig verschachtelten Daten.
Nur ein einzelner abschließender LF ist optional; zusätzliche Leerzeichen oder
Zeilenumbrüche werden zurückgewiesen. Arbeitszähler bleiben Dezimalzeichenketten,
damit die JavaScript-Ganzzahlpräzision nicht stillschweigend Werte verändert.
Importdaten werden nur als Text und DOM-Elemente dargestellt, nicht als HTML
ausgeführt. Ist WebCrypto nicht verfügbar, erklärt eine Fehlermeldung den
Zugriff über localhost oder HTTPS, statt einen ungeprüften Ersatz-Hash zu nutzen.

## Nachprüfen und Tests

```bash
node --test scripts/test-admissible-proof.cjs scripts/admissible-live-scope.test.cjs
./gradlew :app:e2eTest --tests de.regelsuche.e2e.AdmissibleWorkbenchBrowserTest
```

Die 18 grundlegenden und fünf Scope-Node-Prüfertests benötigen keine zusätzliche
Bibliothek. Die zehn Playwright-Tests teilen die bestehende Produktionsserver-
und Browser-Fixture. Sie laden den echten Worker und importieren eine Referenz
aus einem ausgeführten Primachsenraum-CI-Lauf. Sie prüfen Hauptseiten-Einstieg,
Navigation, Tastatur, Mobilgröße, beschädigte Dateien, verspätete Antworten,
Zurücksetzen, lokale Ergebnisübergabe und v2-Reimport. Der Upload-Monitor hängt
an der Experimentseite, nicht an den legitimen AST-Anfragen der Hauptseite;
ein absichtlich eingefügter POST bestätigt, dass der Monitor tatsächlich greift.
Desktop- und Mobilaufnahmen stehen unter `app/build/reports/admissible-workbench/`.

Die Herkunft der Referenz steht in
`app/src/e2eTest/resources/admissible/README.md`. Die normale Repository-CI führt
die bestehende E2E-Testsuite einschließlich dieser Klasse aus. Der aktuelle
CI-Status ist getrennt von lokal ausgeführten Node-Tests zu lesen.
