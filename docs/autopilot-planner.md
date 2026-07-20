# Autopilot: targetfreie Discovery-Campaigns

Der Autopilot verteilt begrenzte Ressourcen auf vorhandene Discovery- und
Evidenzstufen. Seine Entscheidungen sind **keine mathematische Evidenz**. Sie
ersetzen weder Falsifikation noch Project Novelty, solvergestützte Bestätigung,
formalen Proof, Promotion oder Public Evidence.

## Unterstützter Vertrag

Regelsuche unterstützt die aktuelle Autopilot-Architektur ohne parallele
Kompatibilitätsschicht für frühere interne Schemas. Historische Läufe bleiben
über ihren jeweiligen Git-Commit reproduzierbar, bestimmen aber nicht den
aktuellen Produktionsvertrag.

Der Research Brief verwendet:

```text
regelsuche.autonomous-research-brief/v2
```

Er besitzt keinen Zielausdruck und keine versteckte erwartete Antwort. Vor dem
Lauf friert er insbesondere ein:

- erlaubte mathematische Domänen und Seed-Generatoren;
- Inventar-, Pack- und Modellidentitäten;
- einen deterministischen Seed;
- Mindestwerte für Input-, Familien- und Evidenzdiversität;
- den Output-Namespace;
- getrennte Budgets für jede Evidenzstufe.

## Fachliche Stufen

Der Brief trennt:

```text
GENERATION
CANDIDATE_FORMATION
VALIDATION
COUNTEREXAMPLE_SEARCH
PROJECT_NOVELTY
PROOF
LIFECYCLE_HANDOFF
```

Jede Stufe benötigt eine konfigurierte Nicht-Zeit-Ressource. Project Novelty
wird nicht in Validation versteckt. Lifecycle Handoff wird weder mit Proof noch
mit Promotion gleichgesetzt.

## Aggregate Candidate Formation

`regelsuche.autonomous-aggregate-decision/v2` beschreibt den tatsächlichen
Fan-in des Open-Target-Miners:

- Scope `AGGREGATE`;
- mehrere unveränderliche Observation Branches;
- ausschließlich `GoalStatus.UNTARGETED`;
- Mindestzahlen für Inputs, Familien und Evidence-Hashes;
- einen Output-Namespace statt vorab erfundener Kandidatenidentitäten;
- begrenzte Kapazitäten für Mining-Batches und Kandidaten.

Die Eingangsreihenfolge beeinflusst weder Decision-Hash noch kanonisches JSON.

`regelsuche.autonomous-aggregate-receipt/v2` erlaubt null, einen oder mehrere
Kandidaten. Ein Kandidatenbranch entsteht erst, wenn der produktive Miner eine
konkrete Conjecture-ID erzeugt hat. Abgewiesene Cluster bleiben sichtbare
Evidence, erzeugen aber keinen erfolgreichen Output-Branch.

Jeder Output bindet nur die tatsächlich tragenden Beobachtungen mit
Observation-, Branch-, Familien-, Snapshot- und Evidence-Identitäten.
`INCONCLUSIVE` und `BACKEND_UNAVAILABLE` erzeugen keine Kandidatenoutputs.
Nicht ausgeführte Ressourcen werden als übersprungen und nicht als ausgeführt
bilanziert.

## Produktive Open-Target-Bindung

`OpenTargetAutopilotV2Binding` verbindet die Aggregate-Verträge mit der
vorhandenen `OpenTargetConjectureEvidence`:

- der Mining-Evidence-Hash wird unverändert gebunden;
- Campaign- und Rule-Inventory-Identitäten werden gegen den Brief geprüft;
- Observation-IDs müssen exakt den Seed-Provenienzen entsprechen;
- nur Conjecture-IDs des produktiven Mining-Reports können Outputs erzeugen;
- Kandidaten-Lineage enthält ausschließlich den tatsächlich tragenden Support.

Die Binding-Schicht implementiert keinen zweiten Miner.

## Novelty, Solver und Lifecycle

`regelsuche.autonomous-candidate-lifecycle/v2` erhält die Bedeutungen der
bestehenden Reports:

- exakte und alpha-äquivalente Duplikate enden als `DUPLICATE`;
- unparsebare oder unvollständige Evidence bleibt `INCOMPLETE`;
- ein widerlegtes Solver-/Proof-Ergebnis endet als `DISPROVED`;
- nicht ausgeführte oder nicht unterstützte Bestätigung bleibt `INCOMPLETE`;
- erst der konservative `OpenTargetHypothesisCandidateAdapter`-Handoff schließt
  die interne Stufenkette als `COMPLETED` ab.

Der Handoff versucht weder Promotion noch Veröffentlichung.

Die aktuelle DAG verbindet:

```text
Research Brief
→ unveränderliche Observation Branches
→ Aggregate Decisions
→ Aggregate Receipts
→ zero-to-many Candidate Branches mit Lineage
→ getrennte Validation-, Novelty-, Solver- und Lifecycle-Ausgänge
→ nächster Campaign-Plan
```

