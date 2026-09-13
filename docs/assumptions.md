# Symbolische Nebenbedingungen (Assumptions)

Das Paket `de.regelsuche.assumption` modelliert Voraussetzungen, unter denen
eine Transformation, ein Kandidat oder eine bekannte mathematische Struktur
gültig beziehungsweise anwendbar ist. Eine Annahme ist keine beiläufige
Textnotiz: Sie gehört zur Identität und Evidence eines mathematischen Ergebnisses.

## Modell

`Assumption(kind, expression, symbols)` verbindet eine typisierte Art mit der
konkreten symbolischen Aussage. Unterstützt werden insbesondere:

- `NON_ZERO`, `POSITIVE`, `NON_NEGATIVE`;
- `INTEGER`, `NATURAL`, `REAL`, `RATIONAL`;
- `INVERTIBLE` und `DOMAIN_MEMBERSHIP`;
- `CUSTOM_PREDICATE` für noch nicht strukturell modellierte Aussagen;
- `UNKNOWN` für explizit nicht entschiedene Information.

Die veralteten Werte `DOMAIN` und `CUSTOM` bleiben nur zur Quellkompatibilität
erhalten. Neue Implementierungen verwenden `DOMAIN_MEMBERSHIP` beziehungsweise
`CUSTOM_PREDICATE`.

Praktische Konstruktoren sind unter anderem:

```java
Assumption.nonZero("b");
Assumption.positive("x");
Assumption.natural("n");
Assumption.invertible("A");
```

`AssumptionContext` sammelt die entlang eines Pfads oder in einem Run bekannten
Annahmen. `AssumptionSignature` erzeugt daraus eine normalisierte,
deterministische Signatur für Zustands-, Evidence- und Cache-Grenzen.

## Dreiwertige Auswertung

Eine erforderliche Annahme wird nicht als boolescher Spezialfall eines einzelnen
Backends ausgewertet. Der gemeinsame Vertrag lautet:

```text
required Assumption + known AssumptionContext
  -> AssumptionEvaluator(s)
  -> per-evaluator evidence
  -> TRUE | FALSE | UNKNOWN
  -> apply | reject | retain obligation
```

`AssumptionTruthValue` unterscheidet:

- `TRUE`: Die deklarierte Evidence erfüllt die Voraussetzung.
- `FALSE`: Die deklarierte Evidence widerlegt die Voraussetzung.
- `UNKNOWN`: Die Voraussetzung wurde nicht entschieden.

`UNKNOWN` darf niemals stillschweigend als `TRUE` behandelt werden.

### Evaluator-Evidence

Jeder `AssumptionEvaluator` besitzt eine stabile ID und Revision. Seine
`AssumptionEvaluationEvidence` enthält zusätzlich:

- das dreiwertige Ergebnis;
- eine maschinenlesbare Disposition;
- eine Erklärung;
- optional einen Verweis auf ein Solver-, Proof- oder anderes Evidence-Artefakt.

Die Dispositionen sind:

| Disposition | Bedeutung |
| --- | --- |
| `EVALUATED` | Der Evaluator hat fachlich ausgewertet; das Ergebnis kann `TRUE`, `FALSE` oder `UNKNOWN` sein. |
| `UNSUPPORTED` | Die Voraussetzung liegt außerhalb des unterstützten Fragments. |
| `TIMEOUT` | Das deklarierte Zeit- oder Arbeitsbudget wurde ausgeschöpft. |
| `TECHNICAL_FAILURE` | Der Evaluator konnte aus technischem Grund kein fachliches Ergebnis liefern. |

Die letzten drei Dispositionen tragen immer `UNKNOWN`. Timeout, fehlende
Übersetzung oder technische Nichtverfügbarkeit sind damit weder mathematische
Widerlegung noch Zustimmung.

### Portfolio und Konflikte

`AssumptionEvaluatorPortfolio` führt eine nichtleere, deterministisch sortierte
Evaluator-Menge aus. Seine Identität ist content-addressed und bindet
Portfolio-Revision, Evaluator-ID, Evaluator-Revision und Implementierungsklasse.
Eine geänderte Evaluatorauswahl oder Revision erzeugt daher eine andere
`evaluatorProfileHash`. Weil die Implementierungsklasse Bestandteil dieser
Identität ist, akzeptiert das Portfolio nur stabile benannte Klassen;
anonyme, lokale, synthetische, versteckte und dynamische Proxy-Klassen werden
abgewiesen. Ein Adapter für ein externes System erhält daher eine kleine
benannte Wrapperklasse statt einer compilerabhängigen Lambda-/Proxy-Identität.

Die Aggregation ist fehlersicher:

- mindestens ein `TRUE` und kein `FALSE` ergibt `TRUE`;
- mindestens ein `FALSE` und kein `TRUE` ergibt `FALSE`;
- `TRUE` und `FALSE` gemeinsam ergeben `UNKNOWN` plus `conflicting=true`;
- ausschließlich nicht entscheidende Ergebnisse ergeben `UNKNOWN`.

