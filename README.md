# Regelsuche-Dokumentation

## Einstieg

- [Aktueller Discovery-Stand](discovery-status.md) — gemessene Ergebnisse, wissenschaftliche Grenzen und nächste Arbeitsschritte.
- [Release Readiness für Regelsuche 0.2](release-readiness.md) — getrennte Evidence Profiles, qualifizierter Autonomie-Claim sowie Gradle-/Docker-Reproduktion.
- [Capability- und Claim-Status](capability-status.md) — kontrolliertes Statusvokabular, Trust-Grenzen und lokaler Reproduktionsvertrag.
- [Generierte Capability-Matrix](generated/capability-status.md) — aus kanonischer Release-, Domain- und Plugin-Contract-Evidence erzeugter Status.
- [Solver-neutrale Obligation- und Proof-IR](solver-neutral-ir.md) — kanonische Problemverträge, strukturierte Annahmen, Backend-Capabilities und zwei unabhängige Adapter.
- [Capability-aware Solver-Portfolio](solver-portfolio.md) — rollenbasierte Backend-Auswahl, Budgets, Timeouts, Cache, Konflikte und formale Z3-Bestätigung.
- [Vergleichende Discovery-Benchmarks](discovery-benchmarks.md) — informationsgleiche, track-spezifische Baselines ohne universelles Leaderboard.
- [Generische Discovery-Domänen](generic-discovery-domains.md) — domänenneutrale Generation, Suche, Validierung und Evidence mit Algebra- und Zahlenfolgen-Adapter.
- [Domänenneutraler Lifecycle-Handoff](domain-lifecycle-handoff.md) — hashgebundene Übergabe aus generischer Discovery und unveränderter algebraischer Produktionsgeneration ohne Repräsentationsstrings.
- [Generischer Domain-Discovery-Export](domain-discovery-export.md) — manifestgebundene Persistenz von Descriptor, vollständiger Domain Evidence und repräsentationsfreiem Lifecycle-Handoff.
- [Verifikation generischer Domain-Discovery-Exporte](domain-discovery-export-verification.md) — größenbegrenzte, symlink-sichere Byte-Snapshots und fehlersicher sperrende Wurzelbindungen für persistierte Mehrdomänen-Evidence.
- [Qualification für domänengenerische Discovery](domain-generic-qualification.md) — versioniertes, fehlersicher sperrendes Evidence Profile für drei reproduzierbare Läufe über Algebra- und Zahlenfolgen-Domänen.
- [Evolution Study und Split Contracts](evolution-study-contracts.md) — preregistrierte TRAIN/VALIDATION/FINAL-TEST-Trennung, Populationspolitik und `NOT_STARTED`-Claimgrenze.
- [Deterministische TRAIN-Populationen](evolution-population-engine.md) — begrenzte parallele Fitnessauswertung, Diversität, Lineage, harte Blocker und explizite Terminalzustände ohne VALIDATION-/FINAL-TEST-Zugriff.
- [Reale TRAIN-Suchfitness](evolution-train-fitness.md) — gepaarte Produktionssuche mit kompilierten Genomen, rohen Messwerten und fehlersicher getrennten Claim-Grenzen.
- [Von Umformungen zu mathematischen Entdeckungen](from-transformations-to-discovery.md) — geschlossene Zielkette von Search über Mining und Falsifikation bis Promotion und Wiederverwendung.
- [Discovery Engine](discovery-engine.md) — technische Komponenten und Einstiegspunkte.
- [Getting Started](getting-started.md) — Docker, Gradle und der erste grafische Produktfluss.
- [Web-Workbench](web-workbench.md) — Bedienbereiche, sichtbare Ergebnisse und fachliche Zuordnung zur API.
- [Web-Workbench-Benutzerhandbuch](web-ui-user-guide.md) — vollständige Beschreibung der aktuellen Tabs und Bedienelemente.
- [User Workflows](user-workflows.md) — geführte Abläufe für Lernen, Discovery, Vergleich, Proof, Qualität und lokale Regelanalyse.
- [Demo Gallery](demo-gallery.md) — reproduzierbare UI- und Replay-Beispiele.
- [Autonomous Discovery Result Card](autonomous-discovery-walkthrough.md) — Ein-Kommando-Walkthrough mit hashgebundener JSON-/Markdown-Karte und generierten Evidence-SVGs.
- [Unabhängiges Reproduktionsartefakt](independent-reproduction.md) — deterministisches Quell-/Container-Bundle, exakte und semantische Vergleiche sowie kanonische Reproduction Receipts.

