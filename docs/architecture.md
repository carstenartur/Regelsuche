# Architektur

Regelsuche ist ein Gradle-Multimodulprojekt (`app`-Modul) mit fünf Hauptbereichen:

| Paket | Verantwortlichkeit |
| --- | --- |
| `de.regelsuche.ast`, `parse` | AST-Modell für Terme/Gleichungen und Parser/Formatter. |
| `de.regelsuche.transform` | Atomare Rewrite-Regeln (`RewriteRule`, `PatternRewriteRule`, `AstRewriteTransformationEngine`). |
| `de.regelsuche.search` | Such-Heuristiken (`SearchHeuristic`, `SearchProfile`) und Strategien (`BestFirst`, `Beam`, `AStar`, `MCTS`, `Hybrid`, `RandomMonteCarlo`). |
| `de.regelsuche.discovery`, `mining`, `inventory` | Modelle für entdeckte Umformungen, Regel-Kandidaten und das wiederverwendbare Regelinventar. |
| `de.regelsuche.export`, `explain`, `api`, `web` | Renderer (Markdown/LaTeX/Mermaid/JSON), Erklärservice, Query-Services und eingebetteter Web-Workbench. |

Alle Such- und Entdeckungspfade laufen über atomare Regeln und schreiben Knoten/Kanten in den `ExpressionGraphStore`. Lehrbuchformeln entstehen ausschließlich durch Verkettung atomarer Schritte.
