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

## Persistierter Produktions-Export

Der CLI-Einstieg `AutonomousProductionGenerationMain` schreibt die
Produktionsgeneration über `AutonomousProductionGenerationExport`. Der Export
behält die repräsentationsreichen Detailartefakte bei, stellt nachgelagerten
Komponenten aber zusätzlich `lifecycle-handoff.json` als domänenneutralen
Einstieg bereit.

`export-manifest.json` bindet genau sieben Artefaktrollen:

1. Research Brief;
2. Seed Catalog;
3. Observation Bundle;
4. Generation Receipt;
5. Discovery Report;
6. Generation Run;
7. Lifecycle Handoff.

Jeder Eintrag enthält den kanonischen Dateinamen, den semantischen
`sourceContentHash`, den SHA-256 der exakt gespeicherten UTF-8-Bytes und deren
Länge. Die Root-Felder des Manifests binden den vollständigen Generation Run und
den Lifecycle Handoff. Zusätzlich gilt:

```text
lifecycleHandoff.sourceEvidenceHash = manifest.generationRunHash
```

Der Commit erfolgt fail-closed über `MANIFEST_LAST_ATOMIC_RENAME`:

1. ein vorhandenes Manifest wird zuerst entfernt;
2. jedes Detailartefakt wird über eine temporäre Datei und atomare Umbenennung
   ersetzt;
3. das neue Manifest wird erst nach allen Artefakten auf dieselbe Weise
   geschrieben.

Ein fehlendes Manifest kennzeichnet damit einen unvollständigen Export. Ein
Consumer muss vor der Nutzung das Manifest-Schema, alle Byte-Hashes und die
Root-Hash-Bindungen prüfen. Das Manifest selbst enthält keine Ausdrucksstrings,
Zustände oder Pfade.

Die vollständige Umstellung der späteren Lifecycle-Komposition auf diesen
Vertrag bleibt ein weiterer #224-Slice. Insbesondere erzeugt dieser Export noch
keine Proof-, Novelty-, Promotion- oder Public-Evidence-Entscheidung.

## Ressourceninvariante

Jede Ressourcenzeile erfüllt:

```text
configured = executed + skipped + remaining
```

Doppelte Ressourcennamen, negative Werte oder unausgeglichene Zeilen werden vom
Java-Vertrag abgelehnt. Die dedizierte CI prüft dieselbe Bilanz erneut auf den
gespeicherten JSON-Artefakten.

## Reproduzierbarkeit und CI

Der Workflow `Domain Lifecycle Handoff` erzeugt gespeicherte Referenzen für:

1. Ausdrucks-Rewrite-Discovery;
2. Zahlenfolgen-Discovery;
3. den isolierten Handoff der algebraischen Produktionsgeneration;
4. den manifestgebundenen Produktions-Export.

Er verlangt byte-identische Wiederholungen, validiert beide
Draft-2020-12-Schemas, prüft Ressourcenbilanzen, Root- und Byte-Hashes sowie die
Manifest-Vollständigkeit. Handoff und Manifest dürfen keine
repräsentationsspezifischen Felder wie `payload`, `seedExpression`,
`selectedExpression`, `states` oder `path` enthalten.

Schemas:

- `docs/schemas/regelsuche-discovery-lifecycle-handoff-v1.schema.json`
- `docs/schemas/regelsuche-autonomous-production-generation-export-v1.schema.json`

## Nicht behauptet

Handoff und Exportmanifest bestätigen keine mathematische Wahrheit und erzeugen
weder einen formalen Beweis noch eine Neuheits-, Promotions- oder
Veröffentlichungsentscheidung. Sie bilden eine nachvollziehbare Architektur-,
Persistenz- und Provenance-Grenze zwischen bereits vorhandener Evidence und
späteren Gates.