Alle Einzelergebnisse bleiben in `AssumptionEvaluation` sichtbar. Doppelte
Evaluator-IDs und falsch attribuierte Evidence werden abgewiesen.

## Lokaler Evaluator

`KnownAssumptionEvaluator` ist der erste produktive Evaluator. Er verwendet die
explizit bekannten typisierten Annahmen und die vorhandenen monotonen
Implikationsregeln, beispielsweise:

```text
NATURAL  -> INTEGER -> RATIONAL -> REAL
POSITIVE -> NON_ZERO
```

Fehlt eine ausreichende explizite Aussage, bleibt das Ergebnis `UNKNOWN`. Der
Evaluator erfindet keine Annahmen und ruft kein externes System auf.

Ein rein lokales Portfolio entsteht über:

```java
AssumptionEvaluatorPortfolio portfolio =
    AssumptionEvaluatorPortfolio.localOnly();
AssumptionEvaluation evaluation = portfolio.evaluate(required, context);
```

## Kanonisierung und Definitionsbereich

Die assumption-free Kanonisierung darf nicht nur den Wert eines Ausdrucks auf
seinem bisherigen Definitionsbereich erhalten, sondern auch nicht durch das
vollständige Entfernen eines partiellen Teilbaums unbemerkt einen größeren
Definitionsbereich erzeugen. Insbesondere sind daher Gleichungen wie

```text
x/x - x/x  -> 0
(1/x)^0    -> 1
x^0        -> 1
```

ohne passende Nebenbedingung nicht zulässig. Die ersten beiden linken Seiten
sind bei `x = 0` nicht definiert. Für Potenz null gilt zusätzlich der im Projekt
bereits verwendete fail-closed Vertrag, dass `0^0` nicht als `1` angenommen
wird. Daher darf `A^0 -> 1` nur erfolgen, wenn `A` definiert und ungleich null
ist. `2^0 -> 1` ist damit assumption-free zulässig; `x^0` bleibt dagegen ohne
Kontext erhalten und kann assumption-aware nur zusammen mit `x != 0` zu `1`
werden. `0^0` bleibt fail-closed erhalten.

`ExpressionCanonicalizer` prüft deshalb bei vollständiger Elision eines Terms
rekursiv die strukturellen Domain-Anforderungen. Für explizite Divisionen und
negative ganzzahlige Potenzen entsteht gegebenenfalls eine `NON_ZERO`-
Obligation. Bei Potenz null kommt für die vollständig entfernte Basis zusätzlich
die `NON_ZERO`-Obligation der Basis selbst hinzu. Der assumption-free
`PolynomialNormalizer` behandelt Exponent null nicht als formale
Polynomidentität, damit auch verschachtelte Formen wie `x^0 + y` diese Prüfung
nicht umgehen. Die dokumentierten Built-in-Funktionen werden entsprechend ihrer
reellen Domain behandelt:

| Funktion | Voraussetzung bei vollständiger Elision |
| --- | --- |
| `sin`, `cos`, `exp`, `abs` | keine zusätzliche Domain-Annahme |
| `log`, `ln` | Argument `> 0` (`POSITIVE`) |
| `sqrt` | Argument `>= 0` (`NON_NEGATIVE`) |
| `tan` | `cos(argument) != 0` (`NON_ZERO`) |

Unbekannte Funktionsnamen, mehrstellige Funktionssemantik sowie nichtganzzahlige
oder symbolische Potenzen werden an dieser Grenze konservativ behandelt: Kann
der Definitionsbereich nicht durch den vorhandenen Assumption-Vertrag
beschrieben werden, wird der Teilbaum nicht vollständig wegkanonisiert.

Ohne `AssumptionContext` schlägt eine bedingte Elision fehlersicher fehl. Mit
einem Kontext darf die assumption-aware Kanonisierung die ausdrückbaren
Obligationen in den Kontext aufnehmen und anschließend vereinfachen. Dadurch
kann beispielsweise

```text
1/(x + 1) - 1/(x + 1) -> 0
```

nur zusammen mit `x + 1 != 0` entstehen. Ebenso darf `log(x) - log(x)` nur mit
`x > 0` vollständig verschwinden.

Nicht jede Koeffizientenzusammenfassung ist eine Elision. Bleibt der partielle
Teilbaum erhalten, darf sein Koeffizient weiterhin exakt reduziert werden;
`2*(1/x) - 1/x` kann daher zu `1/x` werden, ohne den Definitionsbereich zu
vergrößern.

Diese strukturelle Prüfung ersetzt keine allgemeine Beweis- oder
Domain-Inferenz. Ob bereits bekannte oder extern bewiesene Annahmen gelten,
bleibt Aufgabe des Assumption-Evaluator-Vertrags. Die Kanonisierung erzeugt
beim assumption-aware Pfad lediglich die für ihre eigene bedingte
Vereinfachung benötigten, expliziten Obligationen.

## Externe Evaluatoren

SymPy, Z3, cvc5 und formale Prover können später denselben Vertrag implementieren.
Dabei gelten folgende Grenzen:

