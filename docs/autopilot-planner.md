# Autopilot: targetfreie Campaign-Planung

Der Autopilot verteilt Ressourcen auf bestehende Discovery- und Evidenzstufen. Seine Entscheidungen sind **keine** mathematische Evidenz und ersetzen weder Falsifikation noch Novelty, Proof, Promotion oder Public Evidence.

## Research Brief v1

`regelsuche.autonomous-research-brief/v1` friert vor dem Lauf ein:

- erlaubte mathematische Domänen,
- Seed-Generatoren,
- strukturelle Grenzen,
- Inventar-, Pack- und Modellhashes,
- einen deterministischen Seed,
- aktivierte Evidenzstufen,
- erlaubte Capability-Namen,
- Mindestwerte für Familien- und Supportdiversität,
- die Allokationspolicy,
- Stufenbudgets.

Der Vertrag besitzt bewusst kein Feld für einen Zielausdruck oder eine versteckte erwartete Antwort.

## Budget Ledger v1

`regelsuche.campaign-budget-ledger/v1` hält pro Evidenzstufe und Ressource getrennt fest:

- `configured`,
- `executed`,
- `skipped`,
- `remaining`.

Für jede Zeile gilt:

```text
configured = executed + skipped + remaining
```

Ein Update darf die Restmenge nicht überschreiten. Ein Ledger ist über den Brief-Hash an genau einen Research Brief gebunden.

## Deterministischer Allokationsplan v1

`regelsuche.autonomous-campaign-plan/v1` erzeugt aus Brief, Ledger und Branch-Snapshots eine stabile Reihenfolge von Entscheidungen:

- `ALLOCATE`,
- `STOP`,
- `NO_ACTION`.

Unterstützte v1-Policies sind:

- `ROUND_ROBIN`,
- `EVIDENCE_COMPLETION_FIRST`,
- `COUNTEREXAMPLE_RISK_FIRST`,
- `BALANCED`.

Duplikate, widerlegte, unsichere oder dauerhaft schwache Branches erhalten keine zusätzliche teure Arbeit. Nicht widerlegte Branches mit fehlender Pflichtevidenz können Validierungs-, Gegenbeispiel- oder Proof-Budget erhalten, auch wenn ihre Interestingness noch `NOT_EVALUATED` ist.

Die geplanten Deltas dürfen die faktisch verbleibenden Ledger-Budgets nicht überschreiten. Der Plan verändert das Ledger noch nicht; erst die Ausführung bilanziert Arbeit als ausgeführt oder übersprungen.

## Branch-lokale Ausführung v1

`regelsuche.autonomous-campaign-execution/v1` führt ausschließlich `ALLOCATE`-Entscheidungen aus. Der Adapter verwendet den vorhandenen `DeterministicDiscoveryExperimentRunner` als reproduzierbare Seed-Hülle; die eigentliche stufenspezifische Arbeit wird über einen expliziten `StageSeedEvaluator` bereitgestellt.

Vor der Ausführung werden geprüft:

- Brief-, Ledger- und Planidentität,
- Branch-ID und Snapshot-Hash,
- Seed-Stable-Key,
- erlaubte Domäne und erlaubter Generator,
- für die Stufe geplante Ressourcen.

Jeder Evaluator liefert ein Receipt mit:

- `COMPLETED`, `INCONCLUSIVE`, `DUPLICATE`, `DISPROVED`, `UNSAFE`, `PERSISTENTLY_WEAK` oder `BACKEND_UNAVAILABLE`,
- faktisch ausgeführten Ressourcen,
- explizit übersprungenen Ressourcen,
- Runner-Ergebnis und Gegenbeispielstatus,
- nächstem Snapshot-Hash.

Nicht berichtete Werte werden nicht erfunden. Ein fehlender oder unzulässiger Seed verbraucht keine ausgeführte Arbeit; die geplante Zuteilung wird sichtbar als übersprungen bilanziert. Ein Receipt darf die geplanten Deltas nicht überschreiten. `COMPLETED` verlangt zusätzlich mindestens eine ausgeführte, stufenspezifische Nicht-Zeit-Ressource; reine Laufzeit oder Nullarbeit darf keinen Branch fortschreiben.

## Feedback- und Reallokationsrunde v1

`regelsuche.autonomous-campaign-round/v1` verbindet eine Ausführung mit dem anschließend neu berechneten Plan:

```text
Brief + Ledger + Plan
→ explizite Stage-Receipts
→ aktualisiertes Ledger
→ nächste Branch-Snapshots
→ neuer deterministischer Plan
```

Ein `DISPROVED`-, `DUPLICATE`- oder `UNSAFE`-Receipt erzeugt einen terminalen Branch, der in der Folgerunde kein weiteres teures Budget erhält. Ein erfolgreich abgeschlossener Zwischenschritt ergänzt nur die betreffende Evidenzstufe; er ist weder Proof noch Promotion.

Die logische Receipt-Identität trennt sich von Laufzeittelemetrie:

- `logicalContentHash` bindet Entscheidungen, nichtzeitliche Receipt-Inhalte und nächste Snapshots;
- `runtimeTelemetryHash` bindet tatsächliche Laufzeiten und den faktischen Ledgerstand.

Der Folgeplan wird trotzdem aus dem **aktualisierten** Ledger berechnet. Tatsächlich verbrauchte Zeit darf daher den Restetat und die nächste Allokation verändern. Reproduzierbar ist die Reallokation für identische Brief-, Snapshot- und Receipt-Daten; sie ist nicht künstlich unabhängig von realem Ressourcenverbrauch.

## v1 bleibt unverändert

Die v1-Ausführung ist absichtlich **branch-lokal**: Eine Allokation aktualisiert genau den referenzierten Branch. Die v2-Verträge werden daneben eingeführt und verändern weder die v1-Schema-IDs noch ihre kanonischen Hashes.

Der CI-Vertrag reproduziert weiterhin exakt:

```text
v1 brief  = sha256:b1aa8dce6924467390e2a89687678abcd54ba70925e650370faa1b151ae84359
v1 ledger = sha256:7129908aac01fc0f0ee0cbeef91bc02c6537b4e0981ddbee2ae54953de464e77
v1 plan   = sha256:3a66edc6a6bad32ca5338770104d7edf5dd1b5d6a9ea8804a7ce0d445908be50
```

## Research Brief v2

`regelsuche.autonomous-research-brief/v2` führt die wissenschaftlich getrennten Stufen explizit auf:

- `GENERATION`,
- `CANDIDATE_FORMATION`,
- `VALIDATION`,
- `COUNTEREXAMPLE_SEARCH`,
- `PROJECT_NOVELTY`,
- `PROOF`,
- `LIFECYCLE_HANDOFF`.

Jede aktivierte Stufe besitzt mindestens eine konfigurierte, stufenspezifische Nicht-Zeit-Ressource. Projekt-Novelty wird damit nicht in Validation versteckt, und der konservative Lifecycle-Handoff wird nicht mit Proof oder Promotion verwechselt.

## Aggregate Fan-in-Entscheidung v2

`regelsuche.autonomous-aggregate-decision/v2` modelliert die tatsächliche Eingangstopologie des Open-Target-Miners:

- Scope `AGGREGATE` statt branch-lokal,
- mehrere unveränderliche Beobachtungsbranches,
- ausschließlich `GoalStatus.UNTARGETED`,
- Mindestzahl der Eingänge,
- Mindestdiversität der Familien,
- Mindestdiversität der Evidence-Hashes,
- eine Output-Namespace-Strategie statt vorab erfundener Kandidaten-IDs,
- geplante `MINING_BATCHES`- und `CANDIDATES`-Kapazität.

Die Eingangsreihenfolge beeinflusst weder Decision-Hash noch kanonisches JSON.

## Zero-to-many Receipt und Herkunft v2

`regelsuche.autonomous-aggregate-receipt/v2` erlaubt einen ausgeführten Mining-Batch mit:

- null Kandidaten und retained rejected clusters,
- genau einem Kandidaten,
- mehreren deterministisch sortierten Kandidaten.

Ein Kandidatenbranch entsteht erst, wenn der Miner eine konkrete Conjecture-ID erzeugt hat. Daraus werden Output-Branch-ID und Lineage-Hash deterministisch abgeleitet.

Jeder Output bindet ausschließlich seine tatsächlich unterstützenden Beobachtungen:

- Observation-ID,
- Source-Branch-ID,
- Familien-ID,
- Snapshot-Hash,
- Evidence-Hash,
- Hash des unveränderlichen Beobachtungsbranches.

Eine Kandidatenlinie darf keine Beobachtung außerhalb der Aggregate-Entscheidung referenzieren. Zu wenig Support, doppelte Evidence, unzureichende Familiendiversität oder eine externe Observation erzeugen keinen Kandidatenbranch.

