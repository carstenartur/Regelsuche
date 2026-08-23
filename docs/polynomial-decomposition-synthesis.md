# Semantische Polynomansicht und exakte Zerlegungssynthese

**Implementierungsstand: 23. August 2026**

Regelsuche behandelt interessante Polynomdarstellungen nicht länger nur als
wachsenden Katalog einzelner Identitäten. Der neue Pfad trennt drei Aufgaben:

```text
AST-Ausdruck
  -> exakte semantische Polynomansicht
  -> allgemeine begrenzte Zerlegungssynthese
  -> geprüfte Suchkante mit Zertifikat
```

Eine historische Identität wie

```text
A^4 + 4*B^4
```

ist damit ein Testfall eines allgemeinen Verfahrens. Weder ihre fertige
Faktorisierung noch die Substitutionen `A^2 = a` und `2*B^2 = b` sind im
Synthesizer hinterlegt.

## Semantische Generatoren statt manueller Substitutionen

`PolynomialSemanticView` übersetzt den gewöhnlichen Ausdrucks-AST in ein
Polynom mit exakten ganzzahligen Koeffizienten und Exponentenvektoren.
Variablen und Funktionsaufrufe werden zu Generatoren. Zusammengesetzte Basen
positiver ganzzahliger Potenzen bleiben als ein Generator erhalten, wenn eine
vollständige Expansion die sichtbare Struktur zerstören würde.

Beispiele:

| Ausdruck | automatisch erkannte Generatoren |
| --- | --- |
| `x^4 + 4*y^4` | `x`, `y` |
| `(x + 1)^4 + 4*y^4` | `x + 1`, `y` |
| `sin(t)^4 + 4*z^4` | `sin(t)`, `z` |

Die Generatoren sind weiterhin echte AST-Teilbäume. Es werden keine globalen
Ersatzvariablen angelegt und keine zustandsbehafteten Stringsubstitutionen
benötigt. Bei der Rückübersetzung erscheinen die ursprünglichen Teilbäume in
der erzeugten Darstellung.

Die v1-Ansicht unterstützt:

- exakte ganzzahlige Koeffizienten;
- Addition, Subtraktion und Multiplikation;
- begrenzte nichtnegative ganzzahlige Potenzen;
- Variablen, Funktionen und geeignete zusammengesetzte Potenzbasen als
  content-addressierte Generatoren;
- explizite Grenzen für AST-Besuche, Terme, Generatoren, Grad, Exponenten und
  Koeffizientengröße.

Division, nicht ganzzahlige Koeffizienten, negative oder symbolische Exponenten
und Budgetüberschreitungen scheitern fehlersicher mit getrennten Statuscodes.

## Allgemeine Zerlegungssynthese

`ExactPolynomialDecompositionSynthesizer` arbeitet ausschließlich auf der
semantischen Polynomansicht. Die erste Version behandelt:

- univariate ganzzahlige Polynome über einen impliziten Einheitsgenerator;
- homogene bivariate ganzzahlige Polynome;
- gemeinsamen numerischen Inhalt und gemeinsamen Monomfaktor;
- begrenzte Zerlegungen in Faktoren niedrigeren Grades.

Das Verfahren:

1. entfernt gemeinsamen Inhalt und Monomfaktor;
2. bildet eine exakte Koeffizientenfolge für die beiden Generatoren;
3. enumeriert innerhalb eines festen Budgets Faktorgrade und ganzzahlige
   Koeffizientenschablonen;
4. prüft jeden Vorschlag durch exakte Polynomdivision;
5. rekonstruiert nur bei Rest `0` einen AST-Kandidaten;
6. multipliziert Inhalt und gemeinsamen Monomfaktor wieder ein;
7. dedupliziert Faktorpaarungen kanonisch;
8. ordnet Ergebnisse deterministisch nach Struktur- und Koeffizientenkosten.

Die Schablone enthält nur unbekannte Koeffizienten eines Faktors. Sie enthält
keine Sophie-Germain-spezifischen Werte und keinen bekannten Zielausdruck.

## Beispiel: historische Quartik als erzeugtes Resultat

Für

```text
x^4 + 4*y^4
```

sieht der Synthesizer die Koeffizientenfolge

```text
[4, 0, 0, 0, 1]
```

und findet durch exakte Division die beiden quadratischen Koeffizientenfolgen

```text
[2, -2, 1]
[2,  2, 1]
```

Daraus entsteht

```text
(x^2 - 2*x*y + 2*y^2) * (x^2 + 2*x*y + 2*y^2)
```

Dasselbe Verfahren erzeugt ohne neue Regel unter anderem die entsprechenden
Faktoren für `(x + 1)^4 + 4*y^4` und `sin(t)^4 + 4*z^4`.

Die Fähigkeit ist nicht auf diese Familie beschränkt. Der Testfall

```text
x^4 + 5*x^2*y^2 + 4*y^4
```

wird durch dieselbe Enumeration und exakte Division in

