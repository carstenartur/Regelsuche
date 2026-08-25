# Domänenbewusste Polynomfaktorisierung

**Implementierungsstand: 25. August 2026**

Regelsuche behandelt Polynomfaktorisierung nicht mehr als Eigenschaft eines
einzelnen Quartikoperators. Der mathematische Kern besteht aus getrennten
Verträgen für Koeffizientendomänen, Polynomringe, kanonische Polynome,
Faktorisierungsanfragen, Engines, Ergebnisse und unabhängige Prüfung.

Die Architektur ist absichtlich nicht rückwärtskompatibel zu den früheren
verschachtelten Typen von `PolynomialSemanticView` und
`PolynomialDecompositionSynthesisOperator`. Es gibt keine externen Nutzer, deren
Quell- oder Binärkompatibilität diese Trennung rechtfertigen würde. Historische
Evidence behält ihre eingefrorenen Identitäten; aktuelle Produktions-APIs werden
nach fachlicher Verantwortung gestaltet.

## Zielbild

```text
Quelltext und parsergebundene exakte Literale
  -> syntaktische Interpretation
  -> explizite Koeffizientendomäne
  -> unveränderlicher Polynomring
  -> kanonisches Sparse-Polynom
  -> typisierte Faktorisierungsanfrage
  -> ausgewählte Faktorisierungsengine
  -> Einheit + Faktoren mit Multiplizitäten + Rest
  -> unabhängige exakte Produktrekonstruktion
  -> separat bewertete Vollständigkeit und Irreduzibilität
  -> Suchkante, Cache oder Bericht
```

Die Darstellung trennt drei Identitäten:

1. **Quellidentität:** konkrete Schreibweise, Stellenbereiche und
   Literalprovenienz;
2. **mathematische Polynomidentität:** Koeffizientendomäne, geordnete
   Variablen beziehungsweise strukturelle Atome und kanonische Monome;
3. **Faktorisierungsevidence:** Engine, Budget, Faktoren, Multiplizitäten,
   Rest, Vollständigkeitsstärke und Zertifikat.

Eine Änderung der Schreibweise verändert nicht automatisch das mathematische
Polynom. Eine andere Koeffizientendomäne verändert dagegen den Ring und damit
möglicherweise die Irreduzibilität.

## Koeffizientendomänen

`CoefficientDomain<C>` definiert nur exakte kanonische Arithmetik:

- stabile Domain-ID;
- Null und Eins;
- Addition, Negation und Multiplikation;
- kanonische Textdarstellung;
- Größenmaß für Budgets.

Zusätzliche algebraische Fähigkeiten werden durch getrennte Verträge erklärt:

- `ExactField<C>` für exakte Division durch Nichtnullwerte;
- `GcdDomain<C>` für ggT und geprüfte exakte Division.

Die erste Tranche besitzt:

```text
regelsuche.coefficients.integer/v1
regelsuche.coefficients.rational/v1
```

Die rationale Implementierung verwendet den bereits autoritativen
`ExactRational`-Typ. Es existiert keine zweite Brucharithmetik und kein
`double`-Einstieg in die exakte Domäne.

Künftige Primkörper, endliche Erweiterungskörper oder algebraische Zahlkörper
werden als weitere Domänen hinter denselben Fähigkeiten ergänzt. Eine Engine
muss ihre benötigten Fähigkeiten ausdrücklich deklarieren beziehungsweise an
ihrem typisierten Vertrag erkennen lassen.

## Polynomring und kanonisches Polynom

`PolynomialRing<C>` bindet:

- die Koeffizientendomäne;
- eine deterministische, geordnete Variablenliste.

Eine Variable kann ein gewöhnlicher Variablenname oder die stabile Identität
eines vollständigen AST-Teilbaums sein. Anzeige und AST-Vorkommen liegen in
einem separaten View-Objekt; sie gehören nicht zur mathematischen Ringidentität.

`SparsePolynomial<C>` speichert ausschließlich von null verschiedene Terme:

```text
Monomial(exponent vector) -> canonical coefficient
```

Der Typ stellt insbesondere bereit:

- exakte Addition, Subtraktion, Skalierung und Multiplikation;
- Potenzen durch wiederholtes Quadrieren;
- Gesamt- und Variablengrade;
- Termzahl und maximale Koeffizientenbitlänge;
- Homogenitätsprüfung;
- explizite Homogenisierung durch Erweiterung des Rings;
- kanonisches Material für Evidence und Deduplication.

