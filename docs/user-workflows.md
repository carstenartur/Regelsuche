# User Workflows

Diese Seite beschreibt die wichtigsten Bedienabläufe der Regelsuche-Web-Workbench. Die Schritte verwenden ausschließlich sichtbare Tabs, Felder und Aktionen. Die zugrunde liegenden REST-Verträge werden nicht dupliziert; Methoden, Pfade, Schemata und Statuscodes stehen in Swagger/OpenAPI.

---

## 1. Lernen und Lehren — „Zeige mir einen nachvollziehbaren Schulweg“

**Ziel:** Einen Ausdruck eingeben, mehrere Herleitungen prüfen, einen gut erklärbaren Weg auswählen und das Ergebnis weiterverwenden.

1. Die Web-Workbench öffnen und im Tab **Workbench** bleiben.
2. Den Ausdruck, zum Beispiel `(x+3)^2`, in das Feld **Ausdruck** eingeben.
3. Als **Profil** die didaktische Suche wählen oder unter **Ziel** die schulbuchnahe Darstellung auswählen.
4. **Suche starten** wählen.
5. Im Tab **Pfade** die gefundenen Rechenwege vergleichen und als Darstellung **Schulbuch** oder **Schritte** wählen.
6. Einen Pfad öffnen und im Tab **Replay** schrittweise abspielen. Hervorgehobene Änderungen, Regelhinweise und Annahmen prüfen.
7. Im Tab **Exporte** Markdown für Dokumentation oder LaTeX für mathematische Texte herunterladen.

**Sichtbares Ergebnis:** Ein vollständiger Rechenweg mit Zwischenschritten, verständlicher Erklärung, Annahmen und passendem Export.

**Technische Zuordnung:** Swagger-Bereiche *Search*, *Paths*, *Replay*, *Explain* und *Exports*.

---

## 2. Discovery — „Finde wiederkehrende Regeln und Strukturen“

**Ziel:** Einen breiteren Suchraum untersuchen, Regelkandidaten bewerten und wiederverwendbare Strukturen erkennen.

1. Im Tab **Workbench** das Profil **Erkundung / Discovery** auswählen.
2. Passende **Regel-Domänen** aktivieren und mehrere verwandte Ausdrücke nacheinander untersuchen.
3. Im Tab **Graph** die semantische Erklärung öffnen. Alternative Pfade und Varianten in Clustern nur bei Bedarf einblenden.
4. Im **AST-Regelradar** einzelne Baumpositionen untersuchen und prüfen, welche lokalen Grund-, Erweiterungs- oder Makroregeln dort konkret anwendbar waren.
5. Im Tab **Regelkandidaten** Pattern, Beispielanzahl, Bewertung, Status, Proof-Status und Annahmen vergleichen.
6. Im Tab **Identitäten** prüfen, ob eine wiederkehrende Struktur hinreichend belegt und zur Übernahme geeignet ist.
7. Im Tab **Suchgedächtnis** unter den universellen Mustern kontrollieren, welche kanonischen Zustände in mehreren Läufen wiederkehren und welche Regeln dazu beitragen.
8. Im Tab **Inventar** übernommene Regeln auffinden, aktivieren oder mit Tags ordnen.

**Sichtbares Ergebnis:** Eine nachvollziehbare Kette von konkreten lokalen Zügen über Suchpfade, Kandidaten und Identitäten bis zum kontrollierten Regelbestand.

**Technische Zuordnung:** Swagger-Bereiche *Search*, *Search Graph*, *Rule Radar*, *Candidates*, *Identities*, *Memory* und *Inventory*.

---

## 3. Alternativen vergleichen — „Gib mir nicht nur ein Ergebnis“

**Ziel:** Unterschiedliche gültige Ergebnisformen und Rechenwege nach fachlichen oder didaktischen Kriterien vergleichen.

1. Eine Suche mit dem gewünschten Profil starten.
2. Im Tab **Pfade** mehrere Rechenwege öffnen und ihre Länge, Regeln und Annahmen prüfen.
3. Im Tab **Graph** zwischen **Semantische Erklärung**, **Nur Hauptpfad**, **Complexity Map** und **Rohgraph** wechseln.
4. Über **Ziel** eine andere Präferenz wählen, beispielsweise **Vereinfachen**, **Faktorisieren**, **Schulweg**, **Beweisfreundlich** oder **Numerisch stabil**.
5. Im Tab **Vergleich** zwei Ergebnisse oder Pfade nebeneinanderstellen.
6. Das Ergebnis auswählen, das zum konkreten Zweck passt, statt nur die formal kürzeste Form zu übernehmen.

**Sichtbares Ergebnis:** Unterschiede in Ergebnisform, Rechenweg, Komplexität, Annahmen und Eignung werden direkt vergleichbar.

**Technische Zuordnung:** Swagger-Bereiche *Search*, *Paths*, *Search Graph* und *Compare*.

---

## 4. Proof-Workflow — „Einreichen, verfolgen, Artefakte prüfen“

**Ziel:** Eine Identität oder Regel asynchron mit Lean beziehungsweise SMT prüfen und die erzeugten Artefakte nachvollziehen.

1. Nach dem ersten Suchlauf den Tab **Proof-Jobs** öffnen.
2. Linkes und rechtes Pattern, optionale Annahmen und die Priorität eingeben.
3. **Job einreichen** wählen.
4. Den neuen Eintrag in der Jobliste verfolgen und bei Bedarf mit **Aktualisieren** den Zustand neu laden.
5. Einen nicht mehr benötigten laufenden Auftrag über **Abbrechen** stoppen.
6. Bei einem abgeschlossenen Job **Artefakte** öffnen. Beweisskript, Standardausgabe, Fehlerausgabe und Metadaten getrennt prüfen oder herunterladen.