Ein Receipt ohne retained Decision oder eine Decision ohne retained Inputs wird
abgelehnt.

## Produktive Generation

`PinnedAutonomousProductionCampaign` definiert die ausgeführte, targetfreie
Produktionskampagne. Zwei unabhängig parametrisierte Generatorfamilien liefern
zwölf deterministische Polynomial-Seeds:

```text
factor-common-gap-two-generator/v1
factor-common-twin-prime-generator/v1
```

Beide Familien stellen die beobachtbare Relation `B = A + 2` bereit, beziehen
ihre Parameter jedoch aus unterschiedlichen Quellen. Der Miner kann dadurch
eine gemeinsame Regel abstrahieren, ohne die Familienbezeichnung für die
Clusterbildung zu verwenden.

`AutonomousProductionGenerationRunner` verwendet die vorhandenen
Produktionskomponenten:

```text
DeterministicDiscoveryExperimentRunner
→ targetfreies SearchProblem
→ AstRewriteTransformationEngine
→ BestFirstSearchStrategy
→ GoalStatus.UNTARGETED
→ unveränderlicher Observation Branch
```

Jede Observation hält Seed, Generator, erkundete Zustände, gewichtete Scores,
Regelpfade, Äquivalenzflags, Annahmen, Suchmetriken und getrennte Snapshot-,
Evidence- und Branch-Hashes fest.

Fokussierter Lauf:

```bash
./gradlew :regelsuche-autopilot:runProductionGeneration
```

## Produktives Mining

`AutonomousProductionMiningRunner` setzt persistierte Generationsergebnisse in
den vorhandenen `OpenTargetConjectureMiner` ein. Die benötigten
`SearchState`-Felder werden aus den retained `StateSnapshot`s rekonstruiert. Der
gleiche Batch wird zusätzlich aus den live erzeugten Suchresultaten ausgewertet;
abweichende Reports brechen den Lauf ab.

Die Kampagne führt zwei vorab definierte Aggregate Decisions aus:

1. Ein vollständiger Batch mit zwölf `UNTARGETED` Observations aus beiden
   Familien muss mindestens einen parameterisierten Kandidaten mit
   Zwei-Familien-Lineage erzeugen.
2. Ein Rejection-Batch mit nur alpha-umbenanntem Support muss null Kandidaten und
   den Reject-Grund `alpha-distinct-support<2` festhalten.

Generation und Mining:

```bash
./gradlew :regelsuche-autopilot:runProductionMining
```

## Validation bis Lifecycle Handoff

`AutonomousProductionLifecycleRunner` übernimmt ausschließlich den bereits
gebildeten Kandidatenbranch. Holdout-, Counterexample-, Novelty- oder
Solver-Information fließt nicht in Generation oder Mining zurück.

Die Downstream-Kette verwendet:

```text
OpenTargetConjectureEvaluator
→ DeterministicCounterexampleSearchService
→ OpenTargetConjectureNoveltyChecker
→ SolverIr.Obligation
→ SolverBackend
→ SolverIr.SolverResult
→ OpenTargetHypothesisCandidateAdapter
→ AutonomousCandidateLifecycleV2
```

Die fest gepinnte Validation-Suite enthält drei positive und drei negative
Holdouts mit zuvor nicht verwendeten Koeffizienten. Counterexample Search bleibt
eine eigene Stufe und verwendet deterministische Randwert-, rationale,
pseudozufällige numerische und komplexe Zuweisungen.

Project Novelty wird separat gegen das aktive `KnownRuleRepository` geprüft.
`NOVEL_WITHIN_PROJECT` ist keine externe mathematische Neuheit;
`externalNoveltyStatus` bleibt `NOT_EVALUATED`.

Eine solverneutrale Obligation darf erst nach akzeptierter Validation entstehen.
`SYMBOLICALLY_VERIFIED` verlangt ein hashgebundenes `CONFIRMED`-Resultat mit
`LOSSLESS`-Übersetzung. Nicht unterstützte Konstrukte oder Annahmen blockieren
den Handoff. Der separate Formal-Proof-Status bleibt `NOT_EVALUATED`.

Vollständiger Lifecycle-Lauf:

```bash
./gradlew :regelsuche-autopilot:runProductionLifecycle
```

## Vollständige Produktionskampagne

Der kanonische Kampagnenlauf lautet:

```bash
./gradlew :regelsuche-autopilot:runProductionCampaign
```

Er schreibt den vollständigen Artefaktbaum nach:

```text
regelsuche-autopilot/build/reports/autopilot-production-campaign/
```

Dazu gehören Research Brief, Seed-Katalog, Observations, Generation Receipt,
Mining- und Binding-Evidence, Lineages, Validation, Counterexample Search,
Project Novelty, `solver-obligation.json`, `solver-result.json`,
`proof-report.json`, Lifecycle Candidate und Decision, Ressourcenledger,
Campaign Round, Feedback und `production-campaign-manifest.json`.

Das frühere `proof-obligation.json` ist nicht Bestandteil des aktuellen
Vertrags.

