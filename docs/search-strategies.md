# Suchstrategien & -profile

`de.regelsuche.search.strategy.SearchStrategy`-Implementierungen:

- `BestFirstSearchStrategy` – priorisiert über Score + Tiefe-/Expansionspenalty.
- `BeamSearchStrategy` – Beam-Suche fester Breite.
- `AStarSearchStrategy` – A* mit Score-Heuristik.
- `RandomMonteCarloSearchStrategy` – uniformes Random-Sampling der Frontier.
- `MonteCarloTreeSearchStrategy` – UCB1-basierte MCTS mit Rollouts.
- `HybridSearchStrategy` – Union mehrerer Strategien, dedupliziert per kanonischem Hash.

## `SearchProfile`

`de.regelsuche.search.SearchProfile` bündelt Heuristik + passende Strategie:

| Profil | Heuristik (Tiefe/Knoten) | Strategie |
| --- | --- | --- |
| `FAST_SIMPLIFY` | 4 / 200 | Best-First |
| `DISCOVERY` | 6 / 1500 | Hybrid (BestFirst + Beam) |
| `TEACHING` | 5 / 600 | Beam |
| `PROOF_ORIENTED` | 8 / 2000 | A* |
| `EXHAUSTIVE_SMALL` | 5 / 3000 | MCTS |

Alle Strategien arbeiten ausschließlich über atomare Rewrite-Regeln.
