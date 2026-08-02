# Schema-Katalog

Regelsuche verwendet versionierte JSON-Schemas für Evidence, Manifeste,
Reproduktionsreceipts und externe Integrationsverträge. Diese Seite bietet einen
fachlich gruppierten Einstieg; der vollständige Bestand liegt unter
[`docs/schemas/`](schemas/).

## Verwendung

- Ein Schema beschreibt die zulässige Struktur eines Artefakts.
- Die jeweilige Laufzeitimplementierung und der unabhängige Verifier prüfen
  zusätzlich semantische Beziehungen, Hashbindungen und Ressourcenbilanzen.
- Ein erfolgreiches Schema-Parsing allein autorisiert keinen mathematischen
  oder wissenschaftlichen Claim.
- Inkompatible Vertragsänderungen erhalten eine neue Schema-ID oder Version;
  vorhandene Evidence wird nicht stillschweigend umgedeutet.

## Capability- und Release-Status

| Vertrag | Zweck |
| --- | --- |
| [`regelsuche-capability-status-v1`](schemas/regelsuche-capability-status-v1.schema.json) | Öffentliche Capability- und Claim-Matrix mit Evidence-Roots und Blockern |
| [`regelsuche-release-readiness-run-v1`](schemas/regelsuche-release-readiness-run-v1.schema.json) | Laufübergreifende Release-Readiness-Evidence |
| [`regelsuche-autonomous-candidate-qualification-v1`](schemas/regelsuche-autonomous-candidate-qualification-v1.schema.json) | Qualifikation eines retained autonomen Kandidaten |
| [`regelsuche-autonomous-candidate-qualification-run-v1`](schemas/regelsuche-autonomous-candidate-qualification-run-v1.schema.json) | Gebundener Qualifikationslauf |
| [`regelsuche-autonomous-candidate-qualified-utility-v1`](schemas/regelsuche-autonomous-candidate-qualified-utility-v1.schema.json) | Gepaarter Held-out-Nutzen mit und ohne Kandidat |

## Discovery-Domänen und Lifecycle

| Vertrag | Zweck |
| --- | --- |
| [`regelsuche-discovery-domain-descriptor-v1`](schemas/regelsuche-discovery-domain-descriptor-v1.schema.json) | Versionierte Beschreibung einer Discovery-Domäne |
| [`regelsuche-domain-discovery-evidence-v1`](schemas/regelsuche-domain-discovery-evidence-v1.schema.json) | Vollständige domänenspezifische Discovery-Evidence |
| [`regelsuche-discovery-lifecycle-handoff-v1`](schemas/regelsuche-discovery-lifecycle-handoff-v1.schema.json) | Repräsentationsfreie Übergabe in nachgelagerte Stufen |
| [`regelsuche-domain-discovery-export-v1`](schemas/regelsuche-domain-discovery-export-v1.schema.json) | Manifestgebundener Domain-Export |
| [`regelsuche-domain-discovery-export-verification-v1`](schemas/regelsuche-domain-discovery-export-verification-v1.schema.json) | Verifikation persistierter Exportbytes und Wurzelbindungen |
| [`regelsuche-domain-generic-discovery-qualification-v1`](schemas/regelsuche-domain-generic-discovery-qualification-v1.schema.json) | Qualifikation über mehrere mathematische Objekttypen |

## Evolution und Flagship-Experiment

| Vertrag | Zweck |
| --- | --- |
| [`regelsuche-evolution-split-manifest-v1`](schemas/regelsuche-evolution-split-manifest-v1.schema.json) | TRAIN-/VALIDATION-/FINAL-TEST-Trennung |
| [`regelsuche-evolution-study-plan-v1`](schemas/regelsuche-evolution-study-plan-v1.schema.json) | Eingefrorene Populations-, Mutations- und Ressourcenpolitik |
| [`regelsuche-evolution-train-search-suite-v1`](schemas/regelsuche-evolution-train-search-suite-v1.schema.json) | Reale gepaarte TRAIN-Suchsuite |
| [`regelsuche-evolution-train-fitness-v1`](schemas/regelsuche-evolution-train-fitness-v1.schema.json) | Rohwerte und Blocker der TRAIN-Fitness |
| [`regelsuche-evolution-generation-report-v1`](schemas/regelsuche-evolution-generation-report-v1.schema.json) | Generation, Lineage, Diversität und Rejects |
| [`regelsuche-evolution-population-run-v1`](schemas/regelsuche-evolution-population-run-v1.schema.json) | Vollständiger Populationslauf und Terminalstatus |

Weitere Flagship-Verträge für Freeze, Reveal, Baselines und Work Accounting
liegen ebenfalls im Schema-Verzeichnis und sind in
[Flagship Freeze Execution](evolution-rewrite-program-flagship-freeze-execution.md)
beschrieben.

## Benchmarks, Kosten und Reproduktion

