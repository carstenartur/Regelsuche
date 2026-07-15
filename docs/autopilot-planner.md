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

## Evidence DAG v2

`regelsuche.autonomous-evidence-dag/v2` bindet:

```text
Research Brief v2
→ unveränderliche Observation Branches
→ Aggregate Decisions
→ Aggregate Receipts
→ zero-to-many Candidate Branches mit Lineage
```

Ein Receipt ohne retained Decision oder eine Decision ohne retained Observation Inputs wird abgelehnt. Abgewiesene Cluster bleiben Evidence, ohne einen erfolgreichen Kandidatenbranch zu erzeugen.

Dieser Vertrags-Slice bindet den produktiven `OpenTargetConjectureMiner` noch nicht. Die nächste Stufe übersetzt dessen kanonischen Report und `OpenTargetConjectureEvidence.contentHash()` in genau diese Aggregate-Receipts und Lineages.

## Wissenschaftliche Grenze

Planner, Executor und Evidence DAG dürfen nur entscheiden, **welche Arbeit ausgeführt wird**, **welche Ressourcen verbraucht wurden** und **aus welchen unveränderlichen Eingängen ein Output hervorging**. Daher bleiben:

```text
plannerDecisionIsMathematicalEvidence = false
executionIsMathematicalEvidence = false
roundDecisionIsMathematicalEvidence = false
decisionIsMathematicalEvidence = false
receiptIsMathematicalEvidence = false
dagIsMathematicalEvidence = false
promotionStatus = NOT_EVALUATED
publicEvidenceStatus = NOT_EVALUATED
```

Die Runner-/DAG-Anbindung wählt keine Solverportfolios und erklärt kein Ergebnis für extern neu. Solverfähigkeiten bleiben Aufgabe von #233/#234; externe mathematische Neuheit benötigt weiterhin eine separate Prüfung.
