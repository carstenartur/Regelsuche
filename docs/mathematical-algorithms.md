# Mathematical Algorithms

Regelsuche kombiniert Rewrite-Suche mit optionalen mathematischen
Validierungs-, Faktorisierungs- und Discovery-Backends. Direkte Rewrite-Regeln
bleiben von algorithmischen Backends getrennt: Ein Backend liefert ein
mathematisches Ergebnis oder einen Vorschlag, aber nicht automatisch eine
autorisierte Suchkante oder einen wissenschaftlichen Claim.

## Registry und Verantwortung

Der aktuelle Registry-Fokus liegt auf:

- `polynomialEquivalence`;
- `groebnerBasis`;
- `jasBackend`;
- `singularBackend`;
- `knuthBendix`;
- `criticalPairs`;
- `pslq`;
- `numericRelationSearch`.

Zusätzlich besitzt die domänenbewusste Polynomfaktorisierung eigene typisierte
Verträge. Sie ist kein untypisierter Registry-Aufruf, sondern trennt
Koeffizientendomäne, Polynomring, Request, Algorithmusstufe, Engine-Proposal und
Verifier-Evidence.

## Domänenbewusste Polynomfaktorisierung

Der Faktorisierungskern verwendet folgenden Trust Flow:

```text
CoefficientDomain
  -> PolynomialRing mit expliziter Monomordnung
  -> kanonisches SparsePolynomial
  -> FactorizationRequest
       -> Strukturgrenzen
       -> Kandidatenbudget
       -> nicht zurücksetzbares Arbeitsbudget
  -> allgemeine Algorithmusstufen
  -> FactorizationEngine: untrusted Proposal und BackendClaim
  -> FactorizationVerifier: Vertrags- und Produktprüfung
  -> verifier-ausgestellte Kandidaten und Report-Evidence
```

### Exakte Koeffizientendomänen

Implementiert sind:

```text
regelsuche.coefficients.integer/v1
regelsuche.coefficients.rational/v1
regelsuche.coefficients.prime-field/<p>/v1
```

`BigIntegerDomain` und `ExactRationalField` bilden `Z` beziehungsweise `Q`
exakt ab. `PrimeField` repräsentiert `F_p` für eine deterministisch geprüfte
positive `int`-Primzahl. Alle `BigInteger`-Repräsentanten werden modulo `p`
kanonisiert; Division verwendet die exakte modulare Inverse. Die Primzahl ist
Bestandteil der stabilen Domain-ID und damit der Polynomringidentität.

Es gibt keine zweite Brucharithmetik und keinen `double`-Einstieg in die exakte
Polynomdomäne.

### Polynomring, Sparse-IR und univariate View

`PolynomialRing` bindet Koeffizientendomäne, Variablenreihenfolge und eine
explizite Monomordnung. Implementiert sind lexikographische,
graduiert-lexikographische und graduiert-revers-lexikographische Ordnung.

`SparsePolynomial` ist die kanonische mathematische Identität. Die
`UnivariatePolynomialView` ist eine verlustfreie dichte
Koeffizientendarstellung

```text
[a0, a1, ..., an]  <=>  a0 + a1*x + ... + an*x^n
```

für allgemeine Algorithmen. Sie implementiert exakte Addition, Subtraktion,
Skalierung, Multiplikation, Ableitung, monische Normierung, Division mit Rest
und exakt geprüfte Quotientenbildung.

### Inhalt und primitiver Teil

`UnivariateContentNormalization` verarbeitet einen
`FactorizationRequest<BigInteger>` oder
`FactorizationRequest<ExactRational>` und erzeugt

```text
source = scalar * primitivePart
```

mit einem exakten rationalen Skalar und einem primitiven ganzzahligen Polynom
mit positivem Leitkoeffizienten. Nenner-LCM, ganzzahliger Inhalt,
Vorzeichenkanonisierung und beide Rekonstruktionsstufen sind budgetiert und in
der Evidence gebunden.

Source-Struktur und Gesamtarbeit bleiben Eigentum des `FactorizationRequest`.
`UnivariateContentPolicy` ergänzt ausschließlich die für diese Stufe neue
Zwischenkoeffizienten-Bitgrenze.

