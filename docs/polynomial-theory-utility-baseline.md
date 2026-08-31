# Nullprofil der Polynomtheorie-Nutzenstudie

## Status

Dieser Slice implementiert ausschließlich das Kontrollprofil
`NO_FACTORIZATION`. Die vier mathematischen Profile sind nicht angeschlossen;
die versiegelte Qualifikation bleibt geschlossen.

## Vertrag

`PolynomialTheoryUtilityProfileAdapter` erhält pro Lauf nur Run-, Profil-,
Checkpoint- und Adapteridentität sowie jeweils eine target-blinde Eingabe und
den sichtbaren Formationsfall. Sein verschachtelter `CandidateResult` bewahrt
das terminale Ergebnis, ohne ein zusätzliches Repository-Konzept neben dem
Adaptervertrag einzuführen.

Das Resultat verweist auf die konkrete unveränderliche Eingabehülle. Deren
`inputId` bindet bereits Planzeile, Run, Fall, Profil, Checkpoint, Adapter, alle
Arbeitsgrenzen sowie die Hashes von Präregistrierung, Formation, Qualifikation
und Ausführungsplan. Das Resultat ergänzt nur terminalen Status, tatsächlich
verbrauchte Arbeit, Übergangszahl, Verifier-Ausgang und Transitionsevidenz.

Zulässige Status sind:

```text
VALIDATED_TRANSITION
NO_TRANSITION
UNSUPPORTED
BUDGET_INCONCLUSIVE
TECHNICAL_FAILURE
```

Ein validierter Übergang erfordert mindestens einen Übergang, `VERIFIED` und
eine SHA-256-Evidenz. Andere Status dürfen keine Transitionsevidenz behalten.
Verbrauchte primitive, mechanische und Faktorisierungsarbeit dürfen ihre
jeweilige eingefrorene Grenze nicht überschreiten.

## Nullprofil

Der Adapter

```text
regelsuche.polynomial-theory-utility.no-factorization/v1
```

akzeptiert nur die sechs deterministisch berechneten Baseline-Run-Identitäten
und verarbeitet je 20 Fälle in eingefrorener Reihenfolge. Für alle 120 Inputs
erzeugt er:

```text
NO_TRANSITION
FACTORIZATION_DISABLED_BY_FROZEN_PROFILE
primitive/mechanical/factorization work = 0
transitions = 0
verifier = NOT_REQUESTED
evidence = NONE
```

Nicht verbrauchtes Budget wird weder berechnet noch umverteilt.

## Verifikation

```bash
./gradlew :regelsuche-experiments:test \
  --tests de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityNoFactorizationAdapterTest
```

Die Tests decken alle sechs Runs, 120 eindeutige Resultate, Nullarbeit,
Reihenfolge, erfundene Run-Hashes, unvollständige oder geschlossene Sessions,
Budget-/Evidenzfehler und Rebinding ab.

Der nächste getrennte Slice ist die target-blinde 30-Run-Orchestrierung. Dieser
Stand ist noch kein Nutzen- oder Produktnachweis.
