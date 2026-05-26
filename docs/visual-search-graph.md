# Visual Search Graph

> Mathematik wird als interaktiver Suchraum sichtbar: Knoten sind Ausdrücke,
> Kanten sind Umformungen, Pfade sind Rechenwege, Cluster zeigen Regelstrukturen,
> und gefundene Makroregeln entstehen aus wiederkehrenden Pfaden.

This document describes the **data foundation** of the Visual-Search-Graph
feature. HTTP endpoints, UI tabs, replay mode, export targets and macro-rule
mining are tracked in their own follow-up documents (`replay-mode.md`,
`macro-rules.md`, `didactic-ranking.md`) and will be added incrementally.

## DTO Layer (`de.regelsuche.api.searchgraph`)

| Type                       | Purpose                                                                       |
|----------------------------|-------------------------------------------------------------------------------|
| `SearchGraphDto`           | Top-level envelope: nodes, edges, clusters, stats.                            |
| `SearchGraphNodeDto`       | One expression node (`id`, `expression`, `latex`, `score`, `depth`, `visitedCount`, `isBest`, `isDeadEnd`, `candidateStatus`, `clusterId`). |
| `SearchGraphEdgeDto`       | One rewrite edge (`from`, `to`, `ruleId`, `ruleKind`, `scoreDelta`, `assumptions`, `pathIds`, `equivalencePreserving`). |
| `SearchGraphClusterDto`    | Group of nodes sharing a macro-rule / structural cluster.                     |
| `SearchGraphStatsDto`      | Dashboard tile data: `nodesVisited`, `edgesGenerated`, `deadEnds`, `bestScore`, `averageBranchingFactor`, `maxDepthReached`, `ruleUsageFrequency`, `mostUsefulRules`, `candidateCount`, `macroRuleCount`. |

## Assembler

`SearchGraphAssembler.assemble(snapshot, successes, candidates, macroRuleCount)`
builds a fully populated `SearchGraphDto` from the existing in-memory data:

- **Best path** – traced backwards from the simplification with the highest
  improvement, following the strongest improving incoming edge of each node.
- **Dead-end** – a node with no improving outgoing edge that is not a
  success terminal.
- **Cluster id** – nodes reached via a rule that occurs multiple times share
  a cluster. Canonical-hash based structural clustering is a planned
  extension.
- **LaTeX** – rendered via the existing `LaTeXMathRenderer`; no new LaTeX
  parser is introduced.

## Stats

`SearchGraphStatsService.compute(snapshot, successes, candidates, macroRuleCount)`
returns the aggregate `SearchGraphStatsDto` used by the (future) dashboard
tile view. Currently:

- `nodesVisited` – distinct expressions appearing in nodes or any edge endpoint.
- `edgesGenerated` – total number of recorded rewrites.
- `deadEnds` – nodes with no improving outgoing edge that are not success terminals.
- `bestScore` – maximum recorded improvement.
- `averageBranchingFactor` – `edges / |nodes with outgoing edges|`.
- `maxDepthReached` – maximum `depth` over all edges.
- `ruleUsageFrequency` – `ruleId → count`, sorted descending by count.
- `mostUsefulRules` – rules ranked by accumulated improvement, ties broken by frequency.
- `candidateCount` – size of the supplied mined-rule candidate list.
- `macroRuleCount` – injected by the caller (filled in by the macro-rule miner
  in a follow-up step).

## HTTP API

Alle Endpoints werden vom eingebetteten `WebWorkbenchServer` ausgeliefert (kein
externes Framework):

| Endpoint                                    | Zweck                                                        |
|---------------------------------------------|--------------------------------------------------------------|
| `GET /api/search-graph`                     | `SearchGraphDto` als JSON.                                   |
| `GET /api/paths?sort=score|length|teaching|proof&limit=N` | Sortierte / begrenzte Pfadliste.                |
| `GET /api/paths/{id}/replay`                | `PathReplayDto` – Schritt-für-Schritt-Replay.                |
| `GET /api/identities`                       | Emergent-identity-Report (Makroregel-Kandidaten).            |
| `POST /api/identities/{id}/promote`         | Speichert eine Makroregel als `ReusableRule` ins Inventar.   |
| `GET /api/exports/search-graph.json`        | Suchgraph als JSON (gleiche Form wie API).                   |
| `GET /api/exports/search-graph.mmd`         | Suchgraph als Mermaid (Cytoscape-Fallback).                  |
| `GET /api/exports/search-graph.graphml`     | Suchgraph als GraphML (yEd, Gephi).                          |
| `GET /api/exports/best-path.md`             | Bester Pfad als Markdown.                                    |
| `GET /api/exports/identity-report.tex`      | Konsolidierter LaTeX-Bericht.                                |

Siehe `docs/replay-mode.md`, `docs/macro-rules.md` und `docs/didactic-ranking.md`
für die jeweiligen Teil-Features.

## UI-Tabs

Erweiterung von `resources/web/index.html` und `app.js`:

- **Graph** – Mermaid-Rendering des Suchgraphen (Klassen `best` und `deadend`).
  Cytoscape-Vendoring ist optional und wegen der Größe (~1 MB) bewusst nicht
  enthalten.
- **Replay** – Pfad-Dropdown, Play/Pause/Step, LaTeX-Anzeige.
- **Identitäten** – Karten je Makroregel mit "Als Regel übernehmen"-Button
  (`POST /api/identities/{id}/promote`).
- **Dashboard** – Tile-Ansicht der `SearchGraphStatsDto`-Werte plus Top-Regeln.

## Semantic Discovery Explanation Graph

The raw visual search graph remains available for debugging, but the default
user-facing graph is now a semantic explanation graph.

New endpoint and exports:

- `GET /api/search-graph/semantic`
- `GET /api/exports/search-graph-semantic.json`
- `GET /api/exports/search-graph-semantic.mmd`

Semantic preparation pipeline (backend):

1. canonicalization + equivalence clustering,
2. low-signal rewrite suppression/collapse,
3. main-path selection with weighted ranking,
4. macro-move edge projection with expandable atomic metadata,
5. semantic layout coordinates (`view.layout.positions`).

The Graph tab defaults to **Semantische Erklärung** and supports progressive
disclosure via:

- `triviale Normalisierungen anzeigen`,
- `alternative Pfade anzeigen`,
- `Varianten in Clustern anzeigen`,
- mode selector (`semantic`, `mainPath`, `complexity`, `raw`).

## Roadmap (raw graph stack)

1. ✅ **HTTP API** – `GET /api/search-graph`, `GET /api/paths?sort=…`, `GET /api/paths/{id}/replay`.
2. ✅ **Macro-rule mining** & emergent-identity report (`POST /api/identities/{id}/promote`).
3. ✅ **Didactic ranking** – `TeachingPathScorer` plus `sort=teaching`.
4. ✅ **Exports** – `search-graph.{json,graphml,mmd}`, `best-path.md`, `identity-report.tex`.
5. ✅ **UI** – Graph (Mermaid), Replay, Identitäten, Dashboard. Cytoscape-Vendoring
   ist als spätere Option dokumentiert, aber nicht enthalten, um die Repo-Größe
   nicht aufzublähen.

## Stage 4 — KaTeX-Knoten-Overlays

Seit Stage 4 zeichnet der Graph-Tab die Knoten-Labels nicht mehr als
Plain-Text in den Cytoscape-Canvas, sondern als absolut positionierte
KaTeX-HTML-Overlays in einem `.graph-overlay-layer`-Wrapper, der über
dem Canvas liegt:

* Backend: `SearchGraphNodeDto.expressionLatex` (durch
  `MathPresentation.latex(...)` geroutet) wird vom
  `SearchGraphAssembler`, `SearchGraphJsonSerializer`,
  `SearchGraphRecordCodec` und den drei Repository-Backends
  (`InMemorySearchGraphRepository`, `JsonFileSearchGraphRepository`,
  `Neo4jSearchGraphRepository`) durchgereicht.
* Frontend: das `graphMathOverlay`-Modul in `app.js` hängt sich an die
  Cytoscape-Ereignisse `layoutstop`, `pan`, `zoom`, `position` und
  projiziert die Bounding-Box jedes Knotens zurück in
  Container-Koordinaten. Pro Knoten existiert ein
  `<div class="graph-node-math" data-node-id="…">` mit gerendertem
  KaTeX-Ausdruck; CSS-Transitions
  (`transition: transform 200ms ease, opacity 200ms ease;`) halten die
  Bewegung weich.
* Farb-Tokens: `.graph-node-math.is-best` und `.is-dead-end` spiegeln
  die bestehende Cytoscape-Style-Logik wider.
* Optional: Kanten-Labels (`ruleLatex`) erhalten dieselbe Behandlung,
  sobald der Canvas-Container das Attribut
  `data-graph-math-edges` trägt; per Default sind nur die Knoten als
  KaTeX-Overlay aktiv, um die Performance der initialen Layout-Phase
  nicht zu gefährden.

## Stage 5 — Strukturierte Layout-Beschreibung

`SearchGraphNodeDto.layout()` und `SearchGraphEdgeDto.layout()` liefern
eine `MathLayout` mit ARIA-Label (aus `AstAriaRenderer`). Die
KaTeX-Overlays konsumieren das `aria-label` zusätzlich als Screenreader-
Text, ohne den sichtbaren KaTeX-Render zu ändern.

## Tests

* Stage 4 Backend: `SearchGraphNodeDtoTest`,
  `SearchGraphRepositoryTest#codecRoundTripsExpressionLatexForNodes`.
* Stage 4 Frontend: `WebUiMathPipelineTest#appJsInstallsKatexGraphOverlay`
  pinnt das `graphMathOverlay`-Modul, den `.graph-overlay-layer`-Wrapper
  und den `renderMath()`-Aufruf nach `layoutstop`.
* Stage 4 E2E: `de.regelsuche.e2e.GraphOverlayBrowserFlowTest` (Playwright,
  läuft unter `:app:e2eTest`).
