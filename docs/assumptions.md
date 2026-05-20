# Symbolische Nebenbedingungen (Assumptions)

Das `de.regelsuche.assumption`-Paket modelliert symbolische Vorbedingungen, die eine Regel beim Anwenden braucht.

## Modell

`Assumption(kind, expression, symbols)` mit den Arten:

* `NON_ZERO` – z. B. `b != 0` für Bruch-Regeln
* `POSITIVE` / `NON_NEGATIVE` – für Logarithmus-, Wurzel-, Beträge
* `DOMAIN` – freie Bereichseinschränkung (`x ∈ ℤ`)
* `CUSTOM` – beliebige Bedingung als String

Praktische Konstruktoren: `Assumption.nonZero("b")`, `Assumption.positive("x")`.

`AssumptionContext` sammelt Assumptions entlang eines Transformationspfads und dedupliziert sie nach `expression()`.

## Integration in Regeln

`RewriteRule` hat eine Default-Methode `assumptions(subtree)` (leer). Regeln, die eine Bedingung einführen, überschreiben sie. Beispiel `RationalRules`:

```java
@Override
public List<Assumption> assumptions(Expr subtree) {
    Parts parts = extract(subtree);
    if (parts == null) return List.of();
    return List.of(
        Assumption.nonZero(format(parts.b)),
        Assumption.nonZero(format(parts.c))
    );
}
```

So liefern `rational_cancel_common_factor`, `rational_multiply_fractions` und `rational_divide_by_fraction` jetzt die jeweils notwendigen Nenner-Bedingungen.

## Weitergabe an die Proof-Bridge

`ProofBridgeService.attempt(candidate, assumptions)` reicht die Assumptions an die jeweilige Bridge (Lean/SMT) weiter, die sie in der Zielsprache als Hypothesen bzw. `assert (distinct …)` rendert. Siehe [proof-bridge.md](proof-bridge.md).

## Aktueller Stand

* `RationalRules` ist die erste Regelfamilie, die Assumptions tatsächlich emittiert.
* Die Such- und Mining-Pipelines tragen Assumptions noch nicht automatisch entlang von Pfaden – dafür ist `AssumptionContext` als Sammelpunkt vorbereitet, aber die `TransformationSearchService`-Integration ist eine spätere Erweiterung (siehe `limits.md`).
