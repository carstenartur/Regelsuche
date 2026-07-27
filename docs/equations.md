# Gleichungs-Semantik

Eine Gleichung `a = b` ist *kein* normaler Term, sondern hat eigene
gültige Umformungsregeln. Das Paket `de.regelsuche.equation`
implementiert diese Semantik als eigenständige Engine — analog zu
[`AstRewriteTransformationEngine`](../regelsuche-core/src/main/java/de/regelsuche/transform/AstRewriteTransformationEngine.java)
für Terme.

## Modellklassen

| Klasse                              | Bedeutung                                                |
|-------------------------------------|----------------------------------------------------------|
| `EquationRule`                      | Eine Umformungsregel, die auf einer ganzen Gleichung operiert. |
| `EquationStep`                      | Ergebnis einer Regel: neue Gleichung + emittierte Assumptions. |
| `EquationRewriteContext`            | Bietet Hilfsdaten (zulässige Konstanten, injektive Funktionen). |
| `EquationRewriteEngine`             | Wendet einen Satz von `EquationRule`s an.                |

## Eingebaute Regeln

| Regel                                          | Form                              | Erzeugte Assumption                 |
|------------------------------------------------|-----------------------------------|-------------------------------------|
| `equation_add_both_sides`                      | `a = b → a + c = b + c`           | keine                                |
| `equation_multiply_both_sides`                 | `a = b → a*c = b*c`               | `c ≠ 0`                              |
| `equation_apply_injective_function`            | `a = b → f(a) = f(b)` für `f` injektiv | je nach `f` (z.B. `a, b > 0` für `log`) |

`f` ist injektiv genau dann, wenn der Name in
`EquationRewriteContext.injectiveFunctionNames()` enthalten ist. Standard
ist `exp`; weitere können explizit freigeschaltet werden.

## Warum keine Term-Rewrites?

Term-Rewrites wie `a + 0 → a` müssen *unbedingt* gelten – ohne
Seitenbedingungen. Gleichungs-Umformungen dagegen verändern beide Seiten
gleichzeitig und können neue Voraussetzungen einführen (z.B. `c ≠ 0`).
Würde man sie als Term-Rewrites modellieren, müsste man den
Vergleichsoperator als eigenen AST-Knoten matchen – das vermischt
Gleichungs- und Termsemantik und erschwert Validierung sowie
Erklärbarkeit.

## Beispiel

```java
EquationRewriteEngine engine = new EquationRewriteEngine();
Equation eq = new Equation(parse("2*x"), parse("4"));
EquationRewriteContext ctx = new EquationRewriteContext(
    List.of(new NumberExpr(3)),
    List.of("exp")
);
List<EquationStep> next = engine.step(eq, ctx);
// next[0].equation() = "2*x + 3 = 4 + 3" (Regel: equation_add_both_sides)
```

Die emittierten Assumptions wandern – wie bei Term-Rewrites – in den
ProofBridge-Workflow ([`prover-execution.md`](prover-execution.md)).
