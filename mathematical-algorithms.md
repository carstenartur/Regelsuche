# Mathematical Algorithms

Regelsuche kombiniert Rewrite-Suche mit optionalen mathematischen
Validierungs- und Discovery-Backends.

Der aktuelle Registry-Fokus liegt auf:

- `polynomialEquivalence`
- `groebnerBasis`
- `jasBackend`
- `singularBackend`
- `knuthBendix`
- `criticalPairs`
- `pslq`
- `numericRelationSearch`

Die Registry wird von Validierungs- und Discovery-Schichten konsumiert; direkte
Rewrite-Regeln bleiben davon getrennt. Die domänenbewusste
Polynomfaktorisierung besitzt zusätzlich eine eigene typisierte
Engine-/Verifier-Grenze.

## Implementierter Stand

### Domänenbewusste Polynomfaktorisierung

Der Faktorisierungskern trennt algebraische Daten, Algorithmen, Vorschläge und
Evidence:

```text
CoefficientDomain
  -> PolynomialRing mit expliziter Monomordnung
  -> kanonisches SparsePolynomial
  -> request-weite Strukturgrenzen
  -> univariate Projektion und Inhaltsnormalisierung
  -> GGT und quadratfreie Zerlegung
  -> FactorizationEngine: untrusted Proposal und BackendClaim
  -> FactorizationVerifier: Vertrags- und Produktprüfung
  -> verifier-ausgestellte Kandidaten und Report-Evidence
```

Implementiert sind:

- exakte Integer- und Rational-Domänenverträge;
- lexikographische, graduiert-lexikographische und
  graduiert-revers-lexikographische Monomordnungen;
- unveränderliche kanonische Sparse-Polynome;
- eine verlustfreie dichte univariate Koeffizientenansicht;
- exakte Ableitung, Multiplikation, Polynomdivision und monische Normierung;
- ein budgetierter monischer euklidischer Polynom-GGT;
- Inhalts- und Primitivteilnormalisierung für `Z[x]` und `Q[x]`;
- charakteristik-0-quadratfreie Zerlegung mit Multiplizitäten;
- request-weite Grenzen für Variablen, Grad, Terme und
  Quellkoeffizientenbitlänge;
- explizite Zwischenkoeffizienten- und nicht zurücksetzbare Arbeitsbudgets;
- deterministische, stage-getrennte Work Ledgers und
  content-adressierte Algorithmuszertifikate.

Die Inhaltsnormalisierung erzeugt für beide Quelldomänen exakt

```text
source = scalar * primitivePart
```

mit `scalar` in `Q`, einem primitiven `primitivePart` in `Z[x]`, positivem
Leitkoeffizienten und unveränderter Monomunterstützung. Nenner-LCM,
ganzzahliger Inhalt, Skalar und primitiver Teil werden in der Evidence gebunden.
Der Algorithmus prüft sowohl die ganzzahlige Zwischenform als auch die
ursprüngliche Quelle unabhängig durch Rückmultiplikation.

Die quadratfreie Zerlegung arbeitet über einem exakten Feld der
Charakteristik null. Sie rekonstruiert das Quellpolynom und prüft für jeden
ausgegebenen Faktor `gcd(f, f') = 1`. Quadratfrei bedeutet nicht irreduzibel.

Die erste Engine `regelsuche.factorization.binary-quartic-2x2/v1` löst
weiterhin die allgemeinen Koeffizientenbedingungen

```text
(a*A^2 + b*A*B + c*B^2)
*
(d*A^2 + e*A*B + f*B^2)
```

für binäre homogene Quartiken unter expliziten Koeffizienten-, Kandidaten- und
Work-Budgets. Sie speichert weder die Sophie-Germain-Identität noch andere
benannte Einzelfälle.

