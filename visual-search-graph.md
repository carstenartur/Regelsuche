# Visual Search Graph

> Mathematik wird als interaktiver Suchraum sichtbar: Knoten sind Ausdrücke,
> Kanten sind Umformungen, Pfade sind Rechenwege, Cluster zeigen Regelstrukturen,
> und gefundene Makroregeln entstehen aus wiederkehrenden Pfaden.

Dieses Dokument beschreibt die Datenbasis, die grafische Bedienung und die technische Umsetzung des Visual Search Graph. Die vollständigen HTTP-Verträge stehen in Swagger/OpenAPI; Methoden-, Pfad- und Payload-Tabellen werden hier nicht dupliziert.

## Bedienung im Graph-Tab

1. Im Tab **Workbench** eine Suche starten oder im Demo-Bereich eine Aufgabe auswählen.
2. Den Tab **Graph** öffnen und **Graph laden** wählen.
3. Als Ansicht zunächst **Semantische Erklärung** verwenden. Sie reduziert technische Normalisierungsschritte und hebt den fachlich relevanten Rechenweg hervor.
4. Bei Bedarf auf **Nur Hauptpfad**, **Complexity Map** oder **Rohgraph** wechseln.
5. Über die Schalter triviale Normalisierungen, alternative Pfade und Varianten in Clustern ein- oder ausblenden.
6. Im interaktiven Modus zoomen, verschieben und einen Knoten auswählen. Der Inspector zeigt Ausdruck, Bewertung, Rolle im Suchraum und zugehörige Umformungen.
7. Für eine lineare Erklärung in die Tabs **Pfade** oder **Replay** wechseln; für Weiterverarbeitung den Tab **Exporte** verwenden.

**Sichtbares Ergebnis:** Die Oberfläche zeigt nicht nur ein Endergebnis, sondern macht Hauptpfad, Alternativen, Sackgassen, Cluster, Makroschritte und Komplexitätsunterschiede nachvollziehbar.

**Technische Zuordnung:** Swagger-Bereiche *Search Graph*, *Graph*, *Paths*, *Replay*, *Identities* und *Exports*.

## DTO-Schicht (`de.regelsuche.api.searchgraph`)

| Typ | Zweck |
| --- | --- |
| `SearchGraphDto` | Top-Level-Hülle mit Knoten, Kanten, Clustern, Ansichtsmetadaten und Statistiken. |
| `SearchGraphNodeDto` | Ausdrucksknoten mit kanonischer Darstellung, LaTeX, Bewertung, Tiefe, Besuchszahl, Best-Path-/Dead-End-Markierung, Kandidatenstatus und Clusterzuordnung. |
| `SearchGraphEdgeDto` | Umformungskante mit Regel, Bewertungsänderung, Annahmen, Pfadzuordnung und Äquivalenzerhaltung. |
| `SearchGraphClusterDto` | Gruppe strukturell oder semantisch zusammengehöriger Knoten. |
| `SearchGraphStatsDto` | Kennzahlen für Dashboard und Qualitätsanzeige. |

## Assembler

`SearchGraphAssembler.assemble(snapshot, successes, candidates, macroRuleCount)` baut den vollständig befüllten `SearchGraphDto` aus dem In-Memory-Suchzustand:

- **Bester Pfad:** Rückwärtsverfolgung von der stärksten gefundenen Vereinfachung über die jeweils beste verbessernde Eingangskante.
- **Sackgasse:** Knoten ohne verbessernde ausgehende Kante, der kein erfolgreiches Ziel ist.
- **Cluster:** Wiederkehrende Regel- oder Strukturzusammenhänge werden zu einer sichtbaren Gruppe zusammengeführt.
- **Mathematische Darstellung:** LaTeX und strukturierte Layoutinformationen werden über die gemeinsame Präsentationspipeline erzeugt; es gibt keinen zweiten, nur für den Graphen gepflegten Parser.

## Statistiken und Dashboard

`SearchGraphStatsService.compute(snapshot, successes, candidates, macroRuleCount)` erzeugt die Kennzahlen, die im Tab **Dashboard** erscheinen:

- Anzahl unterschiedlicher Ausdrücke und erzeugter Umformungen,
- Sackgassen und maximal erreichte Tiefe,
- durchschnittlicher Verzweigungsfaktor,
- beste gefundene Verbesserung,
- Regelhäufigkeit und nützlichste Regeln,
- Anzahl der Kandidaten und Makroregeln.

Die grafische Darstellung übersetzt diese Daten in verständliche Kacheln und Ranglisten. Interne Feldnamen sind nur für Tests und Entwicklung relevant und gehören nicht in nutzerseitige Beschriftungen.

## Zusammenhängende GUI-Funktionen

### Graph

Der Tab rendert den Suchraum mit Cytoscape als zoom-, verschieb- und anklickbare Visualisierung. Mathematische Ausdrücke erscheinen als KaTeX-Overlays. Wenn die interaktive Darstellung nicht initialisiert werden kann, bleibt eine Mermaid-Quelltextansicht als Fallback sichtbar.

