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
- Noch vor der kritischen Paarbildung werden Eingabegeneratoren deterministisch mit kleinem Leitgrad zuerst verarbeitet. Jeder weitere Generator wird gegen die bereits akzeptierte Basis reduziert; ein Nullrest oder ein bereits vorhandener monischer Rest wird eliminiert. Dadurch erzeugen redundante Generatoren überhaupt keine kritischen Paare. `GroebnerBasisEngine.EngineResult` und das Payload von `GroebnerBasisEquivalenceService` weisen dafür `initialGeneratorsConsidered`, `initialGeneratorsReduced` und `initialGeneratorsEliminated` aus, sodass die Vorreduktion auch in realen Suchläufen messbar ist.
- Vollständig berechnete Gröbner-Basen werden im langlebigen `GroebnerBasisEquivalenceService` nach kanonisiertem Generatorensatz und Monomordnung wiederverwendet. Die LRU-Struktur ist standardmäßig auf 128 Ideale begrenzt; unvollständige oder budget-abgebrochene Berechnungen werden nicht gecacht.
- Wenn ein angefragter Generatorensatz einen gecachten Generatorensatz echt enthält, kann dessen bereits abgeschlossene Gröbner-Basis inkrementell erweitert werden. Alte–alte kritische Paare gelten dabei als erledigt; Zusatzgeneratoren werden gegen die vorhandene Basis reduziert und anschließend entstehen nur noch Paare, an denen neue Basiselemente beteiligt sind.
- Unter mehreren möglichen Cache-Teilsätzen wird der Kandidat mit der kleinsten oberen Schranke für neu zu betrachtende Paare gewählt. Ist diese Schranke größer als die Paarzahl einer kalten Initialisierung aus dem vollständigen Generatorensatz, wird die inkrementelle Wiederverwendung verworfen und kalt gestartet. Damit wird ein großer gecachter Basiszustand nicht allein wegen seiner größeren Generatorüberschneidung bevorzugt.
- Die Reduktorstruktur einer gecachten Basis wird ebenfalls vorbereitet und wiederverwendet. Eine einmal vollständig berechnete diagnostische Interreduktion wird pro vorbereiteten Ideal ebenfalls memoisiert. Wiederholte Kandidatenprüfungen gegen dasselbe Ideal bezahlen damit nur noch die konkrete Normalformreduktion; eine unvollständige oder budget-abgebrochene Interreduktion bleibt ausdrücklich ungecacht.
- Cache- und Kostenmetriken werden explizit im Ergebnis ausgewiesen: `basisCacheHit`, `reducedBasisCacheHit`, `basisReuseMode`, `basisCacheSize`, `basisCacheCapacity`, `basisPreparationSteps`, `basisPreparationStepsSaved`, `queryReductionSteps`, `interreductionSteps`, `reducedBasisStepsSaved`, `initialGeneratorsConsidered`, `initialGeneratorsReduced`, `initialGeneratorsEliminated`, `incrementalBaseGeneratorCount`, `incrementalBaseSize`, `incrementalCandidatePairUpperBound`, `coldInitialPairUpperBound` und die Extension-Generator-Zähler. Dadurch bleibt nachvollziehbar, welcher Rechenaufwand durch exakte oder inkrementelle Wiederverwendung vermieden wurde und wann eine mögliche Wiederverwendung aus Kostengründen verworfen wurde.
- Der wiederverwendbare Vorbereitungsaufwand wird über mehrere inkrementelle Erweiterungen akkumuliert. Ein späterer exakter Cache-Hit kann daher nicht nur die zuletzt ausgeführten Erweiterungsschritte, sondern die gesamte bereits bezahlte Vorbereitung als eingesparte Arbeit ausweisen. Ein vollständiger Reduced-Basis-Cache-Hit weist die zuvor bezahlten Interreduktionsschritte separat als `reducedBasisStepsSaved` aus und verbraucht dafür im aktuellen Aufruf null `interreductionSteps`.
- Leitmonome der Reduktoren werden pro vorbereiteter Reduktorbasis nur einmal bestimmt und deterministisch sortiert. Die ausgegebene reduzierte Basis wird sequenziell und idealerhaltend interreduziert; ihr Status ist separat als `COMPLETE`, `BUDGET_EXHAUSTED` oder `NOT_COMPUTED` ausgewiesen.
- Gröbner-Ergebnisse enthalten Messwerte für `pairsConsidered`, `pairsReduced`, nach Produkt- und Kettenkriterium verworfene Paare sowie `maxPendingPairs`. Damit können Suchstrategien und Benchmarks bewerten, ob eine Änderung den tatsächlich bearbeiteten algebraischen Suchraum verkleinert.
- `jasBackend` wurde gegen Maven Central evaluiert: das verfügbare JAS-Artefakt (`edu.jas:jas`) steht unter GPL-3.0-or-later und wird deshalb nicht in die MIT-lizenzierte Standard-Distribution eingebunden. Wenn `jasBackend` aktiviert wird, aber kein kompatibler Adapter verfügbar ist, meldet die Gröbner-Schicht `UNAVAILABLE`.
- `numericRelationSearch` routet bei aktiviertem `pslq` über `DomainAwareCasRouter` auf den internen `PslqNumericRelationService`; Ergebnisse sind immer `HYPOTHESIS`, nie `PROOF`, und tragen Koeffizienten, Residual, Sample-Anzahl und Hypothesis-only-Semantik im Payload.
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
- Exakte Cache-Treffer und inkrementelle Erweiterungen werden anhand kanonisierter Generatorenmengen erkannt. Algebraisch identische Ideale mit wesentlich anderen Generatorensystemen werden noch nicht automatisch als derselbe Cache-Zustand erkannt.
- Die Paar-Obergrenze ist ein konservatives Auswahlkriterium, keine exakte Laufzeitprognose. Dynamisch entstehende Basiselemente und Koeffizientenwachstum können die tatsächlichen Kosten weiterhin dominieren; deshalb werden die real ausgeführten Schritte separat gemessen.
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