### Euklidischer GGT und quadratfreie Zerlegung

`UnivariatePolynomialAlgorithms.gcd` berechnet über einem exakten Feld einen
monischen euklidischen Polynom-GGT. `gcd(0, 0)` bleibt ausdrücklich
undefiniert. Unsupported Domain, Unsupported Shape, Budgeterschöpfung und
technischer Fehler sind getrennte Ergebnisse.

`SquareFreeDecomposition` führt über einem exakten Feld der Charakteristik null
eine Yun-artige Zerlegung aus. Sie bewahrt Multiplizitäten, rekonstruiert das
Quellpolynom und prüft für jeden ausgegebenen Faktor
`gcd(f, f') = 1`.

Quadratfrei bedeutet nicht irreduzibel.

## Deterministische Faktorisierung über `F_p[x]`

`FiniteFieldFactorization` implementiert die vollständige Faktorisierung eines
nichtkonstanten, quadratfreien univariaten Polynoms über dem ausdrücklich
deklarierten `PrimeField`.

### Algorithmischer Ablauf

```text
FactorizationRequest<BigInteger> über PrimeField(p)
  -> Struktur-, Kandidaten- und Work-Prüfung
  -> Monisierung und retained Feldeinheit
  -> Ableitung und Quadratfreiheits-GGT
  -> Frobenius-Potenzen x^(p*j) mod f
  -> Berlekamp-Matrix Q - I
  -> deterministische RREF und Nullraumbasis
  -> unabhängige Nullraumprüfung
  -> deterministisches Splitting nach Basis und Restklassen
  -> exakte Rekonstruktion und paarweise Koprimheit
  -> Rabin-/Frobenius-Irreduzibilitätsprüfung
  -> issuer-owned vollständige F_p-Evidence
```

Die Berlekamp-Matrix wird spaltenweise aus `x^(p*j) mod f` aufgebaut. Vor ihrer
quadratischen Allokation prüft die Stufenpolitik `degree²` gegen
`maxBerlekampMatrixCells`. RREF, Nullraumbasis und jeder Basisvektor werden
unter demselben nicht zurücksetzbaren Arbeitsbudget verarbeitet.

Das Splitting ist deterministisch: Basisvektoren werden kanonisch geordnet,
Restklassen in der Reihenfolge `0 .. p-1` betrachtet und Zwischenfaktoren nach
dem kanonischen Sparse-Polynommaterial sortiert. Dadurch stimmt die interne
Reihenfolge mit der Reihenfolge der ausgestellten Resultat-Evidence überein.

### Unabhängige Abschlussprüfung

Ein positives Resultat entsteht erst nach folgenden zusätzlichen Prüfungen:

- Einheit und Faktoren rekonstruieren exakt die Quelle im deklarierten
  `F_p[x]`;
- alle Faktoren sind monisch und nichtkonstant;
- unterschiedliche Faktoren sind paarweise koprim;
- die Faktoranzahl entspricht der Berlekamp-Nullität;
- für jeden Faktor erfüllt `x^(p^n) = x mod f` die Frobenius-Endbedingung;
- für jeden Primteiler `q` des Faktorgrades `n` gilt
  `gcd(f, x^(p^(n/q)) - x) = 1`.

Der letzte Punkt ist der Rabin-Irreduzibilitätstest. Bei zusammengesetzten
Faktorgraden werden alle unterschiedlichen Primteiler des Grades geprüft.

`COMPLETED` bedeutet damit vollständig und irreduzibel faktorisiert im
angegebenen Primkörper. Der Claim wird nicht auf ein ursprüngliches Polynom über
`Z[x]` oder `Q[x]` übertragen.

### Grenzen und Budgets

`FiniteFieldFactorizationPolicy` bindet ausschließlich algorithmusspezifische
Grenzen:

- `Algorithm.DETERMINISTIC_BERLEKAMP_V1`;
- maximale Anzahl enumerierter Feldelemente;
- maximale Anzahl Zellen der Berlekamp-Matrix.