- Externe Systeme laufen reproduzierbar und gepinnt über JUnit/Testcontainers;
- Python, Solver oder weitere Laufzeiten werden nicht zu Host-Voraussetzungen;
- Eingabe, unterstütztes Fragment, Timeout und Ressourcenbudget sind explizit;
- `UNSUPPORTED`, `TIMEOUT` und `TECHNICAL_FAILURE` bleiben maschinenlesbar;
- Solver- oder CAS-Ausgaben sind Evidence, aber nicht automatisch formaler Beweis;
- erwartete technische Terminalzustände werden in `UNKNOWN`-Evidence übersetzt;
  Vertragsverletzungen und fehlerhafte Attribution schlagen dagegen fehlersicher
  fehl.

Die Portfolio-ID muss in Runs, Kandidaten, E-Graph-Saturation und Proof-
Obligationen mitgeführt werden, sobald diese Verbraucher den Vertrag anbinden.

## Integration in Regeln

`RewriteRule.assumptions(subtree)` liefert die Voraussetzungen einer konkreten
Regelanwendung. Rationale, logarithmische, radikale, trigonometrische und
Analysis-Regeln erzeugen bereits passende Annahmen, etwa einen von null
verschiedenen Nenner oder ein positives Logarithmusargument.

Eine bedingte Regel darf künftig erst dann angewendet oder als E-Graph-Union
übernommen werden, wenn ihr deklarierter Guard gemäß dem aktiven Portfolio
`TRUE` ist. `FALSE`, `UNKNOWN` und Konflikte bleiben getrennte, sichtbare
Terminalentscheidungen. Die produktive Einbindung in bedingte Rewrites und
E-Class-Analysen wird in Issue #662 verfolgt.

## Weitergabe an Proof- und Discovery-Evidence

Proof-, Gegenbeispiel- und Representation-Discovery-Komponenten müssen
mindestens binden:

- erforderliche Annahme;
- normalisierte Kontextsignatur;
- Evaluator-Portfolio-Hash;
- aggregiertes Ergebnis und Konfliktstatus;
- vollständige Einzelevidence mit Backend-Revisionen.

Damit kann die Discovery-Oberfläche aus Issue #669 später nachvollziehbar
anzeigen, welche Annahme erfüllt, widerlegt, unbekannt oder zwischen Evaluatoren
umstritten ist, ohne aus einem Backend-Label einen stärkeren Claim abzuleiten.

## Real domains of symbolic derivatives

`diff(body, variable)` denotes a partial real function: the derivative exists
only where the original `body` is differentiable in `variable`. A derivative
formula's standalone AST can have a larger domain. In particular, `1/x` is not
an unconditional replacement for `diff(ln(x), x)` at negative `x`.

The elementary calculus rules retain typed side conditions from the original
body through `RewriteRule.assumptions(source)`. They reuse
`ExpressionDefinedness` for nested logarithm arguments, original quotient
denominators and integer powers. Both `ln(u)` and base-10 `log(u)` require the
actual argument `u > 0`; the latter's formula also retains `ln(10)`.
Non-integer and symbolic powers are treated on the explicitly retained
strictly positive-base branch. These are sufficient conditions, not a claim to
compute the maximal differentiability domain: they may exclude zero or other
real branches on which a particular power is differentiable. Integer powers
retain their usual real branch, including parsed negative integer literals;
`diff(x^1, x)` is `1` and does not introduce an excluded zero.

Sum, difference and product splitting require differentiability of their
operands. When an operand is outside the supported elementary fragment,
rewrites retain a typed `CUSTOM_PREDICATE` of the form
`differentiable(operand, variable)`. This predicate means the operand is defined
in a neighborhood and has a finite real derivative there in that variable.
For example, splitting `diff(abs(x) - abs(x), x)` requires
`differentiable(abs(x), x)`; the rewritten expression is not asserted equivalent
at zero. Such predicates remain unresolved unless a caller supplies appropriate
assumption-aware evidence. The local evaluator requires exact custom-predicate
identity: knowledge of `continuous(abs(x), x)` does not establish
`differentiable(abs(x), x)` merely because the symbols match. This corrected
implication behavior is identified by evaluator revision `known-assumptions/v2`.
These predicates do not add standalone differentiation support
for `abs`, unknown functions or other unsupported operands.

`isEquivalencePreservingByConstruction()` for these rewrites describes the
transformation **together with all its retained assumptions**. Assumptions flow
through ordinary transformation/search evidence, stored steps, graph views,
export and replay. A validator with only an unconditional `EquivalenceService`
returns `UNKNOWN` for guarded transformations and retains the conditions in its
result; an `AssumptionAwareEquivalenceService` can decide the conditional claim.

Standalone callers should use
`Differentiator.differentiateWithAssumptions(body, variable)`, which returns a
`Result(formula, assumptions)` under the same sufficient-domain contract. The
legacy `differentiate` method still returns only a formula AST and must not be
used to infer the derivative's real domain. Any later simplification, evaluation
or export of the result must keep the assumptions, even if a denominator or
logarithm disappears from the formula. The explicit positive-power mode used by
calculus does not change the default domain-preserving simplifier guard.
