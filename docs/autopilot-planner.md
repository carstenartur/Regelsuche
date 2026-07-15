# Autopilot: targetfreie Discovery-Campaigns

Der Autopilot verteilt begrenzte Ressourcen auf bestehende Discovery- und Evidenzstufen. Seine Entscheidungen sind **keine mathematische Evidenz** und ersetzen weder Falsifikation noch Projekt-Novelty, Proof, Promotion oder Public Evidence.

## Ein unterstützter Vertrag

Regelsuche unterstützt nur noch die aktuelle Autopilot-Architektur. Die früheren branch-lokalen v1-Verträge, ihre eingefrorenen Hashes, Schemas und Ausführungsadapter wurden entfernt. Historische Läufe bleiben über den jeweiligen Git-Commit nachvollziehbar; sie begrenzen die aktuelle Architektur nicht mehr.

Der aktuelle Research Brief ist:

```text
regelsuche.autonomous-research-brief/v2
```

Er besitzt bewusst keinen Vorgänger-Hash, keinen Zielausdruck und keine versteckte erwartete Antwort. Vor dem Lauf friert er ein:

- erlaubte mathematische Domänen,
- Seed-Generatoren,
- Inventar-, Pack- und Modellhashes,
- einen deterministischen Seed,
- Mindestwerte für Aggregate Inputs, Familien- und Evidenzdiversität,
- den Output-Namespace,
- Budgets für jede explizite Evidenzstufe.

## Evidenzstufen

Der Brief trennt die fachlichen Stufen:

- `GENERATION`,
- `CANDIDATE_FORMATION`,
- `VALIDATION`,
- `COUNTEREXAMPLE_SEARCH`,
- `PROJECT_NOVELTY`,
- `PROOF`,
- `LIFECYCLE_HANDOFF`.

Jede Stufe benötigt mindestens eine konfigurierte Nicht-Zeit-Ressource. Projekt-Novelty wird damit nicht in Validation versteckt, und Lifecycle-Handoff wird weder mit Proof noch mit Promotion gleichgesetzt.

## Aggregate Fan-in und Fan-out

`regelsuche.autonomous-aggregate-decision/v2` modelliert die tatsächliche Eingangstopologie des Open-Target-Miners:

- Scope `AGGREGATE`,
- mehrere unveränderliche Observation Branches,
- ausschließlich `GoalStatus.UNTARGETED`,
- Mindestzahl der Eingänge,
- Mindestdiversität der Familien und Evidence-Hashes,
- Output-Namespace statt vorab erfundener Kandidaten-IDs,
- geplante `MINING_BATCHES`- und `CANDIDATES`-Kapazität.

Die Reihenfolge der Inputs beeinflusst weder Decision-Hash noch kanonisches JSON.

`regelsuche.autonomous-aggregate-receipt/v2` erlaubt einen Mining-Batch mit null, einem oder mehreren Kandidaten. Ein Kandidatenbranch entsteht erst, wenn der produktive Miner eine konkrete Conjecture-ID erzeugt hat. Abgewiesene Cluster bleiben explizite Evidence, erzeugen aber keinen erfolgreichen Output-Branch.

Jeder Output bindet nur seine tatsächlich unterstützenden Beobachtungen:

- Observation-ID,
- Source-Branch-ID,
- Familien-ID,
- Snapshot-Hash,
- Evidence-Hash,
- Hash des unveränderlichen Observation Branches.

`INCONCLUSIVE` und `BACKEND_UNAVAILABLE` erzeugen keine Kandidatenoutputs. Nicht ausgeführte Ressourcen werden als übersprungen bilanziert und nicht als ausgeführte Arbeit erfunden.

## Produktive Open-Target-Bindung

`OpenTargetAutopilotV2Binding` verbindet die Aggregate-Verträge direkt mit dem vorhandenen `OpenTargetConjectureEvidence`:

- der kanonische Mining-Evidence-Hash wird unverändert gebunden,
- Campaign-ID und Rule-Inventory-Hash werden gegen den Brief geprüft,
- die Observation-IDs müssen exakt den Seed-Provenienzen entsprechen,
- nur Conjecture-IDs des produktiven Mining-Reports können Output-Branches erzeugen,
- Kandidatenlinien enthalten ausschließlich den tatsächlich tragenden Support.

Die Binding-Schicht implementiert keinen zweiten Miner.

## Novelty, Proof und Lifecycle

`regelsuche.autonomous-candidate-lifecycle/v2` bildet die bestehenden fachlichen Reports ohne Bedeutungsvermischung ab:

- `EXACT_DUPLICATE` und `ALPHA_EQUIVALENT_DUPLICATE` enden als `DUPLICATE`,
- `INCONCLUSIVE_UNPARSEABLE` bleibt `INCOMPLETE`,
- Proof `REFUTED` endet als `DISPROVED`,
- Proof `INCONCLUSIVE` oder `NOT_RUN` bleibt `INCOMPLETE`,
- `SYMBOLICALLY_VERIFIED` ohne Lifecycle-Handoff bleibt `INCOMPLETE`,
- erst der konservative `OpenTargetHypothesisCandidateAdapter`-Handoff schließt die interne Stufenkette als `COMPLETED` ab.

Der Handoff versucht weder Promotion noch Veröffentlichung.

## Versionierte Campaign-Artefakte

Die Aggregate-Objekte werden von aktuellen äußeren Verträgen referenziert, nicht kopiert:

- `regelsuche.autonomous-campaign-plan/v2`,
- `regelsuche.autonomous-campaign-execution/v2`,
- `regelsuche.autonomous-branch-lineage/v2`,
- `regelsuche.autonomous-campaign-round/v2`,
- `regelsuche.autonomous-evidence-dag/v2`.

Die DAG verbindet:

```text
Research Brief
→ unveränderliche Observation Branches
→ Aggregate Decisions
→ Aggregate Receipts
→ zero-to-many Candidate Branches mit Lineage
→ getrennte Novelty-, Proof- und Lifecycle-Ausgänge
→ nächster Campaign-Plan
```

Ein Receipt ohne retained Decision oder eine Decision ohne retained Observation Inputs wird abgelehnt.

## Produktive Generation für Issue #348

`PinnedAutonomousProductionCampaign` definiert den ersten tatsächlich ausgeführten Teil der produktiven Mehr-Runden-Campaign. Der Brief enthält kein Target und keine erwartete Antwort. Er bindet das aktive Regel-Inventar der `AstRewriteTransformationEngine`, die Best-First-Suche, `ExpressionScorer`, `ExpressionCanonicalizer` und die Suchheuristik.

Zwei unabhängig bezeichnete Generatorfamilien liefern zwölf deterministische Polynomial-Seeds:

```text
factor-common-left-generator/v1
factor-common-right-generator/v1
```

`AutonomousProductionGenerationRunner` führt jeden Seed über die vorhandenen Produktionskomponenten aus:

```text
DeterministicDiscoveryExperimentRunner
→ targetfreies SearchProblem
→ AstRewriteTransformationEngine
→ BestFirstSearchStrategy
→ GoalStatus.UNTARGETED
→ unveränderlicher Observation Branch
```

Jede Observation hält Seed, Generator, alle erkundeten Zustände, gewichtete Scores, Regelpfade, Äquivalenzflags, Annahmen, Suchmetriken sowie getrennte Snapshot-, Evidence- und Branch-Hashes fest. Diese Daten reichen für die reproduzierbare Candidate Formation aus, ohne auf nur im ursprünglichen JVM-Lauf vorhandene `SearchState`-Objekte angewiesen zu sein.

Der reproduzierbare Lauf lautet:

```bash
./gradlew :regelsuche-autopilot:runProductionGeneration
```

Die Artefakte liegen anschließend unter:

```text
regelsuche-autopilot/build/reports/autopilot-production-generation/
```

## Modulgrenze

Die generischen Verträge für Brief, unveränderliche Observation Branches, Aggregate Decisions, Receipts und Evidence DAG liegen in `:regelsuche-experiments`. Das Modul bleibt unabhängig von konkreten Mining-, Novelty-, Proof- und Lifecycle-Typen.

`:regelsuche-autopilot` verbindet diese Verträge mit `:regelsuche-learning`. Eine Architekturprüfung verhindert die Rückabhängigkeit `:regelsuche-experiments → :regelsuche-learning`.

## CI-Evidence

Der Workflow `Autopilot Evidence` erzeugt und archiviert ausschließlich aktuelle Artefakte:

- aus `:regelsuche-experiments`: `brief-v2.json`, `aggregate-decision.json`, `aggregate-receipt.json` und `dag.json`,
- aus `:regelsuche-autopilot`: `production-binding.json`, `lifecycle-decision.json`, `plan-v2.json`, `execution-v2.json`, `lineage-v2.json`, `round-v2.json` und `next-plan-v2.json`,
- aus der produktiven Generation: Brief, Seed-Katalog, Observations, Generation Receipt, Discovery Report und Run Manifest.

Promotion und Public Evidence bleiben in diesen Planungs- und Ausführungsartefakten `NOT_EVALUATED`.
