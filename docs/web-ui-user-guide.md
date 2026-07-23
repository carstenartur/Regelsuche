# Web-Workbench — Benutzerhandbuch

Die Web-Workbench stellt die mathematischen Such-, Analyse-, Proof- und Exportfunktionen der Regelsuche als zusammenhängende grafische Oberfläche bereit. Sie ist als lokales Explorationswerkzeug gedacht und nicht als produktive Mehrbenutzeranwendung.

Die technische REST-Referenz wird separat über Swagger/OpenAPI gepflegt. Dieses Handbuch beschreibt ausschließlich die sichtbare Bedienung und das Ergebnis in der Oberfläche.

## Start

```bash
./gradlew run --args="serve --port 8080 --host 127.0.0.1"
```

Danach im Browser `http://127.0.0.1:8080/` öffnen.

## Einstieg über die Demo-Karten

Der Startbereich bietet vorbereitete Aufgaben für binomische Formeln, Bruchkürzung, Trigonometrie, Polynom-Expansion, Makroregel-Lernen, Gleichungen, Ungleichungen, Ableitungen und Matrizen.

Ein Klick startet nicht nur eine isolierte Berechnung. Die Workbench führt den vollständigen Ablauf aus, zeigt eine Zusammenfassung und macht anschließend die passenden Bereiche für Suchgraph, AST-Regelradar, Pfade, Replay, Identitäten, Qualität und Export zugänglich.

## Oberfläche

### Workbench

- **Ausdruck:** mathematische Eingabe in der unterstützten Syntax.
- **Typ:** Term oder Gleichung.
- **Profil:** schnelle Vereinfachung, Erkundung/Discovery, Didaktik, beweisorientierte Suche oder erschöpfende Suche für kleine Räume.
- **Ziel:** bevorzugte Ergebnisform, beispielsweise Vereinfachen, Faktorisieren, Schulweg, beweisfreundliche Form oder numerische Stabilität.
- **Regel-Domänen:** gezielte Aktivierung fachlicher Regelbereiche wie Polynome, rationale Ausdrücke, Trigonometrie, Logarithmen, Radikale oder elementare Analysis.
- **Beispiele:** Schaltflächen übernehmen typische Eingaben direkt in das Formular.

Mit **Suche starten** beginnt der Lauf. Der Statusbereich zeigt, ob die Eingabe verarbeitet wird, ein Ergebnis vorliegt oder eine Korrektur erforderlich ist.

### Pfade

Der Tab **Pfade** zeigt die gefundenen Rechenwege. Über **Aktualisieren** wird der aktuelle Stand geladen; die Auswahl **Schulbuch**, **LaTeX** oder **Schritte** bestimmt die Darstellung. Ein Klick auf einen Pfad öffnet eine Detail-Erklärung.

Ein leerer Bereich bedeutet, dass zunächst eine Suche oder Demo ausgeführt werden muss. LaTeX-Quelltext kann in mathematischen Dokumenten weiterverwendet werden.

### Graph

Der Tab **Graph** macht den globalen Suchraum sichtbar. Verfügbar sind:

- **Semantische Erklärung** als nutzerorientierte Standardansicht,
- **Nur Hauptpfad** für den bevorzugten Rechenweg,
- **Complexity Map** für strukturelle Unterschiede,
- **Rohgraph** für technische Untersuchung.

Zusätzliche Schalter blenden triviale Normalisierungen, Alternativen und Varianten in Clustern ein oder aus. Im interaktiven Modus kann der Graph gezoomt, verschoben und über den Inspector untersucht werden. Wenn die interaktive Darstellung nicht verfügbar ist, bleibt die Mermaid-Ansicht als Fallback erhalten.

### Regelkandidaten

Hier werden aus der Suche abgeleitete Kandidaten mit Pattern, Beispielanzahl, Bewertung, Status, Proof-Status und Annahmen angezeigt. Die Oberfläche soll nicht nur einen Score präsentieren, sondern erkennbar machen, auf welcher Evidenz ein Kandidat beruht.

