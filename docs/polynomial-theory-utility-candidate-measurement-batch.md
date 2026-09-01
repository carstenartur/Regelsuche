# Vollständiges Messbatch vor der Candidate-Freeze

Status: target-blinde 600-Zeilen-Bindung, noch nicht serialisiert

Bezug: Issue #748

## Problem

Der resultweite Messvertrag belegt, dass eine einzelne
`PolynomialTheoryUtilityCandidateMeasurements`-Instanz vollständig zu genau
einem `PolynomialTheoryUtilityCandidateResult` passt. Ohne einen eigenen
Batchvertrag könnte ein Aufrufer trotzdem:

- eine Ergebniszeile ohne Messbegleiter auslassen,
- Messbegleiter umordnen oder verdoppeln,
- ein gültiges Messobjekt einem anderen Resultatbatch zuordnen,
- nur eine günstige Teilmenge der 600 Zeilen an die Candidate-Freeze übergeben.

## Vertrag

`PolynomialTheoryUtilityCandidateMeasurementBatch` bindet das bereits
validierte `CandidateBatch` aus dem target-blinden 30-Läufe-Orchestrator an
exakt eine positionsgleiche Messinstanz je Ergebniszeile.

Der Vertrag verlangt:

- genau 600 Resultat-/Messpaare,
- dieselbe Reihenfolge wie im eingefrorenen Ausführungseingangsartefakt,
- `measurements[i].result == candidateBatch.results[i]`,
- eindeutige Messidentitäten,
- unveränderliche Resultat- und Messlisten.

Das Batch besitzt den Zustand:

```text
TARGET_BLIND_MEASUREMENTS_BOUND_NOT_FROZEN
```

Es ist damit die letzte In-Memory-Grenze vor der kanonischen
Candidate-Freeze, aber noch kein öffentliches Ergebnisartefakt.

## Inhaltsidentität

Die Batchidentität enthält:

- Schema, Studienidentität und Evidenzstatus,
- das Schema des bereits typisierten Resultatbatches,
- Hash und Bytelänge der 600 eingefrorenen Ausführungseingänge,
- die exakte Zeilenanzahl,
- je Position die Resultatidentität und die zugehörige Messidentität.

Eine Änderung eines terminalen Ergebnisses, einer Trace, eines Attempts, eines
Cacheereignisses, einer Annahme oder eines AST-Messwerts ändert dadurch die
Batchidentität. Die Details werden nicht als zweite konkurrierende Struktur
wiederholt, sondern über ihre bereits content-adressierten Identitäten gebunden.

## Fail-closed-Regeln

Zurückgewiesen werden insbesondere:

- fehlende oder zusätzliche Messzeilen,
- vertauschte Messzeilen,
- mehrfach verwendete Messidentitäten,
- ein Messbegleiter für ein anderes Resultat,
- ein Resultatbatch mit abweichendem Eingangsartefakt oder Resultatinhalt,
- eine gefälschte Batchidentität.

## Nächster Schritt

Die anschließende Candidate-Freeze darf ausschließlich dieses vollständige
Messbatch serialisieren. Sie muss zusätzlich Formation, Präregistrierung,
Ausführungsplan und die weiterhin nur als Hash/Bytelänge sichtbare versiegelte
Qualifikation binden, kanonische UTF-8/LF-Bytes erzeugen und unbekannte,
manipulierte oder umgeordnete Daten fail-closed ablehnen.

Erst nach dieser Freeze werden mathematische Profiladapter ausgeführt. Dieses
Batch enthält weiterhin keine erwarteten Ausgänge, Referenzausdrücke,
Reduzierbarkeitslabels oder Produktentscheidung.
