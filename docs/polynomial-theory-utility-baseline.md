# Nullprofil der Polynomtheorie-Nutzenstudie

## Status und Grenze

Dieser Slice implementiert nur `NO_FACTORIZATION`. Die vier mathematischen
Profile bleiben unverbunden; Qualifikation, Candidate-Freeze und
Produktentscheidung existieren noch nicht.

## Ausführungsvertrag

`PolynomialTheoryUtilityProfileAdapter` erhält ausschließlich einen gebundenen
Run-Deskriptor, eine target-blinde Eingabe und deren sichtbaren Formationsfall.
Sein verschachtelter `CandidateResult` verweist auf die unveränderliche Eingabe.
Deren `inputId` bindet bereits Planzeile, Run, Fall, Profil, Checkpoint, Adapter,
Budgets und sämtliche eingefrorenen Studienartefakte.

Der Nulladapter verlässt sich jedoch nicht nur auf sichtbare IDs. Beim Öffnen
eines Runs löst er dessen vollständige 20 Eingaben aus dem content-adressierten
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

## Verifikation

```bash
./gradlew :regelsuche-experiments:test \
  --tests de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityNoFactorizationAdapterTest
```

Die Tests decken alle sechs Runs, 120 eindeutige Resultate, Reihenfolge,
erfundene Run-Hashes, Session-Lebenszyklus, Budget-/Evidenzfehler, Rebinding und
einen gefälschten Eingabeumschlag mit wiederverwendeter `inputId` ab. Der
nächste getrennte Slice ist die target-blinde 30-Run-Orchestrierung.
