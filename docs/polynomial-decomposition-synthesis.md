# Semantische Polynomansicht und quartische Zerlegungsengine

**Implementierungsstand: 25. August 2026**

Die ursprüngliche Zerlegungssynthese bleibt als mathematische Fähigkeit erhalten,
ist aber nicht länger die zentrale Polynomarchitektur. Der allgemeine Vertrag
für Domänen, Ringe, Polynome, untrusted Engine-Proposals und
verifier-ausgestellte Evidence steht unter
[Domänenbewusste Polynomfaktorisierung](domain-aware-polynomial-factorization.md).

Diese Seite beschreibt die konkrete erste Engine und ihre Integration in die
Discovery-Suche.

## Strukturelle AST-Atome

`PolynomialSemanticView` v2 interpretiert einen begrenzten kommutativen Ausdruck
als kanonisches Sparse-Polynom mit ganzzahligen Koeffizienten. Eine Unbestimmte
muss kein Variablenname sein. Ein vollständiger AST-Teilbaum kann ein
strukturelles Atom bilden:

```text
A := x + 1
B := sin(t)
```

Damit besitzt

```text
(x + 1)^4 + 5*(x + 1)^2*sin(t)^2 + 4*sin(t)^4
```

dieselbe Koeffizientenstruktur wie

```text
A^4 + 5*A^2*B^2 + 4*B^4.
```

Die mathematische Identität liegt in einem `PolynomialRing<BigInteger>` mit
expliziter Monomordnung und einem `SparsePolynomial<BigInteger>`.
Anzeigezeichenfolgen und konkrete AST-Knoten werden separat im View gebunden.
Dadurch kann dieselbe Engine später auch aus anderen Eingabepfaden aufgerufen
werden.

Exakte Koeffizienten und Exponenten werden aus `ExactParsedTerm` gelesen. Der
historische `double`-Wert eines `NumberExpr` ist nicht autoritativ. So bleibt
beispielsweise `9007199254740993` exakt erhalten.

## Engine für binäre homogene Quartiken

`BinaryQuarticFactorizationEngine` besitzt die stabile ID

```text
regelsuche.factorization.binary-quartic-2x2/v1
```

und implementiert die allgemeine Schablone

```text
(a*A^2 + b*A*B + c*B^2)
*
(d*A^2 + e*A*B + f*B^2).
```

Für

```text
c40*A^4 + c31*A^3*B + c22*A^2*B^2 + c13*A*B^3 + c04*B^4
```

werden exakt die Bedingungen

```text
a*d             = c40
a*e + b*d       = c31
a*f + b*e + c*d = c22
b*f + c*e       = c13
c*f             = c04
```

gelöst. Die Engine speichert weder die Sophie-Germain-Identität noch andere
konkrete Zerlegungen. Sie enumeriert begrenzte Teiler der äußeren Koeffizienten
und löst die verbleibenden linearen Bedingungen exakt.

Die Engine erzeugt noch keine vertrauenswürdige Suchkante. Sie liefert
`FactorizationEngine.Proposal<BigInteger>` mit `BackendClaim.NONE`, ihrem
stage-getrennten Work Ledger und content-addressed Backendprovenienz.

## Beispiele derselben Engine

### Sophie-Germain

```text
A^4 + 4*B^4
```

liefert unter anderem das Proposal

```text
(A^2 - 2*A*B + 2*B^2)
*
(A^2 + 2*A*B + 2*B^2).
```

### Andere quartische Familie

```text
A^4 + 5*A^2*B^2 + 4*B^4
```

liefert

```text
(A^2 + B^2) * (A^2 + 4*B^2).
```

### Symmetrische Zerlegung

```text
A^4 + A^2*B^2 + B^4
```

liefert

```text
(A^2 - A*B + B^2)
*
(A^2 + A*B + B^2).
```

### AST-Einsetzungen

```text
sin(t)^4 + 4*(x + 1)^4
```

wird durch dieselbe Engine verarbeitet. `sin(t)` und `x + 1` bleiben dabei die
beiden gebundenen strukturellen Atome.

## Univariate Homogenisierung

Ein univariates Polynom mit Grad höchstens vier kann für diese Engine durch eine
explizite strukturelle Einheit auf Grad vier homogenisiert werden:

```text
x^4 + 4
  -> x^4 + 4*1^4.
```

Die Einheit erweitert den Ring ausdrücklich. Sie wird beim Rendern nicht als
sichtbares `1^n` ausgegeben.

## Untrusted Proposal und unabhängige Prüfung

Ein Engine-Proposal enthält:

- skalare Einheit;
- kanonisch sortierte Faktoren mit positiven Multiplizitäten;
- einen ungelösten Rest, hier exakt eins;
- Engine-Zertifikat und Rohresultat-Hash.

Gleiche Faktoren werden zusammengeführt. Ein konstanter Rest ungleich eins ist
nicht kanonisch und muss in die skalare Einheit verschoben werden.

`FactorizationVerifier.execute` prüft unabhängig:

- Engine- und Koeffizientendomänen-ID;
- Kandidaten- und Gesamtbudget;
- Ringgleichheit aller Faktoren;
- exakte Rückmultiplikation von Einheit, Faktorpotenzen und Rest.