Ein Polynom aus einem anderen Ring kann nicht stillschweigend addiert oder
multipliziert werden. Monom-Arity, Variablenordnung und Domain-ID werden am
Konstruktor beziehungsweise vor jeder binären Operation geprüft.

## Exakte Syntaxgrenze

`PolynomialSemanticView` v2 verwendet `ExpressionParser.parseExactTerm`.
Numerische Koeffizienten und Exponenten werden über die Identität des tatsächlich
vom Parser erzeugten `NumberExpr`-Knotens zu ihrer exakten Literal-Evidence
aufgelöst. Der historische `double`-Wert des Knotens ist für exakte Mathematik
nicht autoritativ.

Damit bleibt beispielsweise

```text
9007199254740993*x^4
```

auch oberhalb der exakten IEEE-754-Ganzzahlgrenze korrekt. Ein fremd erzeugter
numerischer AST-Knoten erbt keine Source-Evidence. Synthetische Nullknoten aus
der Unary-Minus-Darstellung werden nur als exakt null akzeptiert.

`ExactExpressionFormatter` formatiert parsergebundene Teilbäume auf derselben
Grundlage. Er verhindert, dass strukturelle Atome oder spätere Render-Schritte
exakte Werte erneut über `double` interpretieren.

## Faktorisierungsanfrage und Engine-SPI

`FactorizationRequest<C>` enthält:

- das kanonische Quellpolynom;
- die mindestens verlangte Evidenzstärke;
- Kandidatenbudget;
- ein nicht zurücksetzbares arithmetisches Gesamtbudget.

`FactorizationEngine<C>` arbeitet ausschließlich auf dem mathematischen
Polynom. Parser, AST und Benutzeranzeige gehören nicht zur Engine-Schnittstelle.
Dadurch können dieselben Engines aus einer Suchkante, einer Importpipeline, einem
Experiment oder einem externen Backendadapter aufgerufen werden.

Die erste Engine ist:

```text
regelsuche.factorization.binary-quartic-2x2/v1
```

Sie löst weiterhin exakt die Koeffizientenbedingungen einer
quadratisch-mal-quadratisch-Zerlegung binärer homogener Quartiken. Der
Unterschied ist architektonisch wesentlich: Sie ist eine Engine unter einem
allgemeinen Vertrag und nicht mehr die Definition des Polynommodells.

## Ergebnis- und Fehlersemantik

Ein `FactorizationCandidate<C>` enthält:

- skalare Einheit;
- kanonisch sortierte Faktoren mit positiven Multiplizitäten;
- optionalen ungelösten Rest;
- Evidenzstärke;
- Zertifikat.

Gleiche Faktoren werden zusammengeführt. Die Produktrekonstruktion verwendet
Einheit, Potenzen aller Faktoren und Rest.

Terminale Status sind getrennt:

| Status | Bedeutung |
| --- | --- |
| `COMPLETE_FACTORIZATION` | vollständig und unabhängig zertifiziert im deklarierten Ring |
| `IRREDUCIBLE` | unabhängig zertifizierte Irreduzibilität im deklarierten Ring |
| `PARTIAL_FACTORIZATION` | gültige Zerlegung, aber keine vollständige Irreduzibilitätsaussage |
| `NO_FACTORIZATION_FOUND` | die Engine fand in ihrem vollständigen oder begrenzten Verfahren keinen Kandidaten; keine automatische Irreduzibilitätsaussage |
| `UNSUPPORTED_DOMAIN` | Koeffizientendomäne nicht unterstützt |
| `UNSUPPORTED_REQUEST` | Form oder verlangte Evidenzstärke nicht unterstützt |
| `BUDGET_INCONCLUSIVE` | mechanische oder mathematische Arbeitsgrenze erreicht |
| `TECHNICAL_FAILURE` | Vertragsverletzung, ungültige Engineausgabe oder technischer Fehler |

Die bisherige Quartikengine liefert `PARTIAL_FACTORIZATION`, obwohl ihr
unaufgelöster Rest eins ist. Sie prüft die Zerlegung exakt, beweist aber nicht,
dass jeder quadratische Faktor im Ring irreduzibel ist.

## Drei Evidenzstärken

Produktgleichheit und Vollständigkeit sind verschiedene Behauptungen:

1. `DECOMPOSITION_ONLY` — Regelsuche hat das Produkt exakt rekonstruiert;
2. `BACKEND_CLAIMED_COMPLETE` — ein gebundenes Backend behauptet vollständige
   Faktorisierung, die Faktoren wurden intern zurückmultipliziert;
