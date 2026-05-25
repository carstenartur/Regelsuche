# Hypothesis Mining

Discovery-Pfade werden in Regelsuche nicht direkt als Regeln übernommen. Stattdessen läuft ein eigener Hypothesen-Lebenszyklus:

`Path-Mining → HypothesisCandidate → Counterexample Search → optional Proof → Promotion`

Wesentliche Punkte:

- `HypothesisCandidate` speichert Muster, Evidenz, Annahmen, Novelty-Score sowie Proof-/Counterexample-Status.
- `HypothesisPromotionPipeline` orchestriert Mining, Gegenbeispielsuche, Persistenz und optionale Auto-Promotion zu Makroregeln.
- Annahmen sind Teil der mathematischen Identität und werden in Replay, Hypothesen, Makroregeln und E-Graph-Sicherheit weitergetragen.
- Ein leerer Gegenbeispiel-Fund ist **kein** Beweis; die Suche ist deterministisch, aber budgetiert.
- `InterestingnessScore` sortiert Hypothesen für Reports/Inventar nach
  Kompressionsgewinn, Generalität, unabhängiger Evidenz, Makro-Wiederverwendung,
  Proof-/Counterexample-Status, Ähnlichkeit zu bekannten Regeln,
  Cross-Domain-Wiederkehr und minimalen Annahmen.
- `AssumptionMinimizer` ist bewusst heuristisch: Eine Annahme wird nur entfernt,
  wenn der aufrufende Stabilitäts-Check (Counterexample-/Proof-Status) unverändert
  bleibt. Das ist keine formale Minimalitätsgarantie.
- `SymbolicRegressionHypothesisSource` ist ein optionaler, standardmäßig
  deaktivierter Port. Ergebnisse werden als `HypothesisCandidate` eingespeist und
  gelten niemals als Beweis.

Weiterführende Dokumente:

- [docs/rule-discovery.md](rule-discovery.md)
- [docs/assumptions.md](assumptions.md)
- [docs/macro-rules.md](macro-rules.md)
