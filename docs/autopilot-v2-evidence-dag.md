# Autopilot v2: Aggregate Evidence DAG

Autopilot v1 plant und verbucht branch-lokale Arbeit. Die produktive
Open-Target-Kandidatenbildung ist dagegen eine Fan-in/Fan-out-Operation:
mehrere unabhängige Beobachtungen werden gemeinsam gemined und können null,
einen oder mehrere Kandidaten erzeugen.

v2 ergänzt deshalb neue Schema-IDs, ohne v1-Enums, JSON oder Hashes zu ändern.

## Kompatibilitätsgrenze

Vor der Erzeugung eines v2-Objekts reproduziert die CI die eingefrorenen
v1-Hashes für Research Brief, konfiguriertes Ledger und initialen Plan. Damit ist
„v1 bleibt unverändert“ ein ausführbarer Regressionstest.

## Explizite v2-Stufen

`regelsuche.autonomous-research-brief/v2` unterscheidet:

- `GENERATION`,
- `CANDIDATE_FORMATION`,
- `VALIDATION`,
- `COUNTEREXAMPLE_SEARCH`,
- `PROJECT_NOVELTY`,
- `PROOF`,
- `LIFECYCLE_HANDOFF`.

Projektinterne Novelty und Lifecycle-Handoff werden nicht länger implizit unter
Validation oder Proof zusammengefasst. Promotion und Public Evidence bleiben
außerhalb des Autopiloten und stehen weiterhin auf `NOT_EVALUATED`.

## Immutable Observation-Branches

Eine Aggregate-Entscheidung referenziert unveränderliche
`OBSERVATION`-Branches mit:

- Branch-ID,
- Familien-ID,
- Alpha-Fingerprint,
- Snapshot-Hash,
- Evidence-Hash.

Die Entscheidung ändert diese Inputs nicht. Sie sortiert sie deterministisch und
prüft Mindestanzahl, Familien- und Alpha-Diversität. Zu wenig oder duplizierte
Unterstützung erzeugt eine sichtbare `BLOCKED`-Entscheidung.

## Keine vorab erfundenen Kandidaten-IDs

`regelsuche.autonomous-campaign-plan/v2` deklariert für die Aggregate-Stufe:

- `scope = AGGREGATE`,
- geordnete Input-Branches,
- Mindestunterstützung und Diversität,
- geplante Ressourcen,
- Output-Typ und Namespace.

Der Plan enthält noch keine Kandidaten-IDs. Diese entstehen erst aus einem
kanonischen Mining-Report.

## Zero-to-many Execution

`regelsuche.autonomous-campaign-execution/v2` verarbeitet den tatsächlichen
Mining-Report:

- null Kandidaten ergeben `ZERO_OUTPUT`, nicht Erfolg oder Fehler durch
  Vakuum;
- ein oder mehrere Kandidaten erzeugen deterministische
  `CONJECTURE_CANDIDATE`-Branches;
- abgelehnte Cluster bleiben als Evidence sichtbar, erzeugen aber keinen
  erfolgreichen Branch;
- Kandidaten mit duplizierter, fehlender oder nicht alpha-distinkter
  Unterstützung werden separat mit Blockern ausgewiesen.

## Kandidatenspezifische Lineage

`regelsuche.autonomous-branch-lineage/v2` bindet jeden Output-Branch an genau die
Beobachtungsbranches, die der Mining-Report für diesen Kandidaten nennt:

- Source Branch ID,
- Source Snapshot Hash,
- Source Evidence Hash,
- Aggregate-Decision-Hash,
- Mining-Report-Hash,
- kandidatenspezifischen Convergence-Evidence-Hash.

Nicht verwendete Batch-Inputs dürfen nicht in die Lineage eines Kandidaten
rutschen. Eingabe- und Kandidatenreihenfolge ändern weder Output-IDs noch
Lineage-Hashes.

## Round-Vertrag

`regelsuche.autonomous-campaign-round/v2` bindet Plan, Aggregate-Execution,
Lineage-DAG und nächsten Plan. Auch hier gilt:

```text
planner/execution/round decision is mathematical evidence = false
promotionStatus = NOT_EVALUATED
publicEvidenceStatus = NOT_EVALUATED
```

## Aktueller Implementierungsumfang

Der erste v2-Slice liefert die versionierten Verträge, kanonische Hashes,
Permutationstests und CI-Artefakte. Die nächste Stufe bindet die Verträge direkt
an `OpenTargetConjectureMiner` und `OpenTargetConjectureEvidence`, sodass nur
`UNTARGETED`-Beobachtungen und echte Miner-Kandidaten in den DAG gelangen.