`INCONCLUSIVE` und `BACKEND_UNAVAILABLE` erzeugen keine Kandidatenoutputs. Ein nicht verfügbarer Backend-Versuch weist keine ausgeführte Arbeit aus; die geplanten Ressourcen bleiben als übersprungen sichtbar.

## Modulgrenze

Die generischen v1/v2-Verträge für Brief, Budget, Planung, unveränderliche Beobachtungsbranches, Aggregate-Entscheidungen, Receipts und Evidence DAG liegen in `:regelsuche-experiments`. Dieses Modul bleibt unabhängig von den konkreten Mining-, Novelty-, Proof- und Lifecycle-Typen.

Die schmale Composition-Schicht `:regelsuche-autopilot` verbindet diese Verträge mit `:regelsuche-learning`. Dort liegen die produktive Open-Target-Bindung, der Lifecycle-Mapper und die äußeren Campaign-Artefakte. Eine Architekturprüfung verhindert die Rückabhängigkeit `:regelsuche-experiments → :regelsuche-learning`.

## Produktive Open-Target-Bindung

`OpenTargetAutopilotV2Binding` verbindet im Modul `:regelsuche-autopilot` den Aggregate-Vertrag direkt mit dem vorhandenen produktiven `OpenTargetConjectureEvidence`:

- `OpenTargetConjectureEvidence.contentHash()` wird unverändert als Mining-Evidence-Hash gebunden,
- Campaign-ID und Rule-Inventory-Hash werden übernommen und gegen den v2-Brief geprüft,
- die im Plan enthaltenen Observation-IDs müssen exakt den kanonischen Seed-Provenienzen entsprechen,
- ausschließlich Conjecture-IDs aus dem kanonischen Mining-Report können Output-Branches erzeugen,
- abgewiesene produktive Cluster bleiben explizite Evidence,
- Kandidatenlinien enthalten nur die tatsächlich unterstützenden Beobachtungsbranches.

Damit ist die Fan-in/Fan-out-Schicht keine zweite Mining-Implementierung. Sie bindet die vorhandene produktive Mining-Evidence an Plan, Receipt und Lineage.

## Novelty-, Proof- und Lifecycle-Ausgänge

`regelsuche.autonomous-candidate-lifecycle/v2` bildet die bestehenden fachlichen Reports ohne Zusammenlegung ihrer Bedeutung ab:

- `EXACT_DUPLICATE` und `ALPHA_EQUIVALENT_DUPLICATE` enden terminal als `DUPLICATE`; Proof und Lifecycle werden nicht ausgeführt,
- `INCONCLUSIVE_UNPARSEABLE` bleibt `INCOMPLETE`,
- Proof `REFUTED` endet terminal als `DISPROVED`,
- Proof `INCONCLUSIVE` oder `NOT_RUN` bleibt `INCOMPLETE`,
- `SYMBOLICALLY_VERIFIED` ohne Lifecycle-Handoff bleibt `INCOMPLETE`,
- erst der konservative `OpenTargetHypothesisCandidateAdapter`-Handoff schließt die Autopilot-Stufenkette als `COMPLETED` ab.

Der Handoff akzeptiert nur den bestehenden konservativen Status `VALIDATED_BY_EXAMPLES`. Er versucht weder Promotion noch Veröffentlichung.

## Versionierte Campaign-Artefakte v2

Die Aggregate-Objekte werden durch vier äußere, versionierte Verträge referenziert, nicht kopiert:

- `regelsuche.autonomous-campaign-plan/v2`,
- `regelsuche.autonomous-campaign-execution/v2`,
- `regelsuche.autonomous-branch-lineage/v2`,
- `regelsuche.autonomous-campaign-round/v2`.

Der Plan referenziert die kanonischen Aggregate-Decisions. Die Execution bindet Plan, Aggregate-Receipt, produktiven Mining-Evidence-Hash und Ressourcenbilanz. Die Lineage bindet für jeden Output exakt Candidate-, Evidence-, Observation-, Branch- und Snapshot-Provenienz. Die Round bindet Plan, Execution, Lineage, optionale Lifecycle-Entscheidungen und den deterministischen Folgeplan.

## Evidence DAG und CI-Artefakte v2

`regelsuche.autonomous-evidence-dag/v2` bindet:

```text
Research Brief v2
→ unveränderliche Observation Branches
→ Aggregate Decisions
→ Aggregate Receipts
→ zero-to-many Candidate Branches mit Lineage
→ getrennte Novelty-, Proof- und Lifecycle-Ausgänge
→ nächster versionierter Campaign-Plan
```