Alle übrigen Grenzen stammen aus dem `FactorizationRequest`. Ein
paketinterner Einstieg akzeptiert ein bereits belastetes
`PolynomialWorkBudget`; eine abweichende Budgetautorität wird als technischer
Fehler abgelehnt. Unterläufe und Policy-Grenzen bleiben
`BUDGET_INCONCLUSIVE`, nicht Irreduzibilität.

Details und Reproduktionsbefehle stehen unter
[Deterministische Faktorisierung über Primkörpern](finite-field-factorization.md).

## Erste Engine: binäre homogene Quartiken

Die bestehende Engine

```text
regelsuche.factorization.binary-quartic-2x2/v1
```

löst die allgemeinen Koeffizientenbedingungen

```text
(a*A^2 + b*A*B + c*B^2)
*
(d*A^2 + e*A*B + f*B^2)
```

für binäre homogene Quartiken unter expliziten Koeffizienten-, Kandidaten- und
Work-Budgets. Sie speichert weder die Sophie-Germain-Identität noch andere
benannte Einzelfälle. Teiler der äußeren Koeffizienten werden begrenzt
enumeriert; die verbleibenden linearen Bedingungen werden exakt gelöst.

Eine Engine-Ausgabe autorisiert keine Suchkante. `FactorizationVerifier` prüft
Engine-ID, Koeffizientendomäne, Request, Budgets, Ringe und die exakte
Rückmultiplikation von Einheit, Faktoren, Multiplizitäten und ungelöstem Rest.
Backend-Claims zu Vollständigkeit oder Irreduzibilität bleiben von unabhängig
zertifizierter Evidence getrennt.

## Noch offene `Z[x]`-/`Q[x]`-Faktorisierung

Die vorhandene Primkörperfaktorisierung ist ein qualifizierter algorithmischer
Baustein, aber noch keine vollständige Faktorisierungsengine für ganzzahlige
oder rationale Quellen. Noch offen sind:

1. geeignete Primzahlauswahl mit retained Ablehnungsgründen;
2. Reduktion des primitiven ganzzahligen Polynoms modulo der ausgewählten
   Primzahl;
3. Hensel-Lifting;
4. ganzzahlige Rekombination, zunächst etwa Zassenhaus;
5. spätere LLL-/van-Hoeij-Rekombination, wenn qualifiziert;
6. exakte rationale Faktorreassemblierung;
7. unabhängige vollständige `Z[x]`-/`Q[x]`-Evidence hinter dem allgemeinen
   Engine-/Verifier-Vertrag.

Ein vollständiger Abschluss in `F_p[x]` ist kein Beweis für Vollständigkeit
oder Irreduzibilität in `Z[x]` oder `Q[x]`.

## Gröbner-Basen

`groebnerBasis` nutzt die interne `pureJavaSmallGroebner`-Reduktion für kleine
Polynomideale mit mehreren Generatoren, Nicht-Null-Rest, Budget- und
Unsupported-Domain-Status.

Der interne Buchberger-Kern:

- priorisiert kritische Paare nach dem Totalgrad ihres kleinsten gemeinsamen
  Vielfachen;
- verwirft teilerfremde Leitmonome über das Produktkriterium;
- verwendet das Kettenkriterium nur nach vollständig erledigten Teilketten;
- reduziert Eingabegeneratoren deterministisch vor der Paarbildung;
- eliminiert Nullreste und bereits vorhandene monische Reste;
- misst betrachtete, reduzierte und verworfene Paare sowie
  `maxPendingPairs`.

Vollständig berechnete Basen werden nach kanonisiertem Generatorensatz und
Monomordnung in einer begrenzten LRU-Struktur wiederverwendet. Geeignete echte
Generator-Teilmengen können als inkrementelle Basis dienen. Ein konservativer
Kostenvergleich verwirft inkrementelle Wiederverwendung, wenn ihre obere
Paargrenze schlechter als eine kalte Initialisierung ist.

Reduktorstrukturen und vollständig berechnete Interreduktionen werden ebenfalls
memoisiert. Unvollständige oder budget-abgebrochene Zustände werden nicht
gecacht.