## Checkout-lokale Verifikation

Die vollständige Pass/Fail-Semantik gehört Gradle, JUnit, Testcontainers und den
geprüften Repository-Skripten.

### Java- und Evidence-Verträge

```bash
./gradlew :regelsuche-autopilot:test
./gradlew :regelsuche-autopilot:verifyAutopilotProductionCampaign
```

`verifyAutopilotProductionCampaign` erzeugt die komplette Kampagne und ruft
`scripts/verify-autopilot-production-campaign.py` in der gepinnten Python-
Umgebung auf. Der Verifikator verlangt den vollständigen 37-Dateien-Baum, prüft
eindeutiges JSON und validiert Solver Obligation, Solver Result, Proof Report,
Lifecycle v3 und Campaign v2 gegen ihre Draft-2020-12-Schemas.

Jede retained Datei erhält genau die Identität, die auch der Produktionscode
verwendet:

- `contentHash`, wenn das kanonische Artefakt diesen trägt;
- `evidenceHash` für den Proof Report;
- SHA-256 der exakten deterministischen JSON-Bytes für reine Reports ohne
  eingebetteten semantischen Hash.

Das Campaign Manifest muss exakt alle 36 erwarteten Artefakttypen enthalten.
Fehlende, zusätzliche, doppelte oder gegen ein anderes retained Artefakt
ausgetauschte Roots werden abgewiesen. Zusätzlich werden sämtliche
Top-Level-Bindungen des Campaign Manifests und die vollständige Lifecycle-Kette
gegen die tatsächlich retained Dateien geprüft:

```text
campaign.briefHash                  = brief-v2 identity
campaign.generationRunHash          = generation-run identity
campaign.miningRunHash              = production-mining-run identity
campaign.lifecycleRunHash           = production-lifecycle-run identity
campaign.initialPlanHash            = plan-v2 identity
campaign.nextPlanHash               = next-plan-v2 identity
campaign.campaignRoundHash          = campaign-round-v2 identity
campaign.feedbackReallocationHash   = feedback-reallocation identity
campaign.campaignResourceLedgerHash = campaign-resource-ledger identity

lifecycle.validationHash            = validation-report byte hash
lifecycle.counterexampleHash        = counterexample-report byte hash
lifecycle.projectNoveltyHash        = project-novelty-report byte hash
lifecycle.proofEvidenceHash          = proof-report evidenceHash
lifecycle.solverObligationHash       = solver-obligation contentHash
lifecycle.solverResultHash           = solver-result contentHash
lifecycle.lifecycleCandidateHash     = lifecycle-candidate byte hash
lifecycle.lifecycleDecisionHash      = lifecycle-decision contentHash
lifecycle.stageResourceLedgerHash    = stage-resource-ledger contentHash
```

Die Solver-/Proof-Verkettung bleibt zusätzlich explizit:

```text
solver-result.obligationHash = solver-obligation.contentHash
proof.solverObligationHash   = solver-obligation.contentHash
proof.solverResultHash       = solver-result.contentHash
lifecycle.solver*Hash        = jeweiliger Solver-Hash
```

Der Verifikator verlangt außerdem `CONFIRMED`, `LOSSLESS` und die unveränderten
wissenschaftlichen Grenzen:

```text
targetProvided=false
campaignCompletionIsMathematicalEvidence=false
externalNoveltyEvaluated=false
promotionStatus=NOT_EVALUATED
publicEvidenceStatus=NOT_EVALUATED
```

### Docker-Reproduktion

```bash
./gradlew :regelsuche-autopilot:dockerIntegrationTest
```

`AutonomousProductionCampaignContainerTest` baut `Dockerfile.autopilot` über
Testcontainers aus einem stabilen Snapshot ausschließlich Git-getrackter
Checkout-Dateien. Mutable `.gradle`- und Build-Caches gelangen nicht in den
Docker-Kontext. Der Test führt die Runtime-Image-Kampagne einmalig aus und
vergleicht den gesamten lokalen und containerisierten Evidence-Baum bytegenau.
Bind-Mount-Berechtigungen sind für rootless Docker und User-Namespace-Remapping
explizit behandelt.

Beide Verifikationsschichten sind Teil des normalen Root-Lebenszyklus:

```bash
./gradlew test
./gradlew check
./gradlew fullCheck
```

Ein eigener `Autopilot Evidence`-Workflow ist nicht erforderlich. Central CI
ruft dieselben Gradle-Verträge auf und archiviert deren Reports. GitHub Actions
definiert keine Fixtures, erwarteten Werte, Schema-Assertions oder Docker-
Kommandos mehr.

## Modulgrenze

Die generischen Verträge für Brief, Observation Branches, Aggregate Decisions,
Receipts und Evidence DAG liegen in `:regelsuche-experiments`.
`:regelsuche-autopilot` verbindet sie mit `:regelsuche-learning`, der
solverneutralen IR und den produktiven Discovery-Komponenten. Eine
Architekturprüfung verhindert die Rückabhängigkeit
`:regelsuche-experiments → :regelsuche-learning`.
