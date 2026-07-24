# Rewrite-Regeln

`AstRewriteTransformationEngine.defaultRules()` liefert die Standardliste atomarer Regeln. Jede Regel implementiert `RewriteRule` mit Metadaten:

- `id()` – eindeutiger Bezeichner (`ast_*`, `polynomial_*`, `rational_*`).
- `kind()` – `SIMPLIFY`, `EXPAND`, `FACTOR` oder `NORMALIZE`.
- `mayIncreaseComplexity()` – darf temporär komplexer machen?
- `estimatedCostDelta()` – heuristische Bewertungsänderung.
- `isEquivalencePreservingByConstruction()` – garantierte Äquivalenz.

## Domänen-Pakete

Über `de.regelsuche.rules.RuleDomainRegistry` stehen kuratierte Untermengen bereit:

- `core` – sämtliche Default-Regeln.
- `polynomial` – Polynom-spezifisch (inkl. `polynomial_combine_like_terms`).
- `rational` – Bruchregeln (kürzen, multiplizieren, durch Bruch dividieren). Division durch eine explizite 0 wird beim Matchen abgelehnt.

Domänen liefern weiterhin nur **atomare** Regeln; Lehrbuchformeln (z. B. binomische Formeln) müssen durch mehrschrittige Suchpfade entstehen.
