# Exaktes Replay-Receipt für endliche Polynompläne

`ExactFinitePolynomialPlanReplayVerifier` führt einen gebundenen
`ExactFinitePolynomialPlanRun` mit derselben Quelle, demselben Ansatz, denselben
Hole-Domänen und demselben Retained-Limit vollständig erneut aus. Nur ein exakt
gleicher Lauf erhält ein `ReplayReceipt`; jede Abweichung wird fail-closed
abgelehnt.

Das öffentliche Receipt ist eine versiegelte, nur lesbare Schnittstelle mit
einer einzigen privaten Implementierung, die ausschließlich `verify(...)`
erzeugt. Es bindet Verifier- und Solverrevision, Plan-, Planlauf- und
Solverresultat-Hash, Laufstatus, alle Belegungszähler, sämtliche gespeicherten
Kandidatenhashes und den eigenen Content-Hash. Null-, vollständige und
abgeschnittene Lösungsmengen bleiben unterscheidbar; widersprüchliche Zähler
oder doppelte Kandidatenidentitäten werden abgelehnt.

`CONFIRMED_IDENTICAL_REPLAY` bestätigt nur die deterministische Reproduktion
unter den gebundenen Revisionen. Es ist keine unabhängige Proof-Evidence, lädt
keine externen Evidence-Bytes, replayt keine primitiven Rewrite-Regeln,
kompiliert kein `RewriteProgram` und erteilt keine Promotion-Autorität. Quelle,
Ansatz, Ziel und ausführbare Transformation stehen absichtlich nicht im
Receipt.

```bash
./gradlew :regelsuche-learning:test \
  --tests '*ExactFinitePolynomialPlanReplayVerifierTest'
```

Eine spätere stärkere Evidence-Grenze muss gespeicherte Bytes selbst hashen und
den Lauf unter einer eingefrorenen Runtime erneut ausführen.
