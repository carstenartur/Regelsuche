# Mathematical Algorithms

Regelsuche kombiniert Rewrite-Suche mit optionalen mathematischen Validierungs- und Discovery-Backends.

Der aktuelle Registry-Fokus liegt auf:

- `polynomialEquivalence`
- `groebnerBasis`
- `jasBackend`
- `singularBackend`
- `knuthBendix`
- `criticalPairs`
- `pslq`
- `numericRelationSearch`

Die Algorithmen werden über die Registry aktiviert/deaktiviert und von der Validierungs-/Discovery-Schicht konsumiert; direkte Rewrite-Regeln bleiben davon getrennt.

## Implementierter Stand

- `groebnerBasis` nutzt die interne `pureJavaSmallGroebner`-Reduktion für kleine Polynomideale mit mehreren Generatoren, Nicht-Null-Rest, Budget- und Unsupported-Domain-Status.
- `jasBackend` wurde gegen Maven Central evaluiert: das verfügbare JAS-Artefakt (`edu.jas:jas`) steht unter GPL-3.0-or-later und wird deshalb nicht in die MIT-lizenzierte Standard-Distribution eingebunden. Wenn `jasBackend` aktiviert wird, aber kein kompatibler Adapter verfügbar ist, meldet die Gröbner-Schicht `UNAVAILABLE`.
- `numericRelationSearch` routet bei aktiviertem `pslq` auf den internen `PslqNumericRelationService`; Ergebnisse sind Hypothesen, keine Beweise.
- Symbolic Regression besteht aus zwei Evidence-only Quellen: `HeuristicSymbolicRegressionHypothesisSource` für Shape-Wiederholungen und `TemplateSymbolicRegressionHypothesisSource` für kleine numerische Template-Fits. Beide erzeugen `HypothesisCandidate`-Werte mit Beobachtungs-/Hypothesen-Semantik, nie Proof-Status.
- Provenance wird als typed graph aufgebaut und kann über `ProvenanceRepository` identisch im Speicher oder im Neo4j-Adapter persistiert werden.

Weiterführende Dokumente:

- [docs/rule-discovery.md](rule-discovery.md)
- [docs/search-intelligence.md](search-intelligence.md)
- [docs/equality-saturation.md](equality-saturation.md)
