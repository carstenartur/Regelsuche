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

`ExactPolynomialResidualComposer` arbeitet im bereits vorhandenen exakten
Polynomfragment. Der Ablauf ist:

```text
source expression
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

Jeder Effekt behält:

- die gebundenen Quellkomponenten und ihre Vorkommensschlüssel;
- das vollständige transformierte Fragment;
- den ausgewählten strukturierten Teil;
- den kanonischen exakten Restterm;
- primitive Regel-IDs und Anwendungsschlüssel.

Eine Komposition behält zusätzlich die vollständige Quellpartition, die
ausgewählten Effekte, den rekonstruierten Kandidaten und die zusammengeführte
primitive Lineage.

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

eingeführt. Eine exakte Polynomnormalform und die bestehende
Monomquadrat-Exposition liefern vier vorkommensgebundene Quadratterme.

Für jedes ungeordnete Paar werden zwei allgemeine Effekte erzeugt:

1. die plus-zentrierte Anwendung der eingefrorenen Regel;
2. dieselbe Regel nach der allgemeinen Symmetrie `X^2 -> (-X)^2` am zweiten
   Quadrat.

Ohne die vorzeichengespiegelten Effekte existiert keine zulässige
Nullrest-Komposition. Mit beiden Effektarten findet die begrenzte,
zielunabhängige Kombination genau die beiden klassischen Darstellungen

```text
(ac - bd)^2 + (ad + bc)^2
(ac + bd)^2 + (ad - bc)^2
```

Die historische Referenz wird erst anschließend zur Korrespondenzprüfung
geöffnet.

## Aussagegrenze

Der Slice belegt:

- exakte Bildung lokaler strukturierter Effekte aus vorhandenen beziehungsweise
  eingefrorenen Regeln;
- automatische Wahl disjunkter Summandenpaare;
- automatische Wahl komplementärer Vorzeichen über Restterm-Ausgleich;
- exakte Nullrest- und Quellrekonstruktionsprüfung;
- Bildung historisch relevanter Darstellungen ohne Zielausdruck im Composer.

Er belegt noch nicht:

- das Lernen der Resttermstrategie aus TRAIN-Aufgaben;
- eine allgemeine `SchematicProofPlan`-Sprache mit freien Term- oder
  Koeffizientenlücken;
- autonome Wahl der vorbereitenden Normalform beziehungsweise des
  Strukturtyps;
- Quantoren, Induktion, Widerspruch oder Existenzbeweise;
- externe mathematische Neuheit.

Der nächste Schritt ist, erfolgreiche Effektkompositionen als
`RewriteProgram`-Plan mit strukturellen Selektoren und einer
`RESIDUAL_SUM_IS_ZERO`-Obligation zu generalisieren, generationengetrennt zu
lernen und auf einen familienfremden Holdout zu übertragen.

## Reproduktion

```bash
./gradlew \
  :regelsuche-math-algorithms:test \
  --tests '*ExactPolynomialResidualComposerTest'

./gradlew \
  :app:test \
  --tests '*BrahmaguptaResidualCompositionIntegrationTest'
```

Der vollständige Checkout-Vertrag bleibt:

```bash
./gradlew --no-configuration-cache ciCheck
```
