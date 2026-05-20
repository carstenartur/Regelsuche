# Follow-up Roadmap

Diese Datei sammelt die nächsten geplanten Erweiterungen, die auf dem
Visual-Search-Graph aufsetzen und im aktuellen PR bereits vorbereitet sind.

## §2 Persistenz

* **`SearchGraphRepository`** Interface mit drei produktiven Backends:
  * `InMemorySearchGraphRepository` (Default für Tests und Einzelläufe)
  * `JsonFileSearchGraphRepository` (atomic write-replace, robust gegen Crash)
  * `Neo4jSearchGraphRepository` (nutzt den vorhandenen `neo4j-java-driver`
    der bereits Bestandteil des Projekts ist – kein neues Dependency).
* **`SearchGraphRecord`** bündelt `SearchGraphDto` zusammen mit Replays,
  Macro-Regeln, Identitäten, Exports, Zeitstempel, Suchprofil und gewählten
  Regel-Domänen, sodass Sessions wieder geladen werden können.
* **`SearchGraphRecordCodec`** stellt eine vollständige Round-Trip-JSON-API
  zur Verfügung (`toJson`, `fromJson`, `fromMap`).

Nächste Schritte:

* Workbench-CLI-Option `--persist-search-graphs` mit Auswahl des Repositorys.
* Web-Endpunkt `POST /api/search-graphs` / `GET /api/search-graphs/{id}` zum
  expliziten Speichern und Reloaden.
* Cypher-basierte native Graph-Ablage (Knoten + Kanten als echte Neo4j-Nodes
  inkl. Indizes) anstatt der aktuellen JSON-Body-Speicherung.

## §3 Interaktive Cytoscape-Workbench

Aktueller Stand: Cytoscape und MathJax werden vom CDN geladen. Bei Ausfall
schaltet die UI automatisch auf die Mermaid-Quelltext-Ansicht zurück. Der
Inspector zeigt alle DTO-Felder eines Knotens bzw. einer Kante und rendert
LaTeX über MathJax.

Nächste Schritte:

* Persistente Layout-Auswahl (breadthfirst / cola / dagre).
* Highlight-Pfade für „compare links/rechts" direkt im Cytoscape-Canvas.
* Export des aktuell sichtbaren Subgraphen als PNG.

## §4 Cluster

* **`ClusterType`** modelliert die verschiedenen Cluster-Arten
  (`RULE_USAGE`, `MACRO_SEQUENCE`, `STRUCTURAL_PATTERN`, `SCORE_BASIN`,
  `PROOF_STATUS`).
* `SearchGraphClusterDto` trägt jetzt zusätzlich `supportingPathIds` und
  einen Kohäsions-Score und behält den 3-Argument-Konstruktor für rückwärts-
  kompatible Rule-Cluster.
* `MacroSequenceClusterer` und `StructuralExpressionClusterer` werden vom
  `SearchGraphAssembler` aufgerufen, wenn Transformationen verfügbar sind.

Nächste Schritte:

* `ScoreBasinClusterer` und `ProofStatusClusterer` (bislang nur Enum vorhanden).
* Verfeinerte Anti-Unifikation für `MACRO_SEQUENCE`-Pattern.

## §5 AST-LaTeX/MathML

* **`AstLatexRenderer`** parst Eingaben mit `ExpressionParser` und rendert
  Brüche (`\frac`), Wurzeln (`\sqrt`), Potenzen (`^`), trigonometrische und
  logarithmische Funktionen sowie Gleichungen/Gleichungssysteme korrekt.
  Fällt nur bei Parsing-Fehlern auf die ursprüngliche
  `*`→`\cdot`-Substitution zurück.
* **`AstMathMlRenderer`** liefert Presentation-MathML (`<mfrac>`, `<msup>`,
  `<msqrt>`, …) für Konsumenten, die kein LaTeX verarbeiten.
* `LaTeXMathRenderer` delegiert vollständig an `AstLatexRenderer`, sodass
  alle bestehenden Aufrufstellen automatisch profitieren.

Nächste Schritte:

* AST-bewusster Markdown-Renderer (Brüche als Unicode-Glyphen für Plain-Text).
* `\mathrm{e}`-Erkennung & Spezialformatierung für `exp`/`log`-Argumente.

## §6 Pfadvergleich

* **`PathComparisonService`** und **`PathComparisonDto`** mit Endpunkt
  `GET /api/paths/compare?left=...&right=...` sowie zugehörigem
  Workbench-Tab „Vergleich". Liefert gemeinsame Knoten/Regeln,
  Step-Diffs, Teaching-Scores, Annahmen-Counts und qualitative Empfehlungen.

Nächste Schritte:

* N-Wege-Vergleich (mehr als zwei Pfade gleichzeitig).
* SVG-Diff-Rendering im Workbench-Tab.

## §7 Gefilterte Exporte

* **`SearchGraphFilter`** versteht eine kompakte Query-Syntax
  (`bestPath`, `hideDeadEnds`, `ruleKind`, `rule`, `proofStatus`,
  `minScoreDelta`, `maxScoreDelta`, `cluster`, `path`) und wird bei
  `/api/search-graph?filter=…` und
  `/api/exports/search-graph.json?filter=…` ausgewertet.
* Neue Sub-Resource-Exporte: `/api/exports/cluster/{id}.md`,
  `/api/exports/path/{id}.tex` und `/api/exports/identity/{id}.md`.

## §9 CodeQL & Tests

* Erweiterte Unit-Tests decken Codecs, Repositories, Cluster und
  Path-Comparison ab. CodeQL läuft ohne neue High-Severity-Findings.
