# Schematische Beweispläne: exakte Resttermkomposition

## Ziel

Regelsuche kann lokale algebraische Regeln lernen und exakt wiederverwenden.
Längere historische Herleitungen scheitern jedoch häufig nicht an einer
fehlenden Einzelregel, sondern an der kombinatorischen Wahl mehrerer
komplementärer Anwendungen.

Der erste implementierte Baustein für schematische Beweispläne behandelt
deshalb lokale Transformationen mit einem expliziten algebraischen Restterm:

```text
Quellfragment
  = strukturierter Kandidat
  + exakter Restterm
```

Ein solcher Effekt ist noch keine ausführbare Äquivalenzkante. Erst mehrere
disjunkte Effekte, deren Restterme sich exakt zu null addieren, ergeben eine
zulässige Gesamttransformation.

## Implementierter Vertrag

`ExactPolynomialResidualComposer` arbeitet im begrenzten Polynomfragment mit
exakten rationalen Koeffizienten. Der Ablauf ist:

```text
source expression
  -> parser-issued exact literal provenance
  -> occurrence-bound additive source components
  -> locally verified transformed fragments
  -> select one structured subexpression per fragment
  -> compute exact polynomial residuals
  -> enumerate bounded disjoint effect combinations
  -> require complete source reconstruction
  -> require residual sum = 0
  -> reconstruct and independently recheck the candidate expression
```

Die API besitzt absichtlich keinen Zielausdruck. Historische Namen,
Referenzdarstellungen und erwartete Resultate dürfen erst nach Bildung und
Freeze der Kandidaten ausgewertet werden.

Jeder Effekt behält die gebundenen Quellkomponenten und ihre
Vorkommensschlüssel, das vollständige transformierte Fragment, den ausgewählten
strukturierten Teil, den kanonischen exakten Restterm sowie primitive Regel-IDs
und Anwendungsschlüssel. Eine Komposition behält zusätzlich die vollständige
Quellpartition, die ausgewählten Effekte und den rekonstruierten Kandidaten.

## Exakte Zahlengrenze

Der Composer verwendet `ExpressionParser.parseExactTerm` und den vorhandenen
`ExactExpressionFormatter`. `ExactResidualPolynomialArithmetic` projiziert
diesen parsergebundenen AST in die vorhandenen `Polynomial`-/`Rational`-Typen;
`Rational` delegiert an den gemeinsamen `ExactRational`-Kern. Es gibt weder
einen zweiten Parser noch ein weiteres Polynommodell.

`NumberExpr.value()`, `Rational.fromDouble` und der Legacy-Formatter sind keine
Autorität dieses Pfads. Insbesondere dürfen `9007199254740992` und
`9007199254740993` nicht zusammenfallen. Auch die Teilbaumprüfung verwendet die
exakte Darstellung: ein durch Rundung gleich aussehender numerischer Teilbaum
ist kein gültiger Vorkommensnachweis.

Endliche Dezimalliterale bleiben exakt. Division ist ausschließlich durch eine
exakt als von null verschieden nachgewiesene Konstante erlaubt. Dadurch bleiben
auch Restterme wie `1/3` beim Rendern und erneuten Einlesen exakt. Exponenten
müssen nichtnegative ganzzahlige Literale sein; ein zu `2` rundender Wert wie
`2.00000000000000001` darf keine quadratische Potenz autorisieren.

Die Projektion begrenzt vor dem rekursiven Parsen die Eingabe auf 16.384 Zeichen
und 256 strukturelle Token. Weitere Grenzen sind Exponent 32, Gesamtgrad 128,
512 Terme und 4.096 Bits je Koeffizient. Vor einer Multiplikation werden die
Termpaarzahl, eine konservative Koeffizientenwachstumsgrenze und das verbleibende
Budget von 65.536 Termprodukten pro Projektion geprüft. Zu große, undefinierte
und nicht unterstützte Eingaben werden abgelehnt, nicht angenähert.