Die Ergebnis-Evidence weist unter anderem Basis-Cache-Hits,
Wiederverwendungsmodus, Vorbereitungs- und Reduktionsschritte,
Interreduktionsarbeit, eingesparte Arbeit, Generatorreduktionen und
Paarmetriken getrennt aus.

Der integrierte Kern bleibt auf kleine Ideale über rationalen Koeffizienten
begrenzt. F4/F5, modulare Gröbner-Basen, Signaturen und spezialisierte
Datenstrukturen etablierter Computer-Algebrasysteme sind nicht implementiert.

## Externe und optionale Backends

- `jasBackend` wurde gegen das verfügbare Maven-Central-Artefakt bewertet. Die
  GPL-3.0-or-later-Lizenz passt nicht in die MIT-Standarddistribution; ohne
  kompatiblen Adapter meldet die Schicht `UNAVAILABLE`.
- `singularBackend` bleibt optional. Ohne Adapter oder Installation wird kein
  nativer Erfolg simuliert.
- Externe Faktorisierungsresultate müssen künftig denselben typisierten
  Request-/Verifier-Vertrag durchlaufen und bleiben bis zur exakten
  Rekonstruktion untrusted Proposals.

## Numerische Relationen und Symbolic Regression

`numericRelationSearch` routet bei aktiviertem `pslq` über
`DomainAwareCasRouter` auf den internen `PslqNumericRelationService`.
Ergebnisse bleiben `HYPOTHESIS`, nie `PROOF`, und tragen Koeffizienten,
Residual, Sample-Anzahl und Informationsgrenze.

Symbolic Regression besitzt zwei Evidence-only Quellen:

- `HeuristicSymbolicRegressionHypothesisSource` für Shape-Wiederholungen;
- `TemplateSymbolicRegressionHypothesisSource` für kleine numerische
  Template-Fits.

Die Template-Quelle nutzt die stabile Backend-Schnittstelle
`SymbolicRegressionBackend`, sodass spätere PySR-, Operon- oder GP-Adapter ohne
Proof-Semantik angeschlossen werden können.

`DeterministicCounterexampleSearchService` greift Hypothesen mit festen
Boundary-Integer-Samples, optionalen rationalen Samples, seed-gebundenen
Zufallssamples, Domain-/Division-Kanten, komplexen Samples und kleinen
nichtkommutativen Matrix-Samples an. Ein begrenzter Nicht-Fund ist kein Beweis.

## Provenance

Provenance wird als typisierter Graph aufgebaut und kann über
`ProvenanceRepository` im Speicher oder im Neo4j-Adapter persistiert werden.
Der Graph enthält eigene Knoten für Counterexample-Search-Attempts,
Symbolic-Regression-Proposals, numerische Relationskandidaten und
CAS-Validierungsversuche sowie Queries für Quelle, Qualität und
CAS-Erfolgsraten.

## Prüfung

Fokussierte Primkörperprüfung:

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.polynomial.PrimeFieldTest

./gradlew :regelsuche-math-algorithms:test \
  --tests de.regelsuche.math.algorithms.polynomial.FiniteFieldFactorizationTest \
  --tests de.regelsuche.math.algorithms.polynomial.FiniteFieldFactorizationHigherDegreeTest
```

Vollständiger Repositoryvertrag:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Siehe auch

- [Domänenbewusste Polynomfaktorisierung](domain-aware-polynomial-factorization.md)
- [Univariate Polynomgrundlage, Inhalt und quadratfreie Zerlegung](univariate-polynomial-foundation.md)
- [Univariate Inhalts- und Primitivteilnormalisierung](univariate-content-normalization.md)
- [Deterministische Faktorisierung über Primkörpern](finite-field-factorization.md)
- [Semantische Polynomansicht und quartische Zerlegungsengine](polynomial-decomposition-synthesis.md)
- [Rule Discovery](rule-discovery.md)
- [Search Intelligence](search-intelligence.md)
- [Equality Saturation](equality-saturation.md)