### Identitäten

Der Tab **Identitäten** bündelt wiederkehrende oder emergente mathematische Strukturen. Nutzerinnen und Nutzer können Evidenz und Annahmen prüfen und geeignete Ergebnisse in den kontrollierten Übernahmeprozess geben.

### Dashboard

Das **Dashboard** fasst den aktuellen Suchraum zusammen: besuchte Knoten, erzeugte Kanten, Sackgassen, erreichte Tiefe, Verzweigung, nützliche Regeln sowie Kandidaten- und Makroregelzahlen. Es dient der Einordnung eines Laufs, nicht als Ersatz für die fachliche Prüfung in Pfaden, Graph und Replay.

### Benchmark

Der Tab **Benchmark** stellt reproduzierbare Szenarien mit Ampelstatus und Qualitätsmetriken dar. Neben dem fachlichen Treffer werden Proof-Status, Suchaufwand, E-Graph-Metriken, Einsparungen und die Verwendung gelernter Regeln sichtbar.

### Replay

Im **Replay** wird ein ausgewählter Rechenweg abgespielt oder schrittweise durchlaufen. Geänderte mathematische Teile werden hervorgehoben. Kritische Operationen, Annahmen und mögliche Änderungen eines Vergleichszeichens müssen direkt am betroffenen Schritt verständlich sein.

### Vergleich

Der Tab **Vergleich** stellt zwei Ergebnisse oder Rechenwege nebeneinander. Er unterstützt die Auswahl nach Ergebnisform, Pfadlänge, Komplexität, didaktischer Eignung oder Beweisfreundlichkeit.

### Inventar

Das **Inventar** listet wiederverwendbare Regeln. Aktivierungszustand und Tags können über die vorgesehenen Bedienelemente geändert werden. Die Oberfläche zeigt an, ob eine Änderung übernommen wurde; eine nur lokale Änderung darf nicht den Eindruck einer erfolgreichen Persistenz erwecken.

### Suchgedächtnis

Das **Suchgedächtnis** zeigt wiederkehrende Zustände und universelle Muster über mehrere Läufe. Universality Score und Rule-Coverage helfen zu unterscheiden, ob eine Struktur nur in einer einzelnen Demo oder in verschiedenen Suchräumen relevant ist.

### Proof-Jobs

Im Tab **Proof-Jobs** werden linkes und rechtes Pattern, optionale Annahmen und Priorität eingegeben. Nach **Job einreichen** erscheint der Auftrag in einer persistenten Liste. Der Status kann aktualisiert, ein laufender Auftrag abgebrochen und bei terminalen Zuständen das Artefakt-Bundle geöffnet werden.

Das Bundle trennt Beweisskript, Standardausgabe, Fehlerausgabe und Metadaten. Die Oberfläche kennzeichnet klar, ob ein realer Prover oder ein deterministischer E2E-Test-Prover beteiligt war.

### Exporte

Der Tab **Exporte** bietet Ausgabeformate nach ihrem Verwendungszweck an:

- Markdown für Dokumentation,
- LaTeX für mathematische Texte,
- Mermaid oder GraphML für Graphwerkzeuge,
- JSON für maschinelle Weiterverarbeitung,
- Bundles für reproduzierbare Weitergabe zusammengehöriger Artefakte.

### Didaktik

Der Bereich **Didaktik** erklärt und bewertet Lernwege. Interne Scores werden in verständliche Kriterien übersetzt; Fachbegriffe erhalten kontextnahe Hinweise.

### AST-Regelradar

Das **AST-Regelradar** zeigt den aktuellen mathematischen Ausdruck als zoombaren Syntaxbaum. Jeder Knoten entspricht einem konkreten Teilausdruck. Die Punkte am Regelkreis eines Knotens stehen für die dort tatsächlich enumerierten Regelanwendungen.