| Vertrag | Zweck |
| --- | --- |
| [`regelsuche-comparative-benchmark-v1`](schemas/regelsuche-comparative-benchmark-v1.schema.json) | Track-spezifische Vergleiche unter Informationsparität |
| [`regelsuche-candidate-independent-benchmark-run-v1`](schemas/regelsuche-candidate-independent-benchmark-run-v1.schema.json) | Kandidatenunabhängiger Benchmarklauf |
| [`regelsuche-discovery-cost-ledger-v1`](schemas/regelsuche-discovery-cost-ledger-v1.schema.json) | Vollständige Discovery- und Qualifikationskosten |
| [`regelsuche-amortization-report-v1`](schemas/regelsuche-amortization-report-v1.schema.json) | Amortisations- und Break-even-Auswertung |
| [`regelsuche-autonomous-discovery-result-card-v1`](schemas/regelsuche-autonomous-discovery-result-card-v1.schema.json) | Evidence-abgeleitete Ergebnisdarstellung |
| [`regelsuche-independent-reproduction-artifact-v1`](schemas/regelsuche-independent-reproduction-artifact-v1.schema.json) | Inhalt und Identität des Reproduktionspakets |
| [`regelsuche-independent-reproduction-receipt-v1`](schemas/regelsuche-independent-reproduction-receipt-v1.schema.json) | Ergebnis eines Reproduktionslaufs |

## Solver und Proof

| Vertrag | Zweck |
| --- | --- |
| [`regelsuche-solver-obligation-v1`](schemas/regelsuche-solver-obligation-v1.schema.json) | Solver-neutrale mathematische Obligation |
| [`regelsuche-solver-result-v1`](schemas/regelsuche-solver-result-v1.schema.json) | Strukturiertes Backend-Ergebnis |
| [`regelsuche-solver-portfolio-report-v1`](schemas/regelsuche-solver-portfolio-report-v1.schema.json) | Auswahl, Ausführung und Konflikte eines Solver-Portfolios |
| [`regelsuche-open-target-conjecture-proof-v2`](schemas/regelsuche-open-target-conjecture-proof-v2.schema.json) | Proof-Evidence für Open-Target-Kandidaten |

## Plugins, Artefakte und Trust State

| Vertrag | Zweck |
| --- | --- |
| [`regelsuche-plugin-artifact-index-v1`](schemas/regelsuche-plugin-artifact-index-v1.schema.json) | Content-addressed Plugin- und Paketindex |
| [`regelsuche-plugin-artifact-resolution-v1`](schemas/regelsuche-plugin-artifact-resolution-v1.schema.json) | Deterministischer Auflösungsplan |
| [`regelsuche-plugin-signature-v1`](schemas/regelsuche-plugin-signature-v1.schema.json) | Detached Signaturmanifest |
| [`regelsuche-plugin-trust-store-v1`](schemas/regelsuche-plugin-trust-store-v1.schema.json) | Publisher-Keys und Widerrufe |
| [`regelsuche-plugin-trust-store-revision-v1`](schemas/regelsuche-plugin-trust-store-revision-v1.schema.json) | Signierte monotone Trust-State-Revision |
| [`regelsuche-plugin-trust-store-chain-checkpoint-v1`](schemas/regelsuche-plugin-trust-store-chain-checkpoint-v1.schema.json) | Lokaler Replay- und Fork-Schutz |
| [`regelsuche-plugin-artifact-verification-v1`](schemas/regelsuche-plugin-artifact-verification-v1.schema.json) | Artefakt- und Signaturprüfung |
| [`regelsuche-plugin-artifact-gate-v1`](schemas/regelsuche-plugin-artifact-gate-v1.schema.json) | Fail-closed Ladeentscheidung |

## UI- und Diagnoseverträge

| Vertrag | Zweck |
| --- | --- |
| [`regelsuche-ast-rule-radar-v1`](schemas/regelsuche-ast-rule-radar-v1.schema.json) | AST-Positionen und lokal anwendbare Regeln |
| [`regelsuche-ast-rule-radar-search-v1`](schemas/regelsuche-ast-rule-radar-search-v1.schema.json) | Korrelation mit dem globalen Suchlauf |

HTTP-Verträge sind nicht Teil dieses Katalogs. Die verbindliche REST-Referenz
ist die mit der Anwendung ausgelieferte OpenAPI-3.1-Spezifikation.

## Pflege

Neue Schemas benötigen:

1. eine stabile `$id` und eindeutige Version;
2. strikte Behandlung unbekannter und doppelter Felder, soweit der Vertrag dies
   vorsieht;
3. positive und negative Charakterisierungstests;
4. einen unabhängigen Verifier für semantische Beziehungen;
5. eine fachliche Dokumentationsseite oder einen Eintrag in diesem Katalog;
6. eine explizite Migrations- oder Inkompatibilitätsentscheidung.