Erst danach entsteht ein
`FactorizationVerifier.VerifiedCandidate<BigInteger>` und ein issuer-owned
`FactorizationVerifier.Report<BigInteger>`.

Der positive Status lautet `PARTIAL_FACTORIZATION` mit
`ClaimStrength.VERIFIED_DECOMPOSITION`: Das Produkt ist exakt rekonstruiert,
aber die Engine beweist nicht die Irreduzibilität der beiden quadratischen
Faktoren.

Ein Engine-Miss führt zu `NO_FACTORIZATION_FOUND`. Das ist kein
Irreduzibilitätsbeweis; der Ausdruck kann außerhalb dieser Faktorgradschablone
oder Koeffizientengrenze zerlegbar sein.

## Gemeinsames Work-Budget

Engine und Verifier teilen ein nicht zurücksetzbares Requestbudget. Typische
Stages sind:

```text
engine.divisor-tests
engine.factor-pair-configurations
engine.middle-system-solves
verify.factor-power-multiplications
verify.factor-product-multiplications
verify.product-comparisons
```

Die Stage-Reihenfolge ist kanonisch. Reicht die nach der Engine verbleibende
Arbeit nicht für die Produktrekonstruktion, wird kein Kandidat autorisiert; das
Resultat bleibt `BUDGET_INCONCLUSIVE`.

## Ausdrucksadapter und Suchkante

`PolynomialDecompositionSynthesisOperator` enthält keine Faktorisierungslogik
mehr. Er führt nur noch aus:

```text
Quelltext
  -> PolynomialSemanticView
  -> FactorizationRequest
  -> BinaryQuarticFactorizationEngine
  -> FactorizationVerifier
  -> VerifiedCandidate
  -> Transformation
```

Die Suchkante verwendet weiterhin die Regel-ID

```text
hypothesis_polynomial_decomposition_synthesis
```

und bleibt dadurch von deklarativen Regeln, historischen Spezialbridges und
abgeleiteten Makros unterscheidbar. Exakte Faktorkoeffizienten werden direkt als
Text gerendert und nicht über `NumberExpr(int/double)` zurückgeführt.

Der verbleibende Operator ist ein aktueller Search-Adapter mit konkreter
Verantwortung, keine Kompatibilitätsfassade für die entfernten verschachtelten
Quartiktypen.

## Theory-Subsumption und Cache

`PolynomialTheorySubsumptionClassifier` prüft, ob ein beobachtetes Ziel einer
der unter demselben Budget erzeugten und verifizierten Faktorisierungen
entspricht. Ein positiver Treffer ist eine theorieabgeleitete Instanz, keine neue
Kernelregel und keine externe mathematische Neuheit.

`PolynomialDerivedMacroCache` darf solche Instanzen als begrenzten
Performancecache halten. Er bindet Verifier-Zertifikat, Engine-ID,
Anwendungsschlüssel, vollständiges Work Ledger und mehrere unabhängige
Lineages. Der Cache gehört weder zum Kernel noch automatisch zum
Standardinventar.

Der Discovery-Integrationstest prüft den retained Pfad über mathematische
Wertgleichheit und nicht über historische Renderer-Teilstrings.

## Aktuelle Grenze

Die konkrete Engine unterstützt:

- Koeffizientendomäne `Z`;
- genau zwei strukturelle Atome;
- homogenes Polynom vierten Grades;
- Faktorgradaufteilung `2 + 2`;
- begrenzte ganzzahlige Faktorkoeffizienten;
- begrenzte Engine- und Verifier-Arbeit sowie Kandidatenzahl.

Nicht unterstützt sind durch diese Engine allein:

- beliebige Grade und Faktorgradpartitionen;
- rationale oder algebraische Faktorkoeffizienten;
- vollständige Irreduzibilitätsnachweise;
- allgemeine univariate Faktorisierung;
- mehr als zwei Variablen;
- multivariate Faktorisierung.

Diese Grenzen sind Eigenschaften der Engine und keine Grenzen des Polynom- oder
Faktorisierungsvertrags. Weitere Engines werden unter demselben
Request-/Proposal-/Verifier-Modell ergänzt.

## Prüfung aus dem Checkout

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.polynomial.BinaryQuarticFactorizationEngineTest \
  --tests de.regelsuche.polynomial.FactorizationEngineContractTest \
  --tests de.regelsuche.transform.PolynomialDecompositionSynthesisOperatorTest \
  --tests de.regelsuche.transform.PolynomialTheorySubsumptionClassifierTest
```

Discovery-Integration:

```bash
./gradlew :app:test \
  --tests de.regelsuche.docs.PolynomialDecompositionDiscoveryIntegrationTest
```

Vollständiger Vertrag:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Aussagegrenze

Die Engine belegt eine unabhängig produktverifizierte
quadratisch-mal-quadratische Zerlegungssynthese in einem begrenzten
Quartikfragment. Sie belegt keine vollständige allgemeine
Polynomfaktorisierung und keine Überlegenheit gegenüber einem etablierten CAS.
