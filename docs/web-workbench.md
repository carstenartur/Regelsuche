# Web-Workbench

Die Web-Workbench ist die grafische Oberfläche für Suche, Erklärung, Discovery,
Proof-Jobs und Export. Diese Seite beschreibt den sichtbaren Produktfluss.
Technische HTTP-Verträge stehen ausschließlich in der mitgelieferten
Swagger/OpenAPI-Referenz.

## Start

```bash
./gradlew run --args="serve --port 8080 --host 127.0.0.1"
```

Öffne anschließend `http://127.0.0.1:8080/`.

Für den schnellsten Einstieg kann alternativ das Standard-Dockerimage verwendet
werden; siehe [Getting Started](getting-started.md).

## Empfohlener erster Ablauf

1. **Demo auswählen.** Starte zum Beispiel die Binomische Formel oder eine
   Bruchkürzung.
2. **Ergebnis überblicken.** Die Workbench zeigt Zusammenfassung und
   weiterführende Bereiche erst nach dem ersten Lauf.
3. **Pfade und Graph prüfen.** Vergleiche den besten Rechenweg mit den
   erkundeten Alternativen.
4. **Replay abspielen.** Prüfe jeden Schritt, die verwendete Regel und sichtbare
   Annahmen.
5. **AST-Regelradar öffnen.** Wähle eine Baumposition und untersuche dort
   anwendbare Regeln sowie den vollständigen Folgeausdruck.
6. **Ergebnis weiterverwenden.** Erzeuge ein Export-Bundle oder starte eine
   passende Proof-Obligation.

Die [Demo Gallery](demo-gallery.md) zeigt diesen Ablauf mit testgenerierten
Screenshots.

## Bereiche der Workbench

| Bereich | Aufgabe | Sichtbares Ergebnis |
| --- | --- | --- |
| **Demo-Bereich** | vorbereitete mathematische Aufgabe ausführen | Zusammenfassung, Pfade, Graph, Replay und Exportzugänge |
| **Workbench** | Ausdruck, optionales Ziel, Profil und Regelbereiche festlegen | Suchstatus und Ergebniszusammenfassung |
| **Pfade** | gefundene Rechenwege laden und vergleichen | sortierte Alternativen mit lesbarer Erklärung |
| **Graph** | erkundeten Suchraum untersuchen | interaktiver Graph mit Hauptpfad, Alternativen und Inspector |
| **Replay** | ausgewählten Pfad schrittweise abspielen | Zwischenausdrücke, Änderungen, Regeln und Annahmen |
| **AST-Regelradar** | lokale Züge an einer AST-Position untersuchen | Regelkandidaten, Bindungen, Annahmen und Vorschau des Folgeausdrucks |
| **Regelkandidaten** | aus Suchbeobachtungen gebildete Kandidaten bewerten | Pattern, Unterstützung, Status und Evidence |
| **Identitäten** | wiederkehrende Strukturen untersuchen | Identitätskarten und mögliche Weiterverwendung |
| **Dashboard** | aktuellen Suchraum zusammenfassen | Knoten, Kanten, Tiefe, Verzweigung und Qualitätskennzahlen |
| **Benchmark** | eingefrorene Szenarien ausführen | track-spezifische Ergebnisse und Diagnosemetriken |
| **Vergleich** | zwei Pfade oder Ergebnisse gegenüberstellen | Unterschiede in Form, Länge, Bewertung und Eignung |
| **Inventar** | aktiven Regelbestand untersuchen | Regeln, Herkunft, Tags, Packs und Aktivierungsstatus |
| **Suchgedächtnis** | wiederkehrende Zustände und Muster über Läufe prüfen | Coverage und wiederverwendbare Strukturen |
| **Proof-Jobs** | Obligation einreichen und Ausführung verfolgen | Jobstatus, Solver-Ausgabe und Artefakte |
| **Exporte** | Ergebnisse weiterverwenden | Formate für Dokumentation, mathematische Texte, Graphanalyse und Maschinenverarbeitung |
| **Didaktik** | Lern- und Erklärperspektiven prüfen | didaktisch aufbereitete Rechenwege und Bewertungen |
| **Hilfe** | Syntax und Begriffe nachschlagen | kontextnahe Hinweise sowie Links zu Swagger und OpenAPI |

Nicht jeder Bereich ist vor der ersten Suche sichtbar. Die Oberfläche reduziert
den Einstieg bewusst auf einen Hauptfluss und blendet Detailbereiche erst ein,
wenn passende Daten vorhanden sind.

## Workbench: eigene Suche

### Voraussetzungen

Keine. Für eine zielgerichtete Suche werden Ausdruck und Ziel benötigt; für
eine targetfreie Suche bleibt das Zielfeld leer und die entsprechende Policy
entscheidet über das retained Ergebnis.

### Eingaben

- mathematischer Ausdruck;
- Aufgabentyp beziehungsweise Domäne;
- optionaler Zielausdruck;
- Suchprofil und Budget;
- aktive Regelbereiche oder Packs.

### Sichtbares Ergebnis

Während der Ausführung zeigt die Workbench den Suchstatus. Nach Abschluss sind
Zusammenfassung, Pfade und Graph verfügbar. Ein leerer oder budgeterschöpfter
Lauf bleibt als solcher sichtbar und wird nicht als technischer Erfolg mit
mathematischem Ergebnis dargestellt.

