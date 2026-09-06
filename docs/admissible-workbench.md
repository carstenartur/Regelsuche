# Zulässige Primzahlmuster in der Workbench untersuchen

Die zusätzliche Experimentansicht läuft im vorhandenen Workbench-Server unter
`/static/admissible-workbench.html`. Sie lädt keine Erweiterungen und benötigt
weder Python noch das Primachsenraum-Repository auf dem Server. Die Oberfläche
bleibt von Ausdruckssuche, Regelbestand und laufenden Proof-Jobs getrennt.

## Einstieg

Starte die normale Workbench mit `serve --host 127.0.0.1 --port 8080` und öffne
`http://127.0.0.1:8080/static/admissible-workbench.html`.

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

**Dies ist eine interaktive Ergebnis- und Beweisansicht. Sie startet noch keine
neue Java-/SDK-Kampagne und lernt keine neue Regel im Browser.**

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

Das JSON-Format `admissible-workbench/v1` ist absichtlich kompakt und kanonisch.
Es akzeptiert keine zusätzlichen oder doppelten Felder und keine beliebig
verschachtelten Daten. Arbeitszähler bleiben Dezimalzeichenketten, damit die
JavaScript-Ganzzahlpräzision nicht stillschweigend Werte verändert. Importdaten
werden nur als Text und DOM-Elemente dargestellt, nicht als HTML ausgeführt.

## Nachprüfen und Tests

```bash
node --test scripts/test-admissible-proof.cjs
./gradlew :app:e2eTest --tests de.regelsuche.e2e.AdmissibleWorkbenchBrowserTest
```

Die 16 Node-Prüfertests benötigen keine Zusatzbibliothek. Die sieben
Playwright-Tests starten den vorhandenen Produktionsserver in einem eigenen
Prozesskontext, laden den echten Worker und importieren eine komprimierte
Referenz aus einem ausgeführten Primachsenraum-CI-Lauf. Sie prüfen Navigation,
Tastatur, Mobilgröße, beschädigte Dateien, verspätete Antworten und Zurücksetzen.
Sie verwenden keinen simulierten Mathematikprüfer. Desktop- und Mobilaufnahmen
stehen danach unter `app/build/reports/admissible-workbench/`.

Die Herkunft der Referenz steht in
`app/src/e2eTest/resources/admissible/README.md`. Die normale Repository-CI führt
die bestehende E2E-Testsuite einschließlich dieser Klasse aus. Der aktuelle
CI-Status ist getrennt von lokal ausgeführten Node-Tests zu lesen.
