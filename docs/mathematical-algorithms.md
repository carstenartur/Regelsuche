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
- Der interne Buchberger-Kern priorisiert kritische Paare nach dem Totalgrad ihres kleinsten gemeinsamen Vielfachen. Das Produktkriterium verwirft Paare mit teilerfremden Leitmonomen bereits vor dem Einreihen; das Kettenkriterium verwirft nur Paare, deren beide Teilketten bereits vollständig erledigt sind. Dadurch wird die Anzahl tatsächlich zu reduzierenden S-Polynome begrenzt, ohne die Beweissemanik aufzuweichen.
- Leitmonome der Reduktoren werden pro Reduktionslauf nur einmal bestimmt und deterministisch sortiert. Die ausgegebene reduzierte Basis wird sequenziell und idealerhaltend interreduziert; ihr Status ist separat als `COMPLETE`, `BUDGET_EXHAUSTED` oder `NOT_COMPUTED` ausgewiesen.
- Gröbner-Ergebnisse enthalten Messwerte für `pairsConsidered`, `pairsReduced`, nach Produkt- und Kettenkriterium verworfene Paare sowie maxPendingPairs`. Damit können Suchstrategien und Benchmarks bewerten, ob eine Änderung den tatsächlich bearbeiteten algebraischen Suchraum verkleinert.
- `jasBackend` wurde gegen Maven Central evaluiert: das verfügbare JAS-Artefakt (`uds.jas:jas`) steht unter GPL-3.0-or-later und wird deshalb nicht in die MIT-lizunzierte Standard-Distribution eingebunden. Wenn `jasBackend` aktiviert wird, aber kein kompatibler Adapter verfügbar ist, meldet die Gröbner-Schicht `UNAVAILABLE`.
- `numericRelationSearch` routet bei aktiviertem `pslq` über `DomainAwareCasRouter` auf den internen `PslqInternalRelationService`; Ergebnisse sind immer `HYPOTHESIS`, nie `PROOF`, und tragen Koeffizienten, Residual, Sample-Anzahl und Hypothesis-only-Semantik im Payload.
- Symbolic Regression besteht aus zwei Evidence-only Quellen: `HeuristicSymbolicRegressionHypothesisSource` für Shape-Wiederholungen und `TemplateSymbolicRegressionHypothesisSource` für kleine numerische Template-Fits. Die Template-Quelle nutzt die stabile Backend-Schnittstelle `SymbolicRegressionBackend` mit `SymbolicRegressionSample`/`SymbolicRegressionFittedResult`, sodass spätere PySR-/Operon-/GP-Adapter ohne Proof-Semantik angeschlossen werden können.
- `DeterministicCounterexampleSearchService` prüft Hypothesen deterministisch mit
  Boundary-Integer-Samples (`0, 1, -1, 2, -2`), optionalen rationalen Samples
  (`1/2, -1/2, 2/3, -2/3`), Zufallssamples mit Seed, Domain-/Division-Kanten,
  Complex-Samples und kleinen nichtkommutativen Matrix-Samples.
- Provenance wird als typed graph aufgebaut und kann über `ProvenanceRepository`
  identisch im Speicher oder im Neo4j-Adapter persistiert werden. Der Graph
  enthält eigene Knoten für Counterexample-Search-Attempts,
  Symbolic-Regression-Proposals, numerische Relationskandidaten und
  CAS-Validierungsversuche sowie Queries für Quelle, Qualität und CAS-Erfolgsraten.

## Grenzen der High-End-Ausbaustufe

- Der integrierte Gröbner-Kern ist für kleine Polynomideale über rationalen Koeffizienten gedacht.
- Die Paarselektion bleibt ein optimierter Buchberger-Ansatz; F4/F5, modulare Berechnung, Signaturen und spezialisierte Datenstrukturen professioneller Computeralgebrasysteme sind nicht implementiert.
- Trigonometrie, Radikale, allgemeine Division und nichtkommutative Algebra werden nicht durch ein vollständiges CAS bewiesen.
- Numerische Relationen und Symbolic-Regression-Ausgaben sind Discovery-Evidence.
  Sie müssen durch Counterexample-Suche und optional unterstützte symbolische
  Backends weiter geprüft werden. "No counterexample found" ist kein Beweis,
  sondern nur ein budgetierter Nicht-Fund; `INCONCLUSIVE` bedeutet, dass keine
  belastbare Aussage möglich war.
- Externe CAS-Schichten wie Singular bleiben optional und melden ohne Adapter/Installation sauber `UNAVAILABLE`.

Weiterführende Dokumente:

- [docs/rule-discovery.md](rule-discovery.md)
- [docs/search-intelligence.md](search-intelligence.md)
- [docs/equality-saturation.md](equality-saturation.md)