3. `INDEPENDENTLY_CERTIFIED_COMPLETE` — auch Vollständigkeit und
   Irreduzibilität wurden durch eine unabhängige Evidence geprüft.

Ein externes Backend kann daher Reichweite liefern, ohne allein durch seine
Ausgabe einen stärkeren Regelsuche-Claim zu autorisieren.

`FactorizationVerifier` rekonstruiert jedes Kandidatenprodukt unabhängig im
Quellring. Ring- und Produktabweichungen werden vor einer Suchkante abgelehnt.
Für eine vollständige Faktorisierung ist zusätzlich eine
Irreduzibilitäts-/Vollständigkeitsevidence erforderlich.

## Syntaxadapter und Suche

`PolynomialDecompositionSynthesisOperator` ist nur noch ein Adapter:

```text
String
  -> PolynomialSemanticView
  -> FactorizationRequest
  -> BinaryQuarticFactorizationEngine
  -> FactorizationCandidate
  -> gerenderter Transformationstext
```

Er besitzt keine eigene Koeffizientenlösung und kein paralleles
Polynomdatenmodell. Große exakte Faktorkoeffizienten werden beim Rendern nicht
über `NumberExpr(int/double)` geleitet.

`PolynomialTheorySubsumptionClassifier` klassifiziert gefundene Identitäten
gegen typisierte Faktorisierungsevidence. Positiv subsumierte Instanzen können
weiterhin nur in den begrenzten `PolynomialDerivedMacroCache`; sie werden nicht
als neue Kernelgesetze behandelt. Der Cache bindet jetzt die arithmetischen
Arbeitseinheiten der Engine statt eines quartikspezifischen
`consideredConfigurations`-Felds.

## Keine unnötige Rückwärtskompatibilität

Entfernt beziehungsweise ersetzt wurden insbesondere die bisherigen
verschachtelten Typen:

```text
PolynomialSemanticView.Atom
PolynomialSemanticView.Monomial
PolynomialSemanticView.Polynomial
PolynomialDecompositionSynthesisOperator.Candidate
PolynomialDecompositionSynthesisOperator.SynthesisReport
```

Es gibt keine deprecated Duplikate und keine Adapter, die die neue Architektur
wieder auf die alte Quartikoberfläche reduzieren. Ein Adapter wird künftig nur
beibehalten, wenn ein konkretes externes Serialisierungsformat oder ein
unveränderliches historisches Experiment ihn benötigt und dieser Eigentümer
explizit dokumentiert ist.

## Nächste Ausbaustufen

Issue #763 verfolgt die erste vollständige Qualifikationsdomäne

```text
Q[x]
```

mit beliebigem unterstützten Grad unter expliziten Budgets. Die geplante native
Pipeline umfasst:

1. Inhalt und primitiven Anteil;
2. Ableitung, Polynom-ggT und quadratfreie Zerlegung mit Multiplizitäten;
3. geeignete Primzahlauswahl;
4. Faktorisierung über endlichen Körpern;
5. Hensel-Lifting;
6. ganzzahlige Faktorrekomposition, zunächst Zassenhaus;
7. rationale Reassemblierung;
8. unabhängige Produkt- und Vollständigkeitsprüfung.

Parallel kann ein externer Backendadapter dieselben Requests bedienen. Seine
Ergebnisse bleiben Kandidaten, bis Regelsuche sie exakt rekonstruiert hat. Eine
spätere multivariate Pipeline nutzt denselben Ring-, Engine- und
Evidencevertrag, erweitert aber die algorithmische Implementierung.

## Prüfung aus dem Checkout

Fokussierte Kernprüfungen:

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.polynomial.SparsePolynomialTest \
  --tests de.regelsuche.polynomial.BinaryQuarticFactorizationEngineTest \
  --tests de.regelsuche.parse.ExactExpressionFormatterTest \
  --tests de.regelsuche.transform.PolynomialDecompositionSynthesisOperatorTest \
  --tests de.regelsuche.transform.PolynomialTheorySubsumptionClassifierTest
```

Vollständiger Repositoryvertrag:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Aussagegrenze

Die erste Tranche belegt eine erweiterbare, exakte Faktorisierungsarchitektur
und migriert die bisherige Quartikzerlegung ohne parallele Alt-API. Sie belegt
noch keine vollständige Faktorisierung über `Q[x]`, keine multivariate
Faktorisierung und keine Überlegenheit gegenüber etablierten CAS.