Ein Receipt ohne retained Decision oder eine Decision ohne retained Observation Inputs wird abgelehnt. Abgewiesene Cluster bleiben Evidence, ohne einen erfolgreichen Kandidatenbranch zu erzeugen.

Der Workflow `Autopilot Evidence` verlangt und archiviert unter anderem:

- aus `:regelsuche-experiments`: `brief-v2.json`, `aggregate-decision.json`, `aggregate-receipt.json` und `dag.json`,
- aus `:regelsuche-autopilot`: `production-binding.json`, `lifecycle-decision.json`, `plan-v2.json`, `execution-v2.json`, `lineage-v2.json`, `round-v2.json` und `next-plan-v2.json`,
- weiterhin die unveränderten v1-Kompatibilitäts- und Ausführungsartefakte.

## Produktive Generation für Issue #348

`PinnedAutonomousProductionCampaign` ist der erste tatsächlich ausgeführte Teil der produktiven Mehr-Runden-Campaign. Der gepinnte v2-Brief enthält kein Target und keine erwartete Antwort. Er bindet das aktive Inventar der vorhandenen `AstRewriteTransformationEngine` und konfiguriert zwei unabhängig bezeichnete Generatorfamilien mit insgesamt zwölf deterministischen Polynomial-Seeds.

`AutonomousProductionGenerationRunner` führt diese Seeds nicht als statische Fixtures aus, sondern über die vorhandenen Produktionskomponenten:

```text
DeterministicDiscoveryExperimentRunner
→ targetfreies SearchProblem
→ AstRewriteTransformationEngine
→ BestFirstSearchStrategy
→ GoalStatus.UNTARGETED
→ unveränderlicher Observation Branch
```

Jede Beobachtung hält den Seed, den Generator, alle erkundeten Zustände, Regelpfade, Annahmen, Suchmetriken sowie getrennte Snapshot-, Evidence- und Branch-Hashes fest. Die Reihenfolge der Eingaben und die Parallelität verändern die kanonischen Hashes nicht.

Der erste Slice veröffentlicht und archiviert:

- `regelsuche.autonomous-production-seed-catalog/v1`,
- `regelsuche.autonomous-production-observations/v2`,
- `regelsuche.autonomous-generation-receipt/v2`,
- `regelsuche.autonomous-production-generation/v2`.

Der Generation-Receipt bilanziert für `GENERATED_STATES`, `EXPLORED_STATES` und `OBSERVATIONS` weiterhin exakt:

```text
configured = executed + skipped + remaining
```

Der reproduzierbare Lauf lautet:

```bash
./gradlew :regelsuche-autopilot:runProductionGeneration
```

Die sechs kanonischen Artefakte liegen anschließend unter `regelsuche-autopilot/build/reports/autopilot-production-generation/` und werden durch den Workflow `Autopilot Evidence` verlangt und hochgeladen. Dieser Slice führt noch kein Aggregate Mining, keine Validierung, keine Novelty-Prüfung, keinen Proof und keinen Lifecycle-Handoff aus; diese folgen nach dem Merge nacheinander auf demselben Issue. Promotion und Public Evidence bleiben `NOT_EVALUATED`.

## Wissenschaftliche Grenze

Planner, Executor, Binding, Lifecycle-Mapper und Evidence DAG dürfen nur entscheiden, **welche Arbeit ausgeführt wird**, **welche Ressourcen verbraucht wurden**, **aus welchen unveränderlichen Eingängen ein Output hervorging** und **welcher interne Workflow-Ausgang erreicht wurde**. Daher bleiben:

```text
plannerDecisionIsMathematicalEvidence = false
executionIsMathematicalEvidence = false
roundDecisionIsMathematicalEvidence = false
decisionIsMathematicalEvidence = false
receiptIsMathematicalEvidence = false
dagIsMathematicalEvidence = false
bindingIsMathematicalEvidence = false
promotionAttempted = false
publicationAttempted = false
promotionStatus = NOT_EVALUATED
publicEvidenceStatus = NOT_EVALUATED
```

Die Runner-/DAG-Anbindung wählt keine Solverportfolios und erklärt kein Ergebnis für extern neu. Solverfähigkeiten bleiben Aufgabe von #233/#234; externe mathematische Neuheit benötigt weiterhin eine separate Prüfung.
