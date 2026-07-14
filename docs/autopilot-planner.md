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

Die geplanten Deltas dürfen die faktisch verbleibenden Ledger-Budgets nicht überschreiten. Der Plan verändert das Ledger noch nicht; erst eine spätere Ausführung bilanziert Arbeit als ausgeführt oder übersprungen.

## Wissenschaftliche Grenze

Der Planner darf nur entscheiden, **welche Arbeit als Nächstes ausgeführt werden soll**. Daher bleiben im Plan:

```text
plannerDecisionIsMathematicalEvidence = false
promotionStatus = NOT_EVALUATED
publicEvidenceStatus = NOT_EVALUATED
```

Die erste Version erzeugt Brief, Ledger und Plan. Die Anbindung an `DeterministicDiscoveryExperimentRunner` und `ScientificDiscoveryWorkflow` folgt in einem späteren Slice von #225.
