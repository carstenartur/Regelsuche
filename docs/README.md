# Regelsuche-Dokumentation

Diese Seite ist der zentrale Einstieg in die Dokumentation. Sie ordnet Seiten
nach Zielgruppe und Aufgabe; maschinenlesbare Verträge und generierte Berichte
sind bewusst von Handbüchern und Architekturtexten getrennt.

## Der passende Einstieg

| Ich möchte … | Empfohlener Einstieg |
| --- | --- |
| Regelsuche ausprobieren | [Getting Started](getting-started.md) |
| die Web-Workbench bedienen | [Web-Workbench](web-workbench.md) und [Benutzerhandbuch](web-ui-user-guide.md) |
| einen vollständigen Anwendungsfall nachvollziehen | [User Workflows](user-workflows.md) und [Demo Gallery](demo-gallery.md) |
| den aktuellen Forschungsstand verstehen | [Discovery- und Forschungsstand](discovery-status.md) |
| Claims und Evidence korrekt einordnen | [Capability-Status](generated/capability-status.md), [Glossar](glossary.md) und [Release Readiness](release-readiness.md) |
| die Architektur verstehen oder Code ändern | [Architektur](architecture.md) und [Developer Guide](developer-guide.md) |
| Regeln oder Plugins entwickeln | [Erweiterungssystem](extension-system.md) |
| einen Lauf reproduzieren | [Scientific Reproducibility](scientific-reproducibility.md) und [Independent Reproduction](independent-reproduction.md) |
| einen JSON-Vertrag nachschlagen | [Schema-Katalog](schema-catalog.md) |

## Verbindliche Quellen

Regelsuche verwendet je Informationstyp genau eine maßgebliche Quelle:

| Information | Maßgebliche Quelle |
| --- | --- |
| Bedienabläufe und sichtbare Zustände | Markdown-Handbücher und Browser-E2E-Tests |
| HTTP-Methoden, Pfade, Payloads und Statuscodes | lokale Swagger UI und OpenAPI 3.1 |
| aktueller Forschungsstand | `discovery-status.md` mit Datumsangabe |
| Capability- und Claim-Status | generierte Capability-Matrix und gebundene Evidence |
| Architekturentscheidungen | Architekturtexte und ADRs unter `docs/adr/` |
| maschinenlesbare Verträge | versionierte JSON-Schemas unter `docs/schemas/` |
| Build- und Testsemantik | Gradle, JUnit und Skripte im Checkout |

Die Regeln für Pflege, Sprache, Claim-Grenzen und generierte Abschnitte stehen in
[Dokumentationskonventionen](documentation-conventions.md).

## Nutzung und Produkt

- [Getting Started](getting-started.md) — Standarddemo, lokaler Gradle-Start,
  Full Mode, Sicherheit und erste Schritte.
- [Web-Workbench](web-workbench.md) — fachliche Bereiche und sichtbare
  Ergebnisse der Oberfläche.
- [Web-Workbench-Benutzerhandbuch](web-ui-user-guide.md) — vollständige
  Beschreibung der aktuellen Tabs und Bedienelemente.
- [User Workflows](user-workflows.md) — geführte Abläufe für Suche, Replay,
  Discovery, Proof, Qualität und Export.
- [Demo Gallery](demo-gallery.md) — reproduzierbare Produktdemos mit
  testgenerierten Screenshots.
- [AST-Regelradar](ast-rule-radar.md) — positionsgebundene Regelanwendungen im
  Ausdrucksbaum.
- [Replay und Reports](replay-and-reports.md) — Rechenwege,
  Änderungsdarstellung und Reportartefakte.
- [Math-Domains](math-domains.md) — Gleichungen, Ungleichungen, Analysis und
  lineare Algebra.
- [Glossar](glossary.md) — einheitliche Begriffe für Produkt, Forschung und
  Entwicklung.

## Forschung, Benchmarks und Evidence

### Status und Claim-Grenzen

- [Aktueller Discovery- und Forschungsstand](discovery-status.md)
- [Generierte Capability- und Claim-Matrix](generated/capability-status.md)
- [Release Readiness](release-readiness.md)
- [Von Umformungen zu mathematischen Entdeckungen](from-transformations-to-discovery.md)
- [Bekannte Grenzen](limits.md)

### Discovery und Lernen

- [Discovery Engine](discovery-engine.md)
- [Discovery Evidence v1](discovery-evidence-v1.md)
- [Hypothesis Mining](hypothesis-mining.md)
- [Rule Discovery](rule-discovery.md)
- [Makroregeln und emergente Identitäten](macro-rules.md)
- [Autopilot Planner](autopilot-planner.md)
- [Experiment Runner](experiment-runner.md)

### Vergleich und Reproduktion

- [Comparative Discovery Benchmarks](discovery-benchmarks.md)
- [Scientific Reproducibility](scientific-reproducibility.md)
- [Autonomous Discovery Result Card](autonomous-discovery-walkthrough.md)
- [Independent Reproduction Artifact](independent-reproduction.md)
- [Candidate-independent Benchmark](candidate-independent-benchmark.md)
- [Discovery Cost Ledger](discovery-cost-ledger.md)
- [Vector Amortization Report](vector-amortization-report.md)

### Flagship und evolutionäre Programme

