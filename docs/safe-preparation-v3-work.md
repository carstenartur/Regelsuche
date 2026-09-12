# SAFE-v3: zusätzliche Arbeitsabrechnung

`OccurrenceAwareSharedRulePreparationCoordinator` verwendet für seine zusätzliche
`analyze()`-Arbeit den Vertrag `regelsuche.occurrence-preparation-work/v2`.
Der historische V2-Coordinator, seine Zähler und seine Fixtures bleiben unverändert.

## Direkte Ausführung

`directRuleExecutions` zählt weiterhin die gestarteten Regel-Durchläufe, nicht
die einzelnen AST-Knoten. Zusätzlich zählt die Implementierung unmittelbar vor
jedem Aufruf:

- `directMatchAttempts`: jede besuchte Fundstelle einschließlich erfolgloser Matches;
- `directApplyAttempts`: jeden konkreten Rewrite-Aufruf einschließlich No-op und Ausnahme;
- `directAssumptionRequests`: jede Abfrage konkreter Nebenbedingungen einschließlich Ausnahme.

Die Zähler leben pro Auswertung außerhalb der technischen Fehlergrenze. Bereits
verbrauchte Arbeit verschwindet deshalb nicht, wenn ein späterer Knoten fehlschlägt.
Kandidaten, Fundstellenanalysen und Guard-Anfragen behalten ihre bisherigen Einheiten.
`chargedUnits()` addiert diese getrennten Beiträge mit Überlaufprüfung.

## Wiederholter Aufbau des Delegates

Für `R` ungelöste Principals und `P` Vorbereitungsregeln wird ein neuer V2-Delegate
aufgebaut. Dieser Aufbau ist nicht kostenlos und wird durch `delegateSetupUnits`
mit folgender expliziter, eingabegrößenabhängiger Einheit abgerechnet:

```text
R = 0: 0
R > 0: 1 + (R + P) + R * (P + 1)
```

Das sind ein Konstruktionsvorgang, die Inventarslots und die pro Principal
sichtbaren Regelslots. Ein Slot ist eine zusammengesetzte Initialisierungseinheit,
einschließlich der internen Validierung und Indizierung dieses Inputs. Die Zahl
ist weder ein exakter CPU-Zähler noch eine Laufzeitobergrenze. Ein späterer
Setup-Cache benötigt eine eigene Kosten-/Identitätsentscheidung; hier wird keine
Beschleunigung behauptet und kein zustandsabhängiger Cache eingeführt.

## Grenzen der Aussage

Diese zusätzliche V3-Abrechnung ersetzt nicht die separat ausgewiesene V2-
Ausführungsarbeit. Auch die anfängliche Coordinator-Konstruktion und die gewählte
`verify()`-Strategie gehören in eine vollständige Produktkostenrechnung. Der
CLI-/Workbench-Adapter aus #972 muss diese Grenzen ausdrücklich berücksichtigen
und unter gleichem Kostenvertrag neu gegen DIRECT qualifizieren. Die zusätzlichen
Zähler allein rechtfertigen weder einen Performance-Claim noch einen Defaultwechsel.

`OccurrencePreparationWorkContractTest` prüft unter anderem größere No-match-
Bäume, eingabegrößenabhängigen Setup-Aufwand, konkrete Direktkosten, No-ops,
verbrauchte Arbeit vor Ausnahmen, deterministische Wiederholung und Überlauf.