## Erweiterungen

- [Erweiterungssystem](extension-system.md) — zentrale Architekturkarte und Auswahlhilfe für Java-Plugins, Regeldateien, Knowledge Packs, deklarative und gelernte Makros, Discovery-Operatoren und mathematische Capabilities.
- [Plugins](plugins.md) — `ServiceLoader`, externe JARs, `PluginRuntime`, Reload, Katalog und Diagnosen.
- [Unveränderlicher Plugin- und Paketindex](plugin-artifact-index.md) — content-addressed Versionen, Kompatibilität, Abhängigkeiten, Provenance und deterministische Auflösungsreceipts.
- [Plugin-Artefakt-Index-Schema v1](schemas/regelsuche-plugin-artifact-index-v1.schema.json)
- [Plugin-Artefakt-Auflösungs-Schema v1](schemas/regelsuche-plugin-artifact-resolution-v1.schema.json)
- [Kryptografische Plugin-Artefaktprüfung](plugin-artifact-trust.md) — Detached Ed25519-Manifeste, Publisher-Trust-Store, Rotation, Widerruf und Vorlade-Policy.
- [Authentisierte Plugin-Trust-State-Revisionen](plugin-trust-store-revisions.md) — signierte Hashkette, lokale Checkpoints sowie Replay-, Gap- und Fork-Schutz für Publisher-Keys und Widerrufe.
- [Plugin-Signatur-Schema v1](schemas/regelsuche-plugin-signature-v1.schema.json)
- [Plugin-Trust-Store-Schema v1](schemas/regelsuche-plugin-trust-store-v1.schema.json)
- [Plugin-Trust-Store-Revision-Schema v1](schemas/regelsuche-plugin-trust-store-revision-v1.schema.json)
- [Plugin-Trust-Store-Checkpoint-Schema v1](schemas/regelsuche-plugin-trust-store-chain-checkpoint-v1.schema.json)
- [Plugin-Trust-Store-Revision-Verifikations-Schema v1](schemas/regelsuche-plugin-trust-store-revision-verification-v1.schema.json)
- [Plugin-Artefakt-Verifikations-Schema v1](schemas/regelsuche-plugin-artifact-verification-v1.schema.json)
- [Plugin-Artefakt-Gate-Schema v1](schemas/regelsuche-plugin-artifact-gate-v1.schema.json)
- [Plugin-API](plugin-api.md) — Java-Vertrag, Registries, Paketierung und Starter-Template.
- [Regeldateien](rule-files.md) — `.regelsuche`-/`.rules`-DSL, Profile, Import/Export und Debugging.
- [Makros](macros.md) — vom Autor deklarierte Makros als Suchkanten.
- [Knowledge Packs](knowledge-packs.md) — kuratierte Core-Pakete mit Provenance, Lizenz, Reviewstatus, Risiko und Validierungsbeispielen.
- [Makroregeln und emergente Identitäten](macro-rules.md) — aus Suchpfaden gelernte und erst nach Evidenz-/Promotion-Gates aktivierte Regeln.

Nicht jede interne Registry ist automatisch ein externer Plugin-JAR-Endpunkt. Solver-/Proof-Backends, Discovery-Promotion und Public Evidence behalten ihre eigenen Verträge und Gates.

## Discovery und wissenschaftliche Evidenz