### Replay

Der Tab **Replay** überführt einen ausgewählten Graphpfad in eine lineare Schrittfolge. Play, Pause und Einzelschritt machen die Reihenfolge der Umformungen nachvollziehbar; mathematische Änderungen und kritische Annahmen werden direkt am Schritt markiert.

### Identitäten

Der Tab **Identitäten** zeigt wiederkehrende Pfadstrukturen als fachlich prüfbare Karten. Eine Übernahmeaktion steht erst am Ende der Evidenzprüfung und führt kontrolliert in das Regel-Inventar.

### Dashboard

Das **Dashboard** fasst Struktur und Aufwand des Suchraums zusammen. Es hilft zu erkennen, ob ein Ergebnis aus einem kleinen direkten Weg oder einem breiten Suchraum mit vielen Alternativen hervorgegangen ist.

### Exporte

Der Tab **Exporte** bietet Graphformate nach ihrem Zweck an: JSON für maschinelle Verarbeitung, Mermaid für textbasierte Diagramme, GraphML für Graphwerkzeuge und berichtsorientierte Formate für den besten Pfad und die erkannten Strukturen.

## Semantischer Erklärgraph

Der Rohgraph bleibt für technische Analyse verfügbar. Die nutzerseitige Standardansicht ist jedoch der semantische Erklärgraph.

Die Backend-Aufbereitung besteht aus:

1. Kanonisierung und Äquivalenz-Clustering,
2. Unterdrückung oder Zusammenfassung signalärmerer Normalisierungsschritte,
3. gewichteter Auswahl des Hauptpfads,
4. Projektion wiederkehrender Teilpfade als aufklappbare Makroschritte,
5. Berechnung semantischer Layoutkoordinaten.

Im Graph-Tab wird diese progressive Offenlegung über folgende sichtbare Optionen gesteuert:

- **triviale Normalisierungen anzeigen**,
- **alternative Pfade anzeigen**,
- **Varianten in Clustern anzeigen**,
- Auswahl des Ansichtsmodus.

Die Dokumentation beschreibt die Wirkung dieser Bedienelemente. Die zugrunde liegenden technischen Operationen und Export-Schemata werden ausschließlich in Swagger/OpenAPI gepflegt.

## Implementierungsstand

- Die Suchgraph-Datenstruktur, Pfadsortierung, Replay-Daten und Identitätsberichte sind implementiert.
- Makroregel-Mining und der kontrollierte Übergang in das Inventar sind in die zusammenhängenden GUI-Flows eingebunden.
- Didaktisches Ranking beeinflusst Pfadwahl und Darstellung.
- JSON-, Mermaid-, GraphML-, Markdown- und LaTeX-Ausgaben stehen über die grafische Exportoberfläche zur Verfügung.
- Graph, Replay, Identitäten und Dashboard sind als sichtbare Tabs implementiert.
- Cytoscape und KaTeX werden als selbst gehostete Web-Assets ausgeliefert; externe CDN-Zugriffe sind nicht erforderlich.

## KaTeX-Knoten-Overlays

Der Graph-Tab zeichnet Knotenbeschriftungen nicht als einfachen Canvas-Text, sondern als absolut positionierte KaTeX-HTML-Overlays über der Cytoscape-Fläche:

- Das Backend reicht die mathematische LaTeX-Darstellung durch Assembler, Serialisierung und Repository-Backends weiter.
- Das Frontend reagiert auf Layout-, Pan-, Zoom- und Positionsereignisse und projiziert die Bounding-Box jedes Knotens in Containerkoordinaten.
- Best-Path- und Dead-End-Markierungen spiegeln die Graphsemantik auch in den Overlays wider.
- Kantenbeschriftungen können dieselbe Pipeline verwenden; standardmäßig bleiben zunächst die Knoten sichtbar, um die initiale Layoutphase nicht zu überlasten.

## Strukturierte Layout-Beschreibung

Knoten und Kanten liefern zusätzlich eine strukturierte `MathLayout`-Beschreibung mit ARIA-Label. Die KaTeX-Overlays verwenden diese Information für zugängliche Screenreader-Texte, ohne die sichtbare mathematische Darstellung zu verändern.

## Tests

- `SearchGraphNodeDtoTest` und Repository-Roundtrip-Tests sichern die mathematische Knotendarstellung.
- `WebUiMathPipelineTest` pinnt Overlay-Schicht, Ereignisbindung und Renderpfad.
- `GraphOverlayBrowserFlowTest` prüft die echte Bedienung im Browser mit Playwright.
- Browserflows für Pfade, Replay, Identitäten und Exporte sichern die zusammenhängende Nutzerreise statt isolierter Request-Beispiele.

Weitere Details stehen in [Replay Mode](replay-mode.md), [Macro Rules](macro-rules.md) und [Didactic Ranking](didactic-ranking.md).