**Sichtbares Ergebnis:** Ein persistenter, verständlich gekennzeichneter Jobzustand mit reproduzierbarem Artefakt-Bundle. Ein Browser-E2E-Test belegt den vollständigen Bedienfluss; reale Solverläufe werden zusätzlich unabhängig getestet.

**Technische Zuordnung:** Swagger-Bereich *Proof Jobs*. Konfiguration und Betriebsgrenzen beschreibt [Proof Workbench](proof-workbench.md).

---

## 5. Qualität bewerten — „Welche Szenarien funktionieren wirklich?“

**Ziel:** Den aktuellen Funktionsstand nicht anhand einzelner Erfolgsmeldungen, sondern anhand reproduzierbarer Szenarien und Qualitätsmetriken beurteilen.

1. Den Tab **Benchmark** öffnen.
2. Den Benchmark-Lauf über die dort angebotene Aktion starten oder aktualisieren.
3. Pro Szenario den Ampelstatus und die fachlichen Prüfpunkte betrachten: Wurde ein Ergebnis gefunden, stimmt es mit der Erwartung überein und welcher Proof-Status wurde erreicht?
4. Zusätzlich Suchaufwand und Strukturmetriken wie besuchte beziehungsweise verworfene Zustände, E-Graph-Klassen, Einsparungen und verwendete gelernte Regeln vergleichen.
5. Auffällige Szenarien über **Workbench**, **Graph**, **Pfade** und **Replay** gezielt nachvollziehen.

**Sichtbares Ergebnis:** Eine Qualitätsübersicht, die Erfolg, Belegstatus und Suchaufwand zusammenführt und problematische Szenarien in konkrete Untersuchungsschritte überführt.

**Technische Zuordnung:** Swagger-Bereich *Benchmark*.

---

## 6. Lokale Regelanwendungen untersuchen — „Welche Züge sind genau hier möglich?“

**Ziel:** Einen mathematischen Ausdruck als AST betrachten und an einer konkreten Baumposition nachvollziehen, welche Regelanwendungen wirklich verfügbar sind und was sie bewirken würden.

1. Im Tab **Workbench** einen Ausdruck eingeben oder eine Demo ausführen.
2. Den Tab **AST-Regelradar** öffnen.
3. Den Ausdruck als zoombaren Baum untersuchen und einen AST-Knoten auswählen.
4. Die Punkte am Regelkreis des Knotens mit Maus oder Tastatur fokussieren. Regelname, Herkunft, Bindungen, Annahmen, lokaler Vorher-/Nachher-Teilbaum und vollständiger Folgeausdruck prüfen.
5. Einen Kandidaten anklicken, um ihn zunächst nur auszuwählen und als Vorschau zu betrachten.
6. Erst über die getrennte Ausführungsaktion den lokalen Zug anwenden oder als nächsten Suchschritt übernehmen.
7. Statuskennzeichnungen vergleichen: verfügbar, ausgewählt, angewandt, verworfen, als Duplikat geprunt oder wegen Annahmen abgelehnt.
8. Bei einer Makroregel die atomaren Teilschritte aufklappen und zwischen Regelradar, Suchgraph und Replay wechseln, ohne die gewählte Baumposition zu verlieren.

**Sichtbares Ergebnis:** Ein positionsgebundener, endlicher Katalog konkreter Regelanwendungen mit nachvollziehbarer Vorschau und Suchstatus. Ein Punkt steht für eine ausführbare Anwendung an genau einem AST-Knoten, nicht nur für einen abstrakten Regelnamen.

**Technische Zuordnung:** Swagger-Bereich *Rule Radar*. Fachliche Invarianten, Datenmodell und Grenzen beschreibt [AST-Regelradar](ast-rule-radar.md).

---

## Zielauswahl im Workbench-Tab

Die Auswahl **Ziel** beeinflusst, welche Ergebnisform bevorzugt wird, ohne dass Nutzerinnen und Nutzer einen technischen Request bearbeiten müssen.

| Sichtbare Auswahl | Geeignet für |
| --- | --- |
| **Vereinfachen** | Kurze oder kleine Ergebnisform als allgemeiner Standard |
| **Faktorisieren** | Faktorisierte statt expandierter Polynomform |
| **Schulweg** | Kleine Schritte, überschaubare Koeffizienten und geringe Verschachtelung |
| **Beweisfreundlich** | Formen, die Fallunterscheidungen oder formale Beweise erleichtern |
| **Numerisch stabil** | Gut konditionierte beziehungsweise Horner-artige Formen |

Bleibt das Feld auf **vom Profil**, verwendet die Workbench die zum gewählten Profil passende Voreinstellung.

## Einstieg für neue Nutzerinnen und Nutzer

Vor der ersten Suche zeigt die Startseite bewusst nur den primären Einstieg und den Demo-Bereich. Weiterführende Tabs wie **Graph**, **Replay**, **Proof-Jobs**, **Benchmark**, **AST-Regelradar** und **Exporte** werden nach dem ersten Suchlauf beziehungsweise nach dem Start einer Demo sichtbar. Dadurch beginnt der Ablauf mit einer mathematischen Aufgabe statt mit einer technischen Funktionsliste.

## REST-Nutzung

Direkte Integrationen verwenden die Swagger/OpenAPI-Dokumentation der laufenden Installation. Diese Seite bleibt auch dann unverändert, wenn sich ein HTTP-Pfad oder ein JSON-Schema ändert, solange der sichtbare GUI-Ablauf gleich bleibt. Die verbindliche Regel steht in der [Dokumentationskonvention](documentation-conventions.md).
