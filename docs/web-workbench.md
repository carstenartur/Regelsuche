# Web-Workbench

Die Web-Workbench (`de.regelsuche.web.WebWorkbenchServer`) verwendet den im JDK eingebauten `com.sun.net.httpserver.HttpServer` und bringt damit keine zusätzliche Web-Framework-Abhängigkeit ins Projekt. Start per CLI:

```bash
./gradlew run --args="serve --port 8080 --host 127.0.0.1"
```

Danach ist die Oberfläche unter `http://127.0.0.1:8080/` erreichbar.

## Zweck der Oberfläche

Die Web-Workbench ist die grafische Bedienoberfläche für die Funktionen, die der Server auch über seine REST-API bereitstellt. Diese Seite beschreibt deshalb den sichtbaren Produktfluss. Methoden, Pfade, Payloads, Responses und Statuscodes werden nicht parallel gepflegt, sondern stehen ausschließlich in Swagger/OpenAPI.

## Bedienbereiche

| Bereich | Grafische Funktion | Sichtbares Ergebnis | Swagger-Bereich |
| --- | --- | --- | --- |
| **Demo-Bereich** | Eine vorbereitete mathematische Aufgabe mit einem Klick ausführen | Zusammenfassung, bester Rechenweg, Graph, Replay, Identität und Exportzugänge | Demo, Search, Exports |
| **Workbench** | Ausdruck, Typ, Profil, Ziel und Regel-Domänen wählen und die Suche starten | Suchstatus und Ergebniszusammenfassung; nach dem ersten Lauf werden die weiterführenden Tabs sichtbar | Search |
| **Pfade** | Gefundene Rechenwege aktualisieren, Darstellungsform wählen und einen Pfad öffnen | Sortierte Alternativen und eine lesbare Detail-Erklärung | Paths, Explain |
| **Graph** | Suchraum laden, Ansichtsmodus und Filter wählen, Knoten untersuchen | Interaktiver Suchgraph mit Hauptpfad, Alternativen, Clustern und Inspector; Mermaid bleibt als Fallback verfügbar | Search Graph, Graph |
| **Regelkandidaten** | Aus der Suche abgeleitete Kandidaten prüfen und nach Evidenz einordnen | Pattern, Beispiele, Bewertung, Status, Proof-Status und Annahmen | Candidates |
| **Identitäten** | Wiederkehrende oder emergente Strukturen prüfen und geeignete Ergebnisse übernehmen | Identitätskarten mit Evidenz und Übernahmeaktion | Identities, Inventory |
| **Dashboard** | Kennzahlen des aktuellen Suchraums überblicken | Knoten, Kanten, Sackgassen, Tiefe, Verzweigung, nützliche Regeln und Kandidaten | Search Graph |
| **Benchmark** | Reproduzierbare Qualitätsszenarien ausführen und vergleichen | Ampelstatus und fachliche sowie technische Qualitätsmetriken pro Szenario | Benchmark |
| **Replay** | Einen Rechenweg abspielen, anhalten und schrittweise untersuchen | Mathematische Zwischenschritte, hervorgehobene Änderungen, Regelhinweise und Annahmen | Paths, Replay |
| **Vergleich** | Zwei Ergebnisse oder Pfade nebeneinander betrachten | Unterschiede in Ergebnisform, Länge, Bewertung und didaktischer Eignung | Paths, Compare |
| **Inventar** | Wiederverwendbare Regeln durchsuchen, aktivieren und mit Tags ordnen | Aktueller Regelbestand und sichtbare Bestätigung der Änderung | Inventory |
| **Suchgedächtnis** | Wiederkehrende Zustände und universelle Muster über mehrere Läufe untersuchen | Universality Score, Rule-Coverage und wiederverwendbare Strukturen | Memory |
| **Proof-Jobs** | Beweisauftrag einreichen, Status verfolgen, abbrechen und Artefakte öffnen | Persistenter Jobstatus sowie Lean-/SMT-Artefakte, Ausgaben und Metadaten | Proof Jobs |
| **Exporte** | Ergebnisse in geeigneter Form weiterverwenden | Downloads für Dokumentation, mathematische Texte, Graphwerkzeuge und maschinelle Verarbeitung | Exports |
| **Didaktik** | Erklär- und Bewertungsfunktionen für Lernwege verwenden | Unterrichtsnahe Darstellung und didaktische Qualitätsinformationen | Didactics |
| **AST-Regelradar** | Ausdruck als AST untersuchen, einen Knoten wählen, dort anwendbare Züge prüfen und eine Anwendung zunächst als Vorschau betrachten | Zoombarer Ausdrucksbaum mit positionsgebundenen Grund-, Erweiterungs- und Makroregeln, Bindungen, Annahmen, Folgeausdruck sowie Auswahl-, Anwendungs- und Pruningstatus | Rule Radar |
| **Hilfe** | Syntax, Begriffe und Bedienhinweise nachschlagen; technische Referenz öffnen | Kontextnahe Kurzhilfe sowie direkte Links zur lokalen Swagger-UI und OpenAPI-Spezifikation | — |

