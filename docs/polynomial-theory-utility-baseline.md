# Target-blinde Ausführungsgrenze der Polynomtheorie-Nutzenstudie

## Status und Grenze

Produktiv implementiert ist weiterhin nur `NO_FACTORIZATION`. Die vier
mathematischen Profile bleiben unverbunden; Qualifikation, Candidate-Freeze und
Produktentscheidung existieren noch nicht.

Die vorliegende Erweiterung ergänzt innerhalb derselben Ausführungsgrenze das
exakte Adapterinventar, 30 isolierte Run-Sessions und ein validiertes
In-Memory-Batch. Die Testadapter der vier unverbundenen Profile liefern
ausschließlich klar als Testdaten markierte `UNSUPPORTED`-Resultate. Daraus
folgt keine mathematische oder produktbezogene Evidenz.

## Eingabe- und Ergebnisvertrag

`PolynomialTheoryUtilityProfileAdapter` erhält ausschließlich einen gebundenen
Run-Deskriptor, eine target-blinde Eingabe und deren sichtbaren Formationsfall.
Sein `CandidateResult` verweist auf die unveränderliche Eingabe. Deren `inputId`
bindet Planzeile, Run, Fall, Profil, Checkpoint, Adapter, Budgets und sämtliche
eingefrorenen Studienartefakte.

Der Nulladapter verlässt sich nicht nur auf sichtbare IDs. Beim Öffnen eines
Runs löst er dessen vollständige 20 Eingaben aus dem content-adressierten
600-Zeilen-Freeze auf. An jeder Position muss der übergebene Record exakt mit
der eingefrorenen Eingabe übereinstimmen. Ein syntaktisch gültiger Umschlag, der
beispielsweise eine echte `inputId` wiederverwendet, aber `rowId` oder Budgets
verändert, wird vor der Ausführung abgewiesen und verbraucht keine Run-Position.

Ein Ergebnis darf seine primitiven, mechanischen und Faktorisierungsbudgets
nicht überschreiten. `VALIDATED_TRANSITION` erfordert mindestens einen
Übergang, `VERIFIED` und eine SHA-256-Evidenz; alle anderen Status behalten null
Übergänge und `NONE` als Transitionsevidenz. Weitere Status sind
`NO_TRANSITION`, `UNSUPPORTED`, `BUDGET_INCONCLUSIVE` und
`TECHNICAL_FAILURE`.

## Nullprofil

Der Adapter

```text
regelsuche.polynomial-theory-utility.no-factorization/v1
```

akzeptiert nur die sechs berechneten Baseline-Run-Identitäten und je 20 Fälle in
Formationsreihenfolge. Alle 120 Ergebnisse lauten:

```text
NO_TRANSITION / FACTORIZATION_DISABLED_BY_FROZEN_PROFILE
primitive/mechanical/factorization work = 0
transitions = 0; verifier = NOT_REQUESTED; evidence = NONE
```

Ungenutztes Budget wird weder berechnet noch umverteilt.

## Exaktes Adapterinventar

`PolynomialTheoryUtilityProfileAdapter.AdapterRegistry` akzeptiert genau einen
Adapter für jedes der fünf vorab registrierten Profil-/Adapter-Paare. Fehlende,
zusätzliche, doppelte oder unter einem anderen Profil eingesetzte Adapter werden
vor der ersten Ausführung abgelehnt.

Unabhängig von der Reihenfolge des Aufrufers speichert die Registry die Adapter
in der eingefrorenen Profilreihenfolge. Damit bleiben spätere Iteration,
Berichterstattung und Artefaktbildung deterministisch.

## Run-major Ausführung

`PolynomialTheoryUtilityProfileAdapter.TargetBlindRunner` konsumiert nur das
content-adressierte Ausführungseingabeartefakt:

```text
5 Profile × 6 Checkpoints × 20 Fälle = 600 Eingaben
```

Der Eingabeartefakt-Vertrag bindet die vollständigen kanonischen Bytes und weist
jede veränderte Zeile oder Reihenfolge zurück. Der Runner öffnet für jeden der
30 zusammenhängenden Runs genau eine Adapter-Session. Die 20 Eingaben müssen
dieselbe Run-, Profil-, Checkpoint- und Adapteridentität besitzen und der
eingefrorenen Formationsreihenfolge folgen. Jedes Ergebnis wird unmittelbar
gegen den exakten Input-Record geprüft, bevor es in das Batch gelangt.

Eine Adapterausnahme erzeugt kein partielles Batch und wird nicht automatisch
als mathematisches Resultat umgedeutet. `try`-with-resources schließt die aktive
Session; ein zusätzlicher Schließfehler bleibt als unterdrückte Ausnahme am
ursprünglichen Fehler erhalten. Ein technischer Studienausgang muss vom Adapter
ausdrücklich als `TECHNICAL_FAILURE` geliefert werden.

## Noch nicht eingefrorenes Ergebnisbatch

`PolynomialTheoryUtilityProfileAdapter.CandidateBatch` bewahrt exakt 600
geordnete, eindeutige und an ihre Eingaben gebundene Resultate. Sein Status
lautet:

```text
TARGET_BLIND_RESULTS_COLLECTED_NOT_FROZEN
```

Das Batch bindet Inhaltsadresse und Bytelänge der Ausführungseingaben, besitzt
aber bewusst noch keine eigene kanonische JSON-Darstellung oder öffentliche
SHA-256-Identität. Der vollständige typisierte Messvertrag und die
Candidate-Freeze-Serialisierung folgen in einem eigenen Slice, bevor ein
mathematischer Profiladapter Ergebnisse liefern darf.

## Verifikation

```bash
./gradlew :regelsuche-experiments:test \
  --tests de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityNoFactorizationAdapterTest
```

Die Tests decken ab:

- alle sechs Nullprofil-Runs und 120 eindeutige Zero-Work-Resultate;
- Reihenfolge, erfundene Run-Hashes, Session-Lebenszyklus, Budget-/Evidenzfehler
  und Rebinding;
- einen gefälschten Eingabeumschlag mit wiederverwendeter `inputId`;
- 30 exakt gebundene, geöffnete und geschlossene Sessions;
- 600 Eingaben und Resultate in unveränderter Reihenfolge;
- fehlende, doppelte und falsch profilierte Adapter sowie deterministische
  Registry-Reihenfolge;
- Null- und an fremde Eingaben gebundene Resultate;
- Session-Cleanup und unterdrückte Schließfehler;
- Unveränderlichkeit des Ergebnisbatches.

Als nächster getrennter Slice folgt der versionierte Mess- und
Candidate-Freeze-Vertrag. Erst danach werden native On-Demand-, Cache-,
Quartikkontroll- und optionale SymPy-Adapter einzeln angebunden.
