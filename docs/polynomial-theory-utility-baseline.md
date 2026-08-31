# Nullprofil der Polynomtheorie-Nutzenstudie

## Status

Der erste ausführbare Profiladapter der eingefrorenen Nutzenstudie ist das
Kontrollprofil `NO_FACTORIZATION`. Es belegt nur die Ausführungs- und
Arbeitsgrenze des Nullpfads. Die vier mathematischen Vergleichsprofile sind
weiterhin nicht implementiert und die versiegelte Qualifikation bleibt
ungeöffnet.

## Adaptervertrag

`PolynomialTheoryUtilityProfileAdapter` erhält pro Lauf ausschließlich:

- `runId`, Profil, Checkpoint und die vorab eingefrorene Adapter-ID;
- genau eine target-blinde Ausführungseingabe;
- den zugehörigen sichtbaren Formationsfall.

Qualifikationsfelder, Referenzausdrücke, Ergebnisse anderer Profile und eine
Produktentscheidung sind nicht Teil der API.

## Inhaltsadressiertes terminales Ergebnis

`PolynomialTheoryUtilityCandidateResult` bindet jedes Ergebnis an Eingabe,
Planzeile, Lauf, Fall, Profil, Checkpoint, Adapter und die drei eingefrorenen
Arbeitsgrenzen. Seine SHA-256-Identität umfasst außerdem terminalen Status,
Detailcode, tatsächlich verbrauchte Arbeit, Übergangszahl, Verifier-Ausgang und
optionale Transitionsevidenz.

Zulässige terminale Status sind:

```text
VALIDATED_TRANSITION
NO_TRANSITION
UNSUPPORTED
BUDGET_INCONCLUSIVE
TECHNICAL_FAILURE
```

Ein validierter Übergang erfordert mindestens einen Übergang, den
Verifier-Ausgang `VERIFIED` und eine SHA-256-Evidenz. Alle anderen Status dürfen
keine Transitionsevidenz behalten. Primitive, mechanische und
Faktorisierungsarbeit dürfen ihre jeweilige eingefrorene Grenze nicht
überschreiten.

## Semantik des Nullprofils

Der Adapter

```text
regelsuche.polynomial-theory-utility.no-factorization/v1
```

verarbeitet sechs getrennte Checkpoint-Läufe mit jeweils 20 Fällen. Er prüft
die eingefrorene Fallreihenfolge und liefert für alle 120 Eingaben:

```text
terminalStatus:            NO_TRANSITION
detailCode:                FACTORIZATION_DISABLED_BY_FROZEN_PROFILE
primitiveWorkConsumed:     0
mechanicalWorkConsumed:    0
factorizationWorkConsumed: 0
generatedTransitions:      0
verifierOutcome:           NOT_REQUESTED
transitionEvidenceHash:    NONE
```

Nicht verbrauchtes Budget wird weder künstlich berechnet noch einem anderen
Suchkanal zugewiesen.

## Verifikation

```bash
./gradlew :regelsuche-experiments:test \
  --tests de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityNoFactorizationAdapterTest

./gradlew :regelsuche-experiments:check
```

Die Tests decken alle 120 Baseline-Eingaben, sechs isolierte Läufe, eindeutige
Ergebnisidentitäten, Nullarbeit, Reihenfolgeverletzungen, unvollständige und
bereits geschlossene Läufe sowie Budget-, Evidenz- und Rebinding-Fehler ab.

## Aussagegrenze und nächster Slice

Dieser Stand ist kein Nutzennachweis für Faktorisierung oder Cache-Replay. Als
Nächstes folgt der generische Candidate-Freeze-Runner in einem separaten Slice.
Erst danach wird der native On-Demand-Adapter an die bestehende exakte
Parser–Request–Engine–Verifier–Transformation-Kette angeschlossen.