## Pfade, Graph und Replay

### Pfade

Der Pfadbereich zeigt retained Rechenwege. Ein Pfad besteht aus konkreten
Regelanwendungen und ist nicht nur eine Liste von Ausdrücken. Zu jedem Schritt
gehören Herkunft, Position, Bindungen, Annahmen und Kosten.

### Graph

Der Suchgraph enthält vollständige Ausdruckszustände. Filter dürfen die Ansicht
vereinfachen, ändern aber nicht die retained Evidence. Ein AST innerhalb eines
Zustands und der Graph zwischen Zuständen sind unterschiedliche Strukturen.

### Replay

Replay stellt einen ausgewählten Pfad erneut dar. Änderungen werden
hervorgehoben; Annahmen bleiben beim betroffenen Schritt sichtbar. Replay ist
nachvollziehbare Ableitungsevidence, aber kein automatischer formaler Beweis.

## AST-Regelradar

### Einstieg

Öffne nach einer Suche den Bereich **AST-Regelradar** und wähle einen Knoten im
Ausdrucksbaum.

### Sichtbares Ergebnis

Die Workbench zeigt die an dieser Position ausführbaren Grund-, Pack-, Plugin-
und Makroregeln. Für jede Anwendung werden soweit verfügbar dargestellt:

- Regelherkunft und Identität;
- gebundene Platzhalter;
- erforderliche oder emittierte Annahmen;
- vollständiger Folgeausdruck;
- Auswahl-, Ausführungs- oder Pruningstatus im zugehörigen Suchlauf;
- bei Makros die zugrunde liegenden primitiven Schritte.

Eine Vorschau verändert weder den aktiven Ausdruck noch das Inventar. Erst die
explizite Anwendung erzeugt einen neuen Zustand.

## Kandidaten, Identitäten und Inventar

Diese Bereiche unterscheiden drei Ebenen:

1. **Beobachtung:** eine Struktur wurde in einem Lauf gefunden;
2. **Kandidat:** eine verallgemeinerte Regel besitzt eigene Lineage und
   Prüfpflichten;
3. **aktive Regel:** ein autoritativer Bestand hat den Kandidaten nach seinen
   Gates übernommen.

Ein Kandidat wird nicht allein durch häufiges Auftreten promoted. Validation,
Counterexample Search, Proof, Novelty und Promotion bleiben getrennte Stufen.

## Proof-Jobs

### Voraussetzungen

Eine formulierte Obligation und ein aktiviertes Proof-Backend.

### Ablauf

1. Obligation und Annahmen eingeben oder aus einem geeigneten Pfad übernehmen.
2. Job einreichen.
3. Status und Solver-Ausführung verfolgen.
4. Bei Abschluss Artefakte und Ausgaben öffnen.

`FORMALLY_PROVED` wird nur angezeigt, wenn das konfigurierte Backend den
entsprechenden Vertrag tatsächlich bestätigt. Ein technischer Fehler oder ein
nicht verfügbares Backend ist kein mathematischer Gegenbeweis.

## Exporte

Das Export-Bundle bietet Formate nach Verwendungszweck:

- **Markdown** für Dokumentation und Reviews;
- **LaTeX** für mathematische Texte;
- **JSON** für maschinelle Weiterverarbeitung;
- **Mermaid und GraphML** für Graphdarstellung und Analyse;
- **Rule Inventory** für die reproduzierbare Einordnung des aktiven
  Regelbestands.

Ein Export bindet den aktuellen Lauf; er ist keine allgemeine Freigabe eines
Kandidaten als aktive Regel.

## Zustände und Fehlermeldungen

Jeder auslösende Bereich soll folgende Zustände unterscheiden:

- **bereit:** Eingaben und Voraussetzungen sind erkennbar;
- **läuft:** die Operation ist sichtbar aktiv;
- **erfolgreich:** Ergebnis und nächster Schritt werden angeboten;
- **leer:** es wird erklärt, welche Aktion Daten erzeugt;
- **budgeterschöpft oder ohne Ergebnis:** fachlicher Terminalzustand bleibt
  sichtbar;
- **fehlgeschlagen:** verständliche Diagnose ohne rohe Serverantwort als
  Benutzertext.

## REST-Referenz

Nach dem Start stehen lokal bereit:

- Swagger UI: `http://127.0.0.1:8080/static/openapi/index.html`
- OpenAPI 3.1 JSON: `http://127.0.0.1:8080/static/openapi/openapi.json`

Die Referenz wird vollständig selbst gehostet. Methoden, konkrete Pfade,
Payloads, Statuscodes und technische Beispiele werden ausschließlich dort
gepflegt. Siehe [Dokumentationskonventionen](documentation-conventions.md).

## Sicherheit

Die lokale Standarddemo verwendet HTTP ohne Anmeldung. Sie ist an
`127.0.0.1` zu binden. Eine extern erreichbare Installation benötigt
mindestens authentifiziertes TLS, eigene Zugangsdaten, Größen- und
Ressourcengrenzen sowie ein Betriebskonzept.

## Siehe auch

- [Web-Workbench-Benutzerhandbuch](web-ui-user-guide.md)
- [User Workflows](user-workflows.md)
- [AST-Regelradar](ast-rule-radar.md)
- [Proof Workbench](proof-workbench.md)
- [Getting Started](getting-started.md)
