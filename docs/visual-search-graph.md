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

## Roadmap (vollständig umgesetzt in diesem PR)

1. ✅ **HTTP API** – `GET /api/search-graph`, `GET /api/paths?sort=…`, `GET /api/paths/{id}/replay`.
2. ✅ **Macro-rule mining** & emergent-identity report (`POST /api/identities/{id}/promote`).
3. ✅ **Didactic ranking** – `TeachingPathScorer` plus `sort=teaching`.
4. ✅ **Exports** – `search-graph.{json,graphml,mmd}`, `best-path.md`, `identity-report.tex`.
5. ✅ **UI** – Graph (Mermaid), Replay, Identitäten, Dashboard. Cytoscape-Vendoring
   ist als spätere Option dokumentiert, aber nicht enthalten, um die Repo-Größe
   nicht aufzublähen.