Eine Engine-Ausgabe autorisiert keine Suchkante. `FactorizationVerifier` prüft
Engine-ID, Koeffizientendomäne, Struktur-, Work- und Kandidatenbudgets,
kanonische Ringe sowie die exakte Rückmultiplikation von Einheit, Faktoren,
Multiplizitäten und ungelöstem Rest. Backend-Claims zu Vollständigkeit oder
Irreduzibilität bleiben von unabhängig zertifizierter Evidence getrennt.

### Gröbner-Basen und weitere Backends

- `groebnerBasis` nutzt die interne `pureJavaSmallGroebner`-Reduktion für kleine
  Polynomideale mit mehreren Generatoren, Nicht-Null-Rest, Budget- und
  Unsupported-Domain-Status.
- Der interne Buchberger-Kern priorisiert kritische Paare nach dem Totalgrad
  ihres kleinsten gemeinsamen Vielfachen. Das Produktkriterium verwirft Paare
  mit teilerfremden Leitmonomen vor dem Einreihen; das Kettenkriterium verwirft
  nur Paare, deren beide Teilketten vollständig erledigt sind.
- Eingabegeneratoren werden deterministisch mit kleinem Leitgrad zuerst
  verarbeitet. Jeder weitere Generator wird gegen die akzeptierte Basis
  reduziert; Nullreste und bereits vorhandene monische Reste werden
  eliminiert.
- Vollständig berechnete Gröbner-Basen werden im langlebigen
  `GroebnerBasisEquivalenceService` nach kanonisiertem Generatorensatz und
  Monomordnung wiederverwendet. Die LRU-Struktur ist standardmäßig auf 128
  Ideale begrenzt; unvollständige Berechnungen werden nicht gecacht.
- Wenn ein Generatorensatz einen gecachten Generatorensatz echt enthält, kann
  dessen abgeschlossene Gröbner-Basis inkrementell erweitert werden. Alte–alte
  kritische Paare gelten als erledigt; neue Paare enthalten mindestens ein
  neues Basiselement.
- Unter mehreren Cache-Teilsätzen wird der Kandidat mit der kleinsten oberen
  Schranke für neu zu betrachtende Paare gewählt. Ist diese Schranke größer als
  die Paarzahl einer kalten Initialisierung, wird kalt gestartet.
- Die Reduktorstruktur und eine vollständig berechnete diagnostische
  Interreduktion werden pro vorbereitetem Ideal memoisiert. Unvollständige oder
  budget-abgebrochene Zustände bleiben ungecacht.
- Ergebnisse weisen Cache- und Kostenmetriken aus, darunter `basisCacheHit`,
  `reducedBasisCacheHit`, `basisReuseMode`, `basisPreparationSteps`,
  `basisPreparationStepsSaved`, `queryReductionSteps`,
  `interreductionSteps`, `reducedBasisStepsSaved`,
  `initialGeneratorsConsidered`, `initialGeneratorsReduced`,
  `initialGeneratorsEliminated`, `incrementalBaseGeneratorCount`,
  `incrementalBaseSize`, `incrementalCandidatePairUpperBound` und
  `coldInitialPairUpperBound`.
- Der wiederverwendbare Vorbereitungsaufwand wird über inkrementelle
  Erweiterungen akkumuliert. Ein späterer exakter Cache-Hit kann die gesamte
  bereits bezahlte Vorbereitung als eingesparte Arbeit ausweisen.
- Leitmonome der Reduktoren werden pro vorbereiteter Basis nur einmal bestimmt
  und deterministisch sortiert. Die reduzierte Basis wird sequenziell und
  idealerhaltend interreduziert.
- Gröbner-Ergebnisse enthalten `pairsConsidered`, `pairsReduced`, nach Produkt-
  und Kettenkriterium verworfene Paare sowie `maxPendingPairs`.
- Das verfügbare JAS-Artefakt `edu.jas:jas` steht unter GPL-3.0-or-later und
  wird deshalb nicht in die MIT-lizenzierte Standard-Distribution eingebunden.
  Ein aktivierter, aber nicht verfügbarer Adapter meldet `UNAVAILABLE`.