Diese lokalen Schutzgrenzen sind noch keine vollständige kanonische
Gesamtarbeitsbilanz für einen späteren Vergleich gelernter Suchpläne. Die
Kompositionsgrenzen bleiben separat: höchstens 64 Komponenten, 128 Effekte,
Kompositionsgröße 8, 1.024 Ergebnisse und höchstens 1.000.000 mögliche
Effektkombinationen im Vorabtest. Eine ergebnisbegrenzte Liste darf nicht als
vollständig ausgeschöpfter Suchraum interpretiert werden.

## Brahmagupta–Fibonacci-Kontrollfall

Der Integrationstest lernt und friert zunächst unabhängig die allgemeine
Quadratergänzung

```text
p^2 + q^2 -> (p + q)^2 - 2*p*q
```

ein. Erst danach wird

```text
(a^2 + b^2) * (c^2 + d^2)
```

eingeführt. Die deklarierte Normalformvorbereitung und die bestehende
Monomquadrat-Exposition liefern vier vorkommensgebundene Quadratterme.
Dieser Kontrollfall verwendet kleine exakte Ganzzahlen; er qualifiziert nicht
automatisch die Legacy-Normalformvorbereitung für beliebige Eingabeliterale.
Die exakte Composer-Grenze bezieht sich auf die tatsächlich übergebene Quelle.

Für jedes ungeordnete Paar werden zwei allgemeine Effekte erzeugt:

1. die plus-zentrierte Anwendung der eingefrorenen Regel;
2. dieselbe Regel nach der allgemeinen Symmetrie `X^2 -> (-X)^2` am zweiten
   Quadrat.

Ohne die vorzeichengespiegelten Effekte darf keine zulässige
Nullrest-Komposition entstehen. Mit beiden Effektarten verlangt der
Integrationstest genau die beiden klassischen Darstellungen

```text
(ac - bd)^2 + (ad + bc)^2
(ac + bd)^2 + (ad - bc)^2
```

Die historische Referenz wird erst anschließend zur Korrespondenzprüfung
geöffnet. Dieser bekannte Entwicklungsfall ersetzt keinen neuen,
familienfremden FINAL TEST.

## Aussagegrenze

Der Slice implementiert exakte lokale Resttermprüfung, die Wahl disjunkter
Summandenpaare und die Auswahl komplementärer Vorzeichen durch Nullrest- und
Quellrekonstruktion ohne Zielausdruck im Composer.

Die übergebenen Regel-IDs und Anwendungsschlüssel sind bewahrte Provenienz.
Der Composer prüft die mathematischen Gleichheiten erneut, spielt aber die
behaupteten primitiven Regeln nicht selbst gegen deren ausführbare Definitionen
ab. Eine `Composition` ist daher kein unabhängig replay-verifiziertes
`RewriteProgram` und kein Produktions-Promotionsbeleg.

Noch offen sind das Lernen der Resttermstrategie aus TRAIN-Aufgaben, eine
allgemeine `SchematicProofPlan`-Sprache mit freien Term- oder Koeffizientenlücken,
die autonome Wahl der vorbereitenden Normalform beziehungsweise des Strukturtyps
sowie Quantoren, Induktion, Widerspruch und Existenzbeweise. Externe mathematische
Neuheit wird nicht beansprucht.

Der nächste Schritt unter #874 ist, erfolgreiche Effektkompositionen als
`RewriteProgram`-Plan mit strukturellen Selektoren und einer
`RESIDUAL_SUM_IS_ZERO`-Obligation zu generalisieren, generationengetrennt zu
lernen und auf einen familienfremden Holdout zu übertragen.

## Reproduktion

```bash
./gradlew \
  :regelsuche-math-algorithms:test \
  --tests '*ExactPolynomialResidualComposerTest' \
  --tests '*ExactResidualSourceProvenanceTest'

./gradlew \
  :app:test \
  --tests '*BrahmaguptaResidualCompositionIntegrationTest'
```

Die Provenienztests decken große Ganzzahlen, Dezimalliterale, Exponenten,
Teilbaumverwechslungen, rationale Restterme, vollständige Kompositionen und
Eingabe-/Entwicklungsgrenzen ab. Testdefinitionen sind nicht mit ausgeführten
Läufen gleichzusetzen; maßgeblich ist der CI-Bericht zum konkreten Commit.

Der vollständige Checkout-Vertrag bleibt:

```bash
./gradlew --no-configuration-cache ciCheck
```
