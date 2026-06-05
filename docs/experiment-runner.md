# Experiment Runner

Für wissenschaftliche Discovery-Läufe nutzt Regelsuche den `DeterministicDiscoveryExperimentRunner`.

Eigenschaften:

- deterministische Seed-Reihenfolge über `SeedExpression.stableKey()`
- globales Budget
- optionale Parallelität ohne veränderte Report-Reihenfolge
- stabile JSON-/Replay-Artefakte für CI und Reproduktionsanhänge

Wichtige Klassen:

- `ScientificSeedCorpora`
- `SeedExpression`
- `DeterministicDiscoveryExperimentRunner`
- `ScientificDiscoveryWorkflow`

Typische Kommandos:

```bash
./gradlew :app:test --tests de.regelsuche.discovery.ScientificDiscoveryReproductionTest
./gradlew benchmarkReport
./gradlew runDiscoveryCampaign1
./gradlew runDiscoveryCampaign2
./gradlew runDiscoveryCampaign3
./gradlew runDiscoveryCampaign4
./gradlew runDiscoveryCampaign5
./gradlew runDiscoveryCampaign6
./gradlew runDiscoveryPromotionPipeline
```

## Discovery Campaign 6: Open-Ended Identity Mining

Im Gegensatz zu den Kampagnen 1-5, die kuratierte Eingabe-/Ziel-Paare prüfen, erzeugt
`DiscoveryCampaignSixRunner` Kandidaten selbst. Bausteine:

- **Seed-Familien**: bekannte Identitäten (vollständiges Quadrat, Differenz von Quadraten,
  Binomialkubus, …) mit einem Platzhalter `U`.
- **Substitutionen**: systematisch eingesetzte Terme wie `x+1`, `sin(x)`, `a+b`, `x^2`, `2*x`.
- **Äquivalenzprüfung**: deterministisch über die Polynom-Normalform (offline, immer verfügbar);
  zusätzlich SymPy als Orakel-Evidenz, sofern vorhanden.
- **Ranking** nach fünf Faktoren: Kürze, Überraschung, Pfadlänge, Wiederverwendbarkeit und
  Unterschied zum Ausgangsausdruck.
- **Report** (`app/build/reports/discovery-campaign-6/`): Top 20 Kandidaten mit Pfad,
  Beweis-/Orakel-Evidenz, Begründung der Interessantheit und Markierung, ob als Makro promotable.

Siehe auch:

- [docs/rule-discovery.md](rule-discovery.md)
- [docs/scientific-reproducibility.md](scientific-reproducibility.md)
