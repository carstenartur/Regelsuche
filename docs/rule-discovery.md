# Regel-Entdeckung

`RuleCandidateMiner` arbeitet auf entdeckten Umformungsketten (`DiscoveredTransformation` + `TransformationStep`), normalisiert sie über den `ExpressionCanonicalizer`, anti-unifiziert AST-Strukturen und sammelt freie Parameterrelationen. Bekannte Identitäten landen ausschließlich als `RuleCandidate`-Statusbasis (vgl. `KnownRuleRepository`), niemals als direkte Transformationsschritte.

## Lebenszyklus (`CandidateProofStatus`)

```
REJECTED < OBSERVED < VALIDATED_BY_EXAMPLES < SYMBOLICALLY_VERIFIED < FORMALLY_PROVABLE < FORMALLY_PROVED
```

- `OBSERVED` – nur empirisch beobachtet.
- `VALIDATED_BY_EXAMPLES` – auf frischen Beispielen validiert (`CandidateValidator`).
- `SYMBOLICALLY_VERIFIED` – Äquivalenz über `SymPyEquivalenceService` bestätigt.
- `FORMALLY_PROVABLE` – Kandidatengleichung erfüllt nötige Voraussetzungen für einen formellen Beweis.
- `FORMALLY_PROVED` – formell verifiziert (Platzhalter für zukünftige Integration).
- `REJECTED` – Kandidat wurde explizit ausgeschlossen (z. B. Gegenbeispiel gefunden).

Validierung passiert AST-basiert über `RulePatternParser` + `RulePatternInstantiator`, ohne String-Substitution.
