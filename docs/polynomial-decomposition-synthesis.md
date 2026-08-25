# Semantische Polynomansicht und quartische Zerlegungsengine

**Implementierungsstand: 25. August 2026**

Die ursprüngliche Zerlegungssynthese bleibt als mathematische Fähigkeit erhalten,
ist aber nicht länger die zentrale Polynomarchitektur. Der allgemeine Vertrag
für Domänen, Ringe, Polynome, Faktorisierungsengines, Ergebnisse und Evidence
steht unter
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

Die mathematische Identität liegt in einem `PolynomialRing<BigInteger>` und
einem `SparsePolynomial<BigInteger>`. Anzeigezeichenfolgen und konkrete
AST-Knoten werden separat im View gebunden. Dadurch kann später dieselbe
Polynomengine auch aus anderen Eingabepfaden aufgerufen werden.

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

## Beispiele derselben Engine

### Sophie-Germain

```text
A^4 + 4*B^4
```

liefert unter anderem

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

## Ergebnisvertrag

Die Engine gibt `FactorizationCandidate<BigInteger>` zurück. Jeder Kandidat
enthält:

- skalare Einheit;
- zwei kanonische Faktoren mit Multiplizitäten;
- den auf eins reduzierten Rest;
- ein content-addressed Zertifikat;
- Evidenzstärke `DECOMPOSITION_ONLY`.

`FactorizationVerifier` multipliziert Einheit, Faktoren und Rest unabhängig
zurück und verlangt exakte Gleichheit mit dem Quellpolynom.

Die Engine meldet trotzdem `PARTIAL_FACTORIZATION`, nicht
`COMPLETE_FACTORIZATION`: Sie beweist die Zerlegung, aber nicht die
Irreduzibilität der beiden quadratischen Faktoren im deklarierten Ring.

Ein nicht gefundener Kandidat wird als `NO_FACTORIZATION_FOUND` ausgegeben. Das
ist kein Irreduzibilitätsbeweis; der Ausdruck kann außerhalb dieser
Faktorgradschablone oder Koeffizientengrenze zerlegbar sein.

## Ausdrucksadapter und Suchkante

`PolynomialDecompositionSynthesisOperator` enthält keine Faktorisierungslogik
mehr. Er führt nur noch aus:

```text
Quelltext
  -> PolynomialSemanticView
  -> FactorizationRequest
  -> BinaryQuarticFactorizationEngine
  -> geprüfter FactorizationCandidate
  -> Transformation
```

Die Suchkante verwendet weiterhin die Regel-ID

```text
hypothesis_polynomial_decomposition_synthesis
```

und bleibt dadurch von deklarativen Regeln, historischen Spezialbridges und
abgeleiteten Makros unterscheidbar. Exakte Faktorkoeffizienten werden direkt als
Text gerendert und nicht über `NumberExpr(int/double)` zurückgeführt.

## Theory-Subsumption und Cache

`PolynomialTheorySubsumptionClassifier` prüft, ob ein beobachtetes Ziel einer
der unter demselben Budget erzeugten Faktorisierungen entspricht. Ein positiver
Treffer ist eine theorieabgeleitete Instanz, keine neue Kernelregel und keine
externe mathematische Neuheit.

`PolynomialDerivedMacroCache` darf solche Instanzen als begrenzten
Performancecache halten. Er bindet Zertifikat, Engine-ID, Anwendungsschlüssel,
arithmetische Arbeit und mehrere unabhängige Lineages. Der Cache gehört weder
zum Kernel noch automatisch zum Standardinventar.

## Aktuelle Grenze

Die konkrete Engine unterstützt:

- Koeffizientendomäne `Z`;
- genau zwei strukturelle Atome;
- homogenes Polynom vierten Grades;
- Faktorgradaufteilung `2 + 2`;
- begrenzte ganzzahlige Faktorkoeffizienten;
- begrenzte arithmetische Arbeit und Kandidatenzahl.

Nicht unterstützt sind durch diese Engine allein:

- beliebige Grade und Faktorgradpartitionen;
- rationale oder algebraische Faktorkoeffizienten;
- vollständige Irreduzibilitätsnachweise;
- allgemeine univariate Faktorisierung;
- mehr als zwei Variablen;
- multivariate Faktorisierung.

Diese Grenzen sind jetzt Eigenschaften einer Engine und keine Grenzen des
Polynom- oder Faktorisierungsvertrags. Weitere Engines werden unter demselben
Request-/Result-Modell ergänzt.

## Prüfung aus dem Checkout

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.polynomial.BinaryQuarticFactorizationEngineTest \
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

Die Engine belegt eine exakt verifizierte quadratisch-mal-quadratische
Zerlegungssynthese in einem begrenzten Quartikfragment. Sie belegt keine
vollständige allgemeine Polynomfaktorisierung und keine Überlegenheit gegenüber
einem etablierten CAS.
