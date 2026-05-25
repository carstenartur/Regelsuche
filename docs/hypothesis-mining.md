# Hypothesis Mining

Discovery-Pfade werden in Regelsuche nicht direkt als Regeln übernommen. Stattdessen läuft ein eigener Hypothesen-Lebenszyklus:

`Path-Mining → HypothesisCandidate → Counterexample Search → optional Proof → Promotion`

Wesentliche Punkte:

- `HypothesisCandidate` speichert Muster, Evidenz, Annahmen, Novelty-Score sowie Proof-/Counterexample-Status.
- `HypothesisPromotionPipeline` orchestriert Mining, Gegenbeispielsuche, Persistenz und optionale Auto-Promotion zu Makroregeln.
- Annahmen sind Teil der mathematischen Identität und werden in Replay, Hypothesen, Makroregeln und E-Graph-Sicherheit weitergetragen.
- Ein leerer Gegenbeispiel-Fund ist **kein** Beweis; die Suche ist deterministisch, aber budgetiert.

Weiterführende Dokumente:

- [docs/rule-discovery.md](rule-discovery.md)
- [docs/assumptions.md](assumptions.md)
- [docs/macro-rules.md](macro-rules.md)
