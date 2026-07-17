# Regelsuche-Dokumentation

## Einstieg

- [Aktueller Discovery-Stand](discovery-status.md) — gemessene Ergebnisse, wissenschaftliche Grenzen und nächste Arbeitsschritte.
- [Release Readiness für Regelsuche 0.2](release-readiness.md) — getrennte Evidence Profiles, qualifizierter Autonomie-Claim sowie Gradle-/Docker-Reproduktion.
- [Solver-neutrale Obligation- und Proof-IR](solver-neutral-ir.md) — kanonische Problemverträge, strukturierte Annahmen, Backend-Capabilities und zwei unabhängige Adapter.
- [Capability-aware Solver-Portfolio](solver-portfolio.md) — rollenbasierte Backend-Auswahl, Budgets, Timeouts, Cache, Konflikte und formale Z3-Bestätigung.
- [Von Umformungen zu mathematischen Entdeckungen](from-transformations-to-discovery.md) — geschlossene Zielkette von Search über Mining und Falsifikation bis Promotion und Wiederverwendung.
- [Discovery Engine](discovery-engine.md) — technische Komponenten und Einstiegspunkte.
- [Getting Started](getting-started.md) — Docker, Gradle und lokale Nutzung.
- [Demo Gallery](demo-gallery.md) — reproduzierbare UI- und Replay-Beispiele.

## Erweiterungen

- [Erweiterungssystem](extension-system.md) — zentrale Architekturkarte und Auswahlhilfe für Java-Plugins, Regeldateien, Knowledge Packs, deklarative und gelernte Makros, Discovery-Operatoren und mathematische Capabilities.
- [Plugins](plugins.md) — `ServiceLoader`, externe JARs, `PluginRuntime`, Reload, Katalog, Diagnosen und aktuelle Vertrauensgrenzen.
- [Plugin-API](plugin-api.md) — Java-Vertrag, Registries, Paketierung und Starter-Template.
- [Regeldateien](rule-files.md) — `.regelsuche`-/`.rules`-DSL, Profile, Import/Export und Debugging.
- [Makros](macros.md) — vom Autor deklarierte Makros als Suchkanten.
- [Knowledge Packs](knowledge-packs.md) — kuratierte Core-Pakete mit Provenance, Lizenz, Reviewstatus, Risiko und Validierungsbeispielen.
- [Makroregeln und emergente Identitäten](macro-rules.md) — aus Suchpfaden gelernte und erst nach Evidenz-/Promotion-Gates aktivierte Regeln.

Nicht jede interne Registry ist automatisch ein externer Plugin-JAR-Endpunkt. Solver-/Proof-Backends, Discovery-Promotion und Public Evidence behalten ihre eigenen Verträge und Gates.

## Discovery und wissenschaftliche Evidenz

- [Discovery Evidence v1](discovery-evidence-v1.md)
- [Aktueller Open-Target-Discovery-Stand](discovery-status.md)
- [Release Readiness und Kandidatenqualifikation](release-readiness.md)
- [Solver-neutrale Obligation- und Proof-IR](solver-neutral-ir.md)
- [Capability-aware Solver-Portfolio](solver-portfolio.md)
- [Solver-Portfolio-Report-Schema v1](schemas/regelsuche-solver-portfolio-report-v1.schema.json)
- [Solver-Obligation-Schema v1](schemas/regelsuche-solver-obligation-v1.schema.json)
- [Solver-Result-Schema v1](schemas/regelsuche-solver-result-v1.schema.json)
- [Open-Target-Proof-Report-Schema v2](schemas/regelsuche-open-target-conjecture-proof-v2.schema.json)
- [Autonomous-Production-Lifecycle-Schema v3](schemas/regelsuche-autonomous-production-lifecycle-v3.schema.json)
- [Release-Readiness-Run-Schema v1](schemas/regelsuche-release-readiness-run-v1.schema.json)
- [Candidate-Qualification-Evidence-Schema v1](schemas/regelsuche-autonomous-candidate-qualification-v1.schema.json)
- [Candidate-Qualification-Run-Schema v1](schemas/regelsuche-autonomous-candidate-qualification-run-v1.schema.json)
- [Candidate-Qualification-Suite-Schema v1](schemas/regelsuche-autonomous-candidate-qualification-suite-v1.schema.json)
- [Candidate-Qualification-Split-Schema v1](schemas/regelsuche-autonomous-candidate-qualification-split-v1.schema.json)
- [Candidate-Paired-Utility-Schema v1](schemas/regelsuche-autonomous-candidate-qualified-utility-v1.schema.json)
- [Open-Target-Evaluation-Schema v1](schemas/regelsuche-open-target-conjecture-evaluation-v1.schema.json)
- [Open-Target-Promotion-Gate-Schema v1](schemas/regelsuche-open-target-promotion-gate-v1.schema.json)
- [Interessantheit, blinde Reviews und Calibration/TEST](interestingness-ranking.md)
- [Interestingness-Assessment-Schema v1](schemas/regelsuche-interestingness-assessment-v1.schema.json)
- [Review-Consensus-Schema v1](schemas/regelsuche-interestingness-review-consensus-v1.schema.json)
- [Calibration-Corpus-Schema v1](schemas/regelsuche-interestingness-calibration-corpus-v1.schema.json)
- [Autopilot: targetfreie Campaign-Planung](autopilot-planner.md)
- [Autonomous-Research-Brief-Schema v1](schemas/regelsuche-autonomous-research-brief-v1.schema.json)
- [Campaign-Budget-Ledger-Schema v1](schemas/regelsuche-campaign-budget-ledger-v1.schema.json)
- [Autonomous-Campaign-Plan-Schema v1](schemas/regelsuche-autonomous-campaign-plan-v1.schema.json)
- [Autonomous-Campaign-Execution-Schema v1](schemas/regelsuche-autonomous-campaign-execution-v1.schema.json)
- [Autonomous-Campaign-Round-Schema v1](schemas/regelsuche-autonomous-campaign-round-v1.schema.json)
- [Hypothesis Mining](hypothesis-mining.md)
- [Rule Discovery](rule-discovery.md)
- [Scientific Reproducibility](scientific-reproducibility.md)
- [Experiment Runner](experiment-runner.md)
- [Replay und Reports](replay-and-reports.md)
- [Bekannte Grenzen](limits.md)

## Architektur und Entwicklung

- [Architektur](architecture.md)
- [Modulstruktur](module-structure.md)
- [Dependency-Regeln](dependency-rules.md)
- [Developer Guide](developer-guide.md)
- [Testing](testing.md)
- [Testing-Strategie](testing-strategy.md)
- [Documentation Quality Checklist](documentation-quality-checklist.md)

## Betrieb und Integration

- [Persistenz](persistence.md)
- [Storage Architecture](storage-architecture.md)
- [Proof Bridge](proof-bridge.md)
- [Mathematical Algorithms](mathematical-algorithms.md)

Der Top-Level-[README](../README.md) bleibt der kurze Produkt- und Quickstart-Einstieg. Diese Seite ist der zentrale Dokumentationsindex; der datierte Forschungsstand wird in `discovery-status.md` gepflegt, damit README und Architekturtexte nicht mit häufig wechselnden Zwischenständen überladen werden.