- [Flagship Freeze Execution](evolution-rewrite-program-flagship-freeze-execution.md)
- [Evolution Study Contracts](evolution-study-contracts.md)
- [Deterministische TRAIN-Populationen](evolution-population-engine.md)
- [Reale TRAIN-Suchfitness](evolution-train-fitness.md)
- [Rewrite-Program-Mutationen](evolution-rewrite-program-mutations.md)

### Domänen und Solver

- [Generische Discovery-Domänen](generic-discovery-domains.md)
- [Domänenneutraler Lifecycle-Handoff](domain-lifecycle-handoff.md)
- [Generischer Domain-Discovery-Export](domain-discovery-export.md)
- [Export-Verifikation](domain-discovery-export-verification.md)
- [Domain-generic Qualification](domain-generic-qualification.md)
- [Exakte lineare Rekurrenzen](linear-recurrence-discovery-domain.md)
- [Solver-neutrale IR](solver-neutral-ir.md)
- [Capability-aware Solver-Portfolio](solver-portfolio.md)

## Architektur und Entwicklung

- [Architektur](architecture.md) — Systemkontext, Schichten, Trust-Grenzen und
  zentrale Ausführungsflüsse.
- [Modulstruktur](module-structure.md) — exakte Gradle-Module und Paketzuordnung.
- [Dependency-Regeln](dependency-rules.md) — erlaubte und verbotene
  Abhängigkeitsrichtungen.
- [Developer Guide](developer-guide.md) — lokaler Entwicklungsablauf und
  Änderungstypen.
- [Testing](testing.md) — autoritative Gradle-Lebenszyklen und Diagnose.
- [Testing-Strategie](testing-strategy.md) — Testschichten und Zuständigkeiten.
- [Dokumentationskonventionen](documentation-conventions.md) — Seitentypen,
  Quellen der Wahrheit und Claim-Regeln.
- [Documentation Quality Checklist](documentation-quality-checklist.md) —
  Review-Raster für alle Dokumenttypen.
- [ADRs](adr/) — versionierte Architekturentscheidungen.

## Erweiterungen

- [Erweiterungssystem](extension-system.md) — Auswahlhilfe und Trust-Modell für
  alle Erweiterungsformen.
- [Plugins](plugins.md) und [Plugin-API](plugin-api.md) — externe Java-JARs,
  Registries, Paketierung und Reload.
- [Plugin-Veröffentlichung, Kompatibilität und Governance](plugin-publishing-governance.md) —
  reproduzierbarer Publishing-Ablauf, Versionierung, Kuration, Security,
  Incident Response und Revocation.
- [Regeldateien](rule-files.md) — `.regelsuche`-/`.rules`-DSL, Profile,
  Import/Export und Debugging.
- [Knowledge Packs](knowledge-packs.md) — kuratierte Regelbestände mit
  Provenienz und Reviewstatus.
- [Regel-Tiers und Ablation](rule-tiers.md) — Kernel, First-Party-Packs,
  Plugins, Profile und Regelinventar-Manifest.
- [Makros](macros.md) — deklarierte Makros als Suchkanten.
- [Plugin Artifact Index](plugin-artifact-index.md)
- [Plugin Artifact Trust](plugin-artifact-trust.md)
- [Plugin Trust Store Revisions](plugin-trust-store-revisions.md)

Nicht jede interne Registry ist ein öffentlicher Plugin-Endpunkt. Solver,
Discovery-Promotion, Public Evidence und externe Verteilung behalten eigene
Verträge und Gates.

## Betrieb und Integration

- [Persistenz](persistence.md) — unterstützte Betriebsmodi und Konfiguration.
- [Storage Architecture](storage-architecture.md) — In-Memory, JSON,
  PostgreSQL/Hibernate und Artefaktablage.
- [Proof Bridge](proof-bridge.md) — von einem Suchpfad zur versionierten
  Beweisobligation.
- [Proof Workbench](proof-workbench.md) — Job-Lebenszyklus und Artefakte.
- [Mathematical Algorithms](mathematical-algorithms.md) — Algorithmen,
  Registries und Backend-Grenzen.
- **REST-Referenz:** lokale Swagger UI unter
  `/static/openapi/index.html`; OpenAPI JSON unter
  `/static/openapi/openapi.json`.

## Referenz und generierte Dokumente

- [Schema-Katalog](schema-catalog.md) — gruppierter Einstieg in die
  maschinenlesbaren Verträge.
- [Capability-Status-Schema](schemas/regelsuche-capability-status-v1.schema.json) —
  verbindlicher Vertrag der generierten öffentlichen Capability-Matrix.
- [`docs/schemas/`](schemas/) — vollständiger, versionierter Schema-Bestand.
- [`docs/generated/`](generated/) — aus Evidence erzeugte Status- und
  Ergebnisdokumente; nicht manuell editieren.
- [Benchmark Report](benchmark-report.md) — aus dem Checkout erzeugter
  Qualitäts- und Benchmarkbericht (nicht ins Repository eingecheckt; wird von
  `./gradlew benchmarkReport` erzeugt und als CI-Artefakt bereitgestellt).

## Historische Dokumentation

[README.legacy.md](README.legacy.md) bewahrt die frühere Langfassung des
Top-Level-README für historische Detailrecherche. Sie ist keine aktuelle
Einstiegs- oder Vertragsseite.