Die Namen in der letzten Spalte sind fachliche Zuordnungen. Die verbindlichen Operationen und Schemata stehen in der Swagger/OpenAPI-Dokumentation der laufenden Installation.

## Typischer Bedienfluss

1. Im Demo-Bereich eine Aufgabe wählen oder im Tab **Workbench** eine eigene Suche starten.
2. In **Pfade**, **Graph** und **Replay** nachvollziehen, wie die Umformung zustande kam.
3. Im **AST-Regelradar** untersuchen, welche konkreten lokalen Züge an einer ausgewählten Baumposition möglich waren und welche davon die Suche verwendet oder verworfen hat.
4. In **Regelkandidaten**, **Identitäten**, **Dashboard** und **Benchmark** Evidenz und Qualität beurteilen.
5. In **Inventar**, **Suchgedächtnis** oder **Proof-Jobs** mit dem Ergebnis weiterarbeiten.
6. In **Exporte** das passende Ausgabeformat herunterladen.

## Zustände und Rückmeldungen

Jeder auslösende Bereich soll mindestens folgende Zustände verständlich darstellen:

- **bereit:** benötigte Eingaben und Voraussetzungen sind erkennbar;
- **läuft:** eine Suche, Auswertung oder ein Proof-Job ist sichtbar in Bearbeitung;
- **erfolgreich:** Ergebnis und nächster sinnvoller Schritt werden angeboten;
- **leer:** es wird erklärt, warum noch keine Daten vorhanden sind und welche Aktion sie erzeugt;
- **fehlgeschlagen:** die Oberfläche zeigt eine nutzbare Fehlermeldung, ohne rohe Serverantworten als Benutzerdokumentation zu verwenden.

## Technische REST-Referenz

Für direkte REST-Nutzung gilt ausschließlich Swagger/OpenAPI. Nutzer- und Feature-Dokumente verweisen höchstens auf den fachlichen Swagger-Bereich oder eine `operationId`; sie wiederholen keine Endpoint-Tabellen oder JSON-Verträge. Siehe [Dokumentationskonvention](documentation-conventions.md).

Nach dem Start der Workbench stehen zwei stabile lokale Zugänge bereit:

- **Swagger UI:** `http://127.0.0.1:8080/static/openapi/index.html`
- **OpenAPI 3.1 JSON:** `http://127.0.0.1:8080/static/openapi/openapi.json`

Die Swagger-Oberfläche, ihre JavaScript- und CSS-Dateien sowie Lizenz- und Hinweisdateien werden vollständig aus dem gestarteten Checkout ausgeliefert. Für die API-Referenz ist weder ein CDN noch eine Internetverbindung erforderlich.

## Statische Assets

Die Oberfläche unter `/` lädt ihre HTML-, CSS- und JavaScript-Ressourcen aus dem Classpath-Verzeichnis `app/src/main/resources/web/`. KaTeX, Cytoscape und die gepinnte Swagger UI werden selbst gehostet; die Benutzung der Workbench und ihrer technischen API-Referenz erfordert keine externen CDN-Zugriffe.
