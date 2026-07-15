# Autopilot: targetfreie Campaign-Planung

Der Autopilot verteilt Ressourcen auf bestehende Discovery- und Evidenzstufen. Seine Entscheidungen sind **keine** mathematische Evidenz und ersetzen weder Falsifikation noch Novelty, Proof, Promotion oder Public Evidence.

## Research Brief

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

## Budget Ledger

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

## Deterministischer Allokationsplan

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

## Ausführung durch den vorhandenen Runner

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

## Feedback- und Reallokationsrunde

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

## Grenze von v1 und v2-DAG

Die v1-Ausführung ist absichtlich **branch-lokal**: Eine Allokation aktualisiert genau den referenzierten Branch. Das ist für die Ausführungs-, Receipt- und Feedback-Grundlage ausreichend, bildet aber die produktive Open-Target-Kandidatenbildung noch nicht vollständig ab.

`OpenTargetConjectureMiner.mine(...)` verarbeitet mehrere unabhängige `UNTARGETED`-Beobachtungen gemeinsam und kann aus einem Batch null, einen oder mehrere Kandidaten erzeugen. Diese Fan-in/Fan-out-Semantik wird nicht nachträglich unter den v1-Schema-IDs versteckt.

Der erste v2-Vertragsslice ergänzt deshalb neue Schema-IDs mit:

- eingefrorenen v1-Golden-Hashes als Kompatibilitätsgate,
- expliziten Stufen `PROJECT_NOVELTY` und `LIFECYCLE_HANDOFF`,
- unveränderlichen Observation-Branches,
- aggregierten Candidate-Formation-Entscheidungen,
- zero-to-many Kandidatenbranches erst nach dem Mining-Report,
- kandidatenspezifischer Lineage,
- expliziter Nullausgabe und sichtbaren Rejections.

Details: [Autopilot v2: Aggregate Evidence DAG](autopilot-v2-evidence-dag.md).

Die direkte Bindung an `OpenTargetConjectureMiner` und `OpenTargetConjectureEvidence` folgt im nächsten Slice von #336. Damit bleiben v1-Artefakte und ihre Hashsemantik stabil, während die produktive #221-Kette schrittweise korrekt modelliert wird.

## Wissenschaftliche Grenze

Planner und Executor dürfen nur entscheiden, **welche Arbeit als Nächstes ausgeführt wird** und **welche Ressourcen tatsächlich verbraucht wurden**. Daher bleiben:

```text
plannerDecisionIsMathematicalEvidence = false
executionIsMathematicalEvidence = false
roundDecisionIsMathematicalEvidence = false
promotionStatus = NOT_EVALUATED
publicEvidenceStatus = NOT_EVALUATED
```

Die Runner-Anbindung wählt keine Solverportfolios und erklärt kein Ergebnis für extern neu. Solverfähigkeiten bleiben Aufgabe von #233/#234; externe mathematische Neuheit benötigt weiterhin eine separate Prüfung.
