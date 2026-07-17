# Domänenneutraler Discovery-Lifecycle-Handoff

Dieser Baustein führt Issue #224 weiter, ohne die bestehende Produktionssuche oder
die wissenschaftlichen Bedeutungen von Validierung, Beweis, Neuheit, Promotion
und Public Evidence umzudefinieren.

## Zweck

`regelsuche.discovery-lifecycle-handoff/v1` ist die schmale Grenze zwischen einer
Discovery-Ausführung und nachgelagerten Lifecycle-, Persistenz- oder
Exportkomponenten. Der Vertrag enthält ausschließlich:

- die Identität von Kampagne, Domäne und Domänenvertrag;
- Hashes des Eingangs und der vollständigen Quell-Evidence;
- Stage und Disposition;
- optionale Kandidaten- und Zertifikatshashes;
- vollständig bilanzierte Ressourcen;
- domänenneutrale Hash-, Zähl- und Statusmetadaten;
- unverändert getrennte Proof-, Novelty-, Promotion- und Public-Evidence-Status.

Mathematische Repräsentationen wie Ausdrucksstrings, Folgenwerte, Suchzustände
oder Pfade werden nicht in den Handoff kopiert. Wer die Details benötigt, muss
die über `sourceEvidenceHash` gebundene Quell-Evidence laden und deren eigenes
Schema verstehen.

## Generische Domänenläufe

`DiscoveryLifecycleHandoff.from(DomainDiscoveryEvidence)` übernimmt einen Lauf
des generischen `DiscoveryDomain<State, Candidate, Certificate>`-Vertrags.

- `domainContractHash` bindet den vollständigen `DiscoveryDomainDescriptor`.
- `inputHash` bindet den `DiscoverySeed`.
- `sourceEvidenceHash` bindet die vollständige `DomainDiscoveryEvidence`.
- `stage` ist `DISCOVERY_VALIDATION`.
- Nur `CONFIRMED` darf einen ausgewählten Kandidaten- und Zertifikatshash
  enthalten.

Die Referenzevidence deckt sowohl die bestehende Ausdrucks-Rewrite-Domäne als
auch die endliche Differenzen verwendende Zahlenfolgen-Domäne ab. Beide erzeugen
dasselbe Lifecycle-Schema, obwohl ihre Zustände, Kandidaten und Zertifikate
unterschiedliche Typen besitzen.

## Bestehende algebraische Produktionsgeneration

`AutonomousProductionDomainHandoffAdapter` adaptiert die unveränderte,
targetfreie `AutonomousProductionGenerationRunner.GenerationRun`.

Der Adapter führt die Suche nicht erneut aus und verändert weder Seed-Katalog,
Observation Bundle, Discovery Report noch Generation Receipt. Er bindet deren
Hashes und überführt die bereits bilanzierten Ressourcen in denselben
Lifecycle-Vertrag.

Für diesen Übergang gilt ausdrücklich:

- `sourceKind=PRODUCTION_GENERATION_RUN`;
- `stage=GENERATION`;
- `disposition=COMPLETED`;
- kein ausgewählter Kandidat;
- kein Zertifikat;
- Generation ist keine mathematische Validierung;
- Proof, externe Novelty, Promotion und Public Evidence bleiben
  `NOT_EVALUATED`.

Damit kann die bestehende algebraische Produktionspipeline einen
repräsentationsfreien Lifecycle-Handoff liefern, ohne ihre Such- oder
Evidence-Artefakte zu verändern. Die vollständige Umstellung der späteren
Lifecycle-Komposition auf diesen Vertrag bleibt ein weiterer #224-Slice.

## Ressourceninvariante

Jede Ressourcenzeile erfüllt:

```text
configured = executed + skipped + remaining
```

Doppelte Ressourcennamen, negative Werte oder unausgeglichene Zeilen werden vom
Java-Vertrag abgelehnt. Die dedizierte CI prüft dieselbe Bilanz erneut auf den
gespeicherten JSON-Artefakten.

## Reproduzierbarkeit und CI

Der Workflow `Domain Lifecycle Handoff` erzeugt drei gespeicherte Referenzen:

1. Ausdrucks-Rewrite-Discovery;
2. Zahlenfolgen-Discovery;
3. unveränderte algebraische Produktionsgeneration.

Er verlangt byte-identische Wiederholungen, validiert das Draft-2020-12-Schema,
prüft Ressourcenbilanzen und verweigert repräsentationsspezifische Felder wie
`payload`, `seedExpression`, `selectedExpression`, `states` oder `path`.

Schema:

- `docs/schemas/regelsuche-discovery-lifecycle-handoff-v1.schema.json`

## Nicht behauptet

Der Handoff selbst bestätigt keine mathematische Wahrheit und erzeugt weder
einen formalen Beweis noch eine Neuheits-, Promotions- oder
Veröffentlichungsentscheidung. Er ist eine nachvollziehbare Architektur- und
Provenance-Grenze zwischen bereits vorhandener Evidence und späteren Gates.