```text
(x^2 + y^2) * (x^2 + 4*y^2)
```

zerlegt.

Nahe, aber andere Ausdrücke werden nicht in die historische Faktorisierung
gezwungen. `x^4 + 3*y^4` liefert unter dem aktuellen Schablonenbudget kein
Faktorpaar; `x^4 + 4*y^3` liegt außerhalb der homogenen bivariaten v1-Fläche.
`NO_DECOMPOSITION_FOUND`, `UNSUPPORTED` und `BUDGET_EXCEEDED` bleiben getrennte
Ergebnisse.

## Zertifikat und Arbeitsbilanz

Jeder erzeugte Kandidat bindet per SHA-256:

- Algorithmusrevision;
- Hash der vollständigen semantischen Quellansicht;
- Generatorreihenfolge und synthetische Generatoren;
- numerischen Inhalt und gemeinsamen Monomfaktor;
- Quellgrad;
- beide Koeffizientenfolgen;
- Synthesemethode;
- vollständiges Budget;
- Zahl enumerierter Schablonen, exakter Divisionen und akzeptierter
  Kandidaten.

`PolynomialStructureSynthesisOperator` übernimmt den Kandidaten als normale
Suchkante mit Regel-ID

```text
hypothesis_polynomial_structure_synthesis
```

und behält Semantic- und Certificate-Hash in ihrem Anwendungsschlüssel. Das
macht den Schritt reproduzierbar und verhindert, dass ein schnellerer Rechner
als weniger mathematische Arbeit ausgegeben wird.

## Discovery-Integration

Der kanonische Operatorname lautet:

```text
polynomial_structure_synthesis
```

Die bisherigen Szenarionamen `sophie_germain_bridge` und
`hidden_structure_bridge` bleiben als Kompatibilitätsalias erhalten, erzeugen
aber denselben allgemeinen `PolynomialStructureSynthesisOperator`.

Der frühere `DifferenceOfSquaresPreparationOperator` bleibt als expliziter
Legacy-/Diagnosevergleich registriert, ist in den allgemeinen Discovery-Profilen
aber nicht mehr standardmäßig aktiv. Der Sophie-Germain-Szenariolauf und der
runtime-blinde Hidden-Rule-Fall verlangen nun die allgemeine Synthese-Regel-ID.

## Konsequenz für gelerntes Wissen

Eine vom Miner beobachtete Formel ist nicht automatisch neues dauerhaftes
mathematisches Wissen. Kann der Theoriealgorithmus sie aus ihrer linken Seite
unter einem gebundenen Budget erneut erzeugen, ist sie **theorie-subsumiert**:

```text
beobachtete Formel
  -> durch PolynomialStructureSynthesisOperator reproduzierbar
  -> optionaler abgeleiteter Makro-Cache
  -> keine neue Kernelregel
```

Gelernt werden soll vor allem, wann die semantische Polynomansicht und welche
Synthesebudgets erfolgversprechend sind. Häufig verwendete konkrete Ergebnisse
können weiterhin als widerrufbare Performance-Makros dienen, ohne den
mathematischen Regelkern aufzublähen.

## Bewusste Grenzen

Die erste Version ist keine vollständige multivariate Faktorisierung. Noch
nicht allgemein unterstützt sind insbesondere:

- mehr als zwei semantische Generatoren;
- inhomogene bivariate Zerlegungen;
- rationale, algebraische oder funktionale Koeffizientenkörper;
- vollständige Faktorisierung über endlichen Körpern oder algebraischen
  Erweiterungen;
- unbeschränkte Grade und Koeffizienten;
- Auswahl der fachlich interessantesten unter vielen äquivalenten
  Darstellungen.

Diese Grenzen sind Erweiterungspunkte des Theorieverfahrens und kein Grund,
für jeden fehlenden Fall eine neue Identitätsregel anzulegen.

## Reproduktion

Fokussierte Tests:

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.polynomial.PolynomialSemanticViewTest \
  --tests de.regelsuche.polynomial.ExactPolynomialDecompositionSynthesizerTest \
  --tests de.regelsuche.transform.PolynomialStructureSynthesisOperatorTest
```

Discovery- und Hidden-Rule-Integration:

```bash
./gradlew :app:test \
  --tests de.regelsuche.docs.SophieGermainScenarioDiscoveryTest \
  --tests de.regelsuche.docs.SophieGermainFamilyDiscoveryTest \
  --tests de.regelsuche.docs.HiddenRulePilotRunnerTest
```

Vollständiger Repositoryvertrag:

```bash
./gradlew --no-configuration-cache ciCheck
```

## Aussagegrenze

Ein positiver Lauf belegt eine begrenzte exakte Polynomzerlegung innerhalb der
angegebenen Repräsentations- und Arbeitsbudgets. Er belegt weder vollständige
Polynomfaktorisierung noch externe mathematische Neuheit oder allgemeine
Überlegenheit gegenüber Computer-Algebra-Systemen.