- `numericRelationSearch` routet bei aktiviertem `pslq` über
  `DomainAwareCasRouter` auf den internen `PslqNumericRelationService`.
  Ergebnisse sind immer `HYPOTHESIS`, nie `PROOF`.
- Symbolic Regression besitzt Evidence-only Quellen für Shape-Wiederholungen
  und kleine numerische Template-Fits. Die Backend-Schnittstelle erlaubt
  spätere PySR-, Operon- oder GP-Adapter ohne Proof-Semantik.
- `DeterministicCounterexampleSearchService` prüft Hypothesen mit
  Boundary-Integer-Samples, rationalen Samples, seed-gebundenen
  Zufallssamples, Domain- und Divisionskanten, komplexen Samples sowie kleinen
  nichtkommutativen Matrix-Samples.
- Provenance wird als typisierter Graph aufgebaut und kann im Speicher oder
  über den Neo4j-Adapter persistiert werden.

## Grenzen der High-End-Ausbaustufe

- Inhalt, primitiver Teil, Polynom-GGT und quadratfreie Zerlegung sind
  implementiert; die vollständige Faktorisierung des primitiven Teils fehlt
  noch.
- Noch nicht implementiert sind Faktorisierung über endlichen Körpern,
  geeignete Primzahlauswahl mit Ablehnungsgründen, Hensel-Lifting,
  Zassenhaus- oder LLL-/van-Hoeij-Rekombination, rationale Faktorreassemblierung
  und unabhängige Vollständigkeits- beziehungsweise
  Irreduzibilitätszertifikate.
- Die binäre Quartik-Engine bleibt eine exakte begrenzte `2 + 2`-Engine. Sie
  wird nicht als vollständige univariate oder multivariate Faktorisierung
  dargestellt.
- Ein Engine-Miss ist kein Irreduzibilitätsbeweis. Ein Backend-Claim erfüllt
  ohne zusätzlichen unabhängigen Verifier keinen `INDEPENDENT_COMPLETE`-
  Request.
- Der integrierte Gröbner-Kern ist für kleine Polynomideale über rationalen
  Koeffizienten gedacht. F4/F5, modulare Berechnung, Signaturen und
  spezialisierte Datenstrukturen professioneller CAS sind nicht implementiert.
- Exakte Cache-Treffer und inkrementelle Erweiterungen werden anhand
  kanonisierter Generatorenmengen erkannt. Algebraisch identische Ideale mit
  wesentlich anderen Generatorensystemen werden noch nicht automatisch als
  derselbe Cache-Zustand erkannt.
- Die Paar-Obergrenze ist ein konservatives Auswahlkriterium, keine exakte
  Laufzeitprognose. Koeffizientenwachstum und dynamisch entstehende
  Basiselemente können die tatsächlichen Kosten dominieren.
- Trigonometrie, Radikale, allgemeine Division und nichtkommutative Algebra
  werden nicht durch ein vollständiges CAS bewiesen.
- Numerische Relationen und Symbolic-Regression-Ausgaben sind
  Discovery-Evidence. „No counterexample found“ ist kein Beweis.
- Externe CAS-Schichten wie Singular bleiben optional und melden ohne
  Adapter beziehungsweise Installation sauber `UNAVAILABLE`.

Weiterführende Dokumente:

- [Domänenbewusste Polynomfaktorisierung](domain-aware-polynomial-factorization.md)
- [Univariate Polynomgrundlage, Inhalt und quadratfreie Zerlegung](univariate-polynomial-foundation.md)
- [Univariate Inhalts- und Primitivteilnormalisierung](univariate-content-normalization.md)
- [Semantische Polynomansicht und quartische Zerlegungsengine](polynomial-decomposition-synthesis.md)
- [ADR: Domänenbewusster Polynomkern statt Quartik-API](adr/domain-aware-polynomial-factorization.md)
- [Rule Discovery](rule-discovery.md)
- [Search Intelligence](search-intelligence.md)
- [Equality Saturation](equality-saturation.md)
