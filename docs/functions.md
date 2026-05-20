# Funktionsausdrücke

Seit der Erweiterung des AST um `FunctionExpr` versteht Regelsuche
mathematische Funktionsanwendungen als eigene Knotenart und nicht nur
als String-Pattern.

## Unterstützte Funktionen

| Name    | Bedeutung               | Domain-Hinweise                |
|---------|-------------------------|--------------------------------|
| `sin`   | Sinus                   | Reell, periodisch              |
| `cos`   | Kosinus                 | Reell, periodisch              |
| `tan`   | Tangens                 | `cos(arg) ≠ 0` erforderlich    |
| `log`   | Dekadischer Logarithmus | `arg > 0` erforderlich         |
| `ln`    | Natürlicher Logarithmus | `arg > 0` erforderlich         |
| `sqrt`  | Quadratwurzel           | Üblicherweise `arg ≥ 0`        |
| `exp`   | Exponentialfunktion     | Total auf den Reellen          |
| `abs`   | Betragsfunktion         | Total auf den Reellen          |

## Syntax

```
sin(x)
cos(2*y + 1)
log(a*b)
sqrt(x^2 + 1)
exp(log(x))
abs(a - b)
```

Funktionen können beliebig verschachtelt werden. Die Argumente sind ganz
normale Ausdrücke; jeder Teil-Ausdruck wird wieder vom Pattern-Matching
erreicht (siehe [`rewrite-rules.md`](rewrite-rules.md)).

## Auswirkungen auf andere Komponenten

* **Parser** (`ExpressionParser`) erkennt die Funktionsnamen aus der
  Tabelle oben und liefert `FunctionExpr`-Knoten.
* **Formatter** (`ExpressionFormatter`) erzeugt `name(arg1, arg2, …)` und
  klammert Argumente korrekt.
* **Kanonisierung** (`ExpressionCanonicalizer`) behandelt Funktionen als
  eigene Knotenklasse — `sin(x) + cos(y)` ist unterschiedlich zu
  `cos(y) + sin(x)` *nur* in der Reihenfolge der Operanden, aber gleich
  modulo Kommutativität von `+`.
* **Pattern-Matching** (`PatternExpr.Function`, `RulePatternParser`) erlaubt
  Regelpatterns wie `sin(A)^2 + cos(A)^2 -> 1`. Funktionsnamen müssen exakt
  übereinstimmen; Argumente werden als Sub-Pattern matched.
* **Symbolische Auswertung** (`SymPyEquivalenceService`) wertet die obigen
  Funktionen numerisch über `java.lang.Math` aus (`log` → `Math.log10`,
  `ln` → `Math.log`).
* **Rewrite-Engine** (`AstRewriteTransformationEngine`) rekursiert in die
  Argumente, so dass eine Regel auf `sin(2*x)` greift, sobald sie auf den
  Sub-Ausdruck `2*x` passt.
* **Export/Import** (JSON-Bundles) erhält die Funktionsausdrücke unverändert.

## Beispiele

```text
log(a*b)                  → log(a) + log(b)          (assumes a>0, b>0)
sin(x)^2 + cos(x)^2       → 1                        (unconditional)
sqrt(a^2)                 → abs(a)                   (unconditional)
exp(log(x))               → x                        (assumes x>0)
tan(x)                    → sin(x) / cos(x)          (assumes cos(x)≠0)
```

Siehe [`equations.md`](equations.md) für die Behandlung von
Gleichungen und [`assumptions.md`](assumptions.md) für das
Assumption-Modell.
