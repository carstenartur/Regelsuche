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

## Roadmap (planned follow-up PRs)

1. **HTTP API** – `GET /api/search-graph`, `GET /api/paths`, `GET /api/paths/{id}/replay`.
2. **Macro-rule mining** & emergent-identity report (`POST /api/identities/{id}/promote`).
3. **Didactic ranking** – `TeachingPathScorer` plus `sort=teaching`.
4. **Exports** – `search-graph.{json,graphml,mmd}`, `best-path.md`, `identity-report.tex`.
5. **UI** – Cytoscape.js graph tab, replay tab, alternative-paths comparison,
   identities, dashboard. Mermaid as fallback.