- Hover oder Tastaturfokus zeigt Regelname, Herkunft, Bindungen, Annahmen, lokalen Rewrite und vollständigen Folgeausdruck.
- Ein Klick wählt einen Zug zunächst nur zur Vorschau aus.
- Eine getrennte Aktion führt den Zug aus oder übernimmt ihn als nächsten Suchschritt.
- Grundregeln, Erweiterungsregeln und Makroregeln sind zusätzlich zu ihrer Farbe durch Text beziehungsweise Form unterscheidbar.
- Statuskennzeichnungen unterscheiden verfügbar, ausgewählt, angewandt, verworfen, Duplikat und wegen Annahmen abgelehnt.
- Makroregeln lassen sich in ihre atomaren Schritte aufklappen.
- Beim Wechsel zum globalen Suchgraphen oder Replay bleiben Baumposition und Kandidatenbezug erhalten.

Das Regelradar behauptet keine mathematische Vollständigkeit. Es zeigt die im aktuellen endlichen Suchkontext tatsächlich erzeugte Kandidatenmenge. Weitere fachliche Details stehen unter [AST-Regelradar](ast-rule-radar.md).

### Hilfe

Der Tab **Hilfe** enthält Syntax, Begriffe und kurze Bedienhinweise. Technische Request- und Response-Verträge gehören nicht hierher, sondern in Swagger/OpenAPI.

## Sicherheit

- Der Server bindet standardmäßig auf `127.0.0.1` und ist damit nur lokal erreichbar.
- Eine öffentliche Bind-Adresse muss bewusst konfiguriert werden.
- HTTPS und Authentisierung werden über die Serverkonfiguration beziehungsweise einen vorgeschalteten Reverse-Proxy eingerichtet.
- Bei einer Exposition über den lokalen Rechner hinaus müssen Zugriffsschutz, sichere Cookies oder Tokens und Transportverschlüsselung vor der Freigabe geprüft werden.

## Bekannte Grenzen

- Die UI ist ohne großes Frontend-Framework aufgebaut; ihre Assets liegen unter `app/src/main/resources/web/`.
- Nicht konfigurierte oder deaktivierte Backend-Funktionen müssen in der Oberfläche als nicht verfügbar erscheinen, statt rohe Fehlerantworten anzuzeigen.
- Die Workbench ist ein lokales Explorationswerkzeug und besitzt kein vollständiges Mehrbenutzer-, Rollen- oder Mandantenmodell.

## Mathematische Darstellung

Die Oberfläche verwendet eine gemeinsame Darstellungspipeline:

- Im Replay werden geänderte mathematische Teile zwischen zwei Schritten hervorgehoben; ein Wechsel der Richtung eines Vergleichszeichens erhält einen deutlichen Hinweis.
- Der Suchgraph rendert mathematische Knoteninhalte als KaTeX-Overlays über der interaktiven Graphfläche und hält sie beim Zoomen und Verschieben synchron.
- Das AST-Regelradar verbindet Knoten und Regelkreise mit einer zugänglichen Textalternative und deterministischer Tastatursteuerung.
- Strukturierte Layoutinformationen unterscheiden Inline-, Display- und ausgerichtete Darstellungen und liefern zusätzlich zugängliche ARIA-Beschriftungen.

Details zur Implementierung stehen in [Replay Mode](replay-mode.md), [Visual Search Graph](visual-search-graph.md) und [AST-Regelradar](ast-rule-radar.md).

## Technische API-Zuordnung

Die grafischen Funktionen verwenden intern die REST-API. Für direkte Integrationen und technische Tests ist die Swagger/OpenAPI-Dokumentation der laufenden Installation verbindlich. Dieses Handbuch nennt deshalb keine HTTP-Pfade, Payloads oder Statuscode-Verträge. Siehe [Dokumentationskonvention](documentation-conventions.md).
