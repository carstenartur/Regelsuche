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

Weiterführende Dokumente:

- [docs/rule-discovery.md](rule-discovery.md)
- [docs/search-intelligence.md](search-intelligence.md)
- [docs/equality-saturation.md](equality-saturation.md)