- [Discovery Evidence v1](discovery-evidence-v1.md)
- [Autonomous-Discovery-Result-Card-Schema v1](schemas/regelsuche-autonomous-discovery-result-card-v1.schema.json)
- [Independent-Reproduction-Artifact-Schema v1](schemas/regelsuche-independent-reproduction-artifact-v1.schema.json)
- [Independent-Reproduction-Receipt-Schema v1](schemas/regelsuche-independent-reproduction-receipt-v1.schema.json)
- [Aktueller Open-Target-Discovery-Stand](discovery-status.md)
- [Release Readiness und Kandidatenqualifikation](release-readiness.md)
- [Solver-neutrale Obligation- und Proof-IR](solver-neutral-ir.md)
- [Capability-aware Solver-Portfolio](solver-portfolio.md)
- [Vergleichende Discovery-Benchmarks](discovery-benchmarks.md)
- [Generische Discovery-Domänen](generic-discovery-domains.md)
- [Domänenneutraler Lifecycle-Handoff](domain-lifecycle-handoff.md)
- [Generischer Domain-Discovery-Export](domain-discovery-export.md)
- [Verifikation generischer Domain-Discovery-Exporte](domain-discovery-export-verification.md)
- [Qualification für domänengenerische Discovery](domain-generic-qualification.md)
- [Evolution Study und Split Contracts](evolution-study-contracts.md)
- [Deterministische TRAIN-Populationen](evolution-population-engine.md)
- [Reale TRAIN-Suchfitness](evolution-train-fitness.md)
- [Evolution-Split-Manifest-Schema v1](schemas/regelsuche-evolution-split-manifest-v1.schema.json)
- [Evolution-Study-Plan-Schema v1](schemas/regelsuche-evolution-study-plan-v1.schema.json)
- [Evolution-Generation-Report-Schema v1](schemas/regelsuche-evolution-generation-report-v1.schema.json)
- [Evolution-Population-Run-Schema v1](schemas/regelsuche-evolution-population-run-v1.schema.json)
- [Evolution-TRAIN-Search-Suite-Schema v1](schemas/regelsuche-evolution-train-search-suite-v1.schema.json)
- [Evolution-TRAIN-Fitness-Schema v1](schemas/regelsuche-evolution-train-fitness-v1.schema.json)
- [Discovery-Domain-Descriptor-Schema v1](schemas/regelsuche-discovery-domain-descriptor-v1.schema.json)
- [Domain-Discovery-Evidence-Schema v1](schemas/regelsuche-domain-discovery-evidence-v1.schema.json)
- [Discovery-Lifecycle-Handoff-Schema v1](schemas/regelsuche-discovery-lifecycle-handoff-v1.schema.json)
- [Domain-Discovery-Export-Schema v1](schemas/regelsuche-domain-discovery-export-v1.schema.json)
- [Domain-Discovery-Export-Verifikations-Schema v1](schemas/regelsuche-domain-discovery-export-verification-v1.schema.json)
- [Domain-Generic-Profile-Katalog-Schema v1](schemas/regelsuche-domain-generic-evidence-profile-catalog-v1.schema.json)
- [Domain-Generic-Qualification-Schema v1](schemas/regelsuche-domain-generic-discovery-qualification-v1.schema.json)
- [Domain-Generic-Qualification-Run-Schema v1](schemas/regelsuche-domain-generic-discovery-qualification-run-v1.schema.json)
- [Capability-Status-Schema v1](schemas/regelsuche-capability-status-v1.schema.json)
- [Comparative-Benchmark-Schema v1](schemas/regelsuche-comparative-benchmark-v1.schema.json)
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
- [Autonomous-Research-Brief-Schema v2](schemas/regelsuche-autonomous-research-brief-v2.schema.json)
- [Autonomous-Stage-Resource-Ledger-Schema v2](schemas/regelsuche-autonomous-stage-resource-ledger-v2.schema.json)
- [Autonomous-Campaign-Plan-Schema v2](schemas/regelsuche-autonomous-campaign-plan-v2.schema.json)
- [Autonomous-Campaign-Execution-Schema v2](schemas/regelsuche-autonomous-campaign-execution-v2.schema.json)
- [Autonomous-Campaign-Round-Schema v2](schemas/regelsuche-autonomous-campaign-round-v2.schema.json)
- [Hypothesis Mining](hypothesis-mining.md)
- [Rule Discovery](rule-discovery.md)
- [Scientific Reproducibility](scientific-reproducibility.md)
- [Experiment Runner](experiment-runner.md)
- [Replay und Reports](replay-and-reports.md)
- [Bekannte Grenzen](limits.md)

## Architektur und Entwicklung

- [Architektur](architecture.md)
- [AST-Regelradar](ast-rule-radar.md) — implementierte positionsgebundene Grund-, Erweiterungs- und Makroregel-Kandidaten, Subtree-Rewrite und korrelierter Suchgraph.
- [AST-Regelradar-Schema v1](schemas/regelsuche-ast-rule-radar-v1.schema.json) und [Search-Schema v1](schemas/regelsuche-ast-rule-radar-search-v1.schema.json).
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
