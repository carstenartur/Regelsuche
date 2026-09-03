# Exaktes Replay-Receipt für endliche Polynompläne

`ExactFinitePolynomialPlanReplayVerifier` ersetzt die bisherige boolesche
Replay-Aussage durch ein kanonisches Receipt:

```text
SchematicProofPlan
  + eingefrorene Quelle, Ansatz, Hole-Domänen und Retained-Limit
  + erwarteter ExactFinitePolynomialPlanRun
  -> vollständige erneute Solverausführung
  -> exakter Vergleich des gesamten Planlaufs
  -> ReplayReceipt
```

Abweichungen bei Plan, Formationseingaben oder erwartetem Lauf werden
fail-closed abgelehnt.

## Ausstellung und Inhalt

`ReplayReceipt` ist eine versiegelte, nur lesbare Schnittstelle. Ihre einzige
zugelassene Implementierung ist ein privater Record im Verifier. Ohne einen
erfolgreichen Aufruf von `verify(...)` lässt sich daher regulär kein Receipt mit
`CONFIRMED_IDENTICAL_REPLAY` konstruieren.

Das Receipt bindet Verifier- und Solverrevision, Plan-, Planlauf- und
Solverresultat-Hash, Laufstatus, vollständige/evaluierte/passende/gespeicherte
Belegungszahlen, alle aufgelösten Kandidatenhashes und den eigenen Hash. Dabei
bleiben folgende Zustände getrennt:

```text
COMPLETE_WITHOUT_SOLUTION
COMPLETE_WITH_RESOLUTIONS
COMPLETE_RESOLUTION_SET_TRUNCATED
```

Nullfunde dürfen keine Kandidaten enthalten; abgeschnittene Mengen müssen mehr
passende als gespeicherte Lösungen ausweisen. Inkonsistente Zähler und doppelte
Kandidatenhashes werden abgelehnt.

## Vertrauensgrenze

`CONFIRMED_IDENTICAL_REPLAY` bestätigt ausschließlich, dass derselbe
kanonische Plan unter den gebundenen Resolver- und Solverrevisionen vollständig
und exakt reproduziert wurde. Es ist keine formal unabhängige Proof-Evidence,
lädt keine externen Evidence-Bytes, replayt keine primitiven Rewrite-Regeln,
kompiliert kein `RewriteProgram`, lernt keine Ansatzgrammatik und autorisiert
keine Promotion oder Public Evidence.

Quelle, Ansatz, Zielausdruck und ausführbare Transformation stehen absichtlich
nicht im Receipt; die Formationseingaben bleiben über Plan- und Planlaufhashes
gebunden.

## Charakterisierung

```bash
./gradlew \
  :regelsuche-learning:test \
  --tests '*ExactFinitePolynomialPlanReplayVerifierTest'
```

Die Tests prüfen quadratische Ergänzung, deterministisches Replay,
vollständige Null- und abgeschnittene Resultate, Eingabe-/Plan-/Laufsubstitution,
kanonische Inhalte, die private versiegelte Ausstellung und das Fehlen von Ziel-
oder Ausführungsfeldern.

Ein späterer unabhängiger Evidence-Verifier muss gespeicherte Bytes selbst
hashen und den Replaylauf unter einer eingefrorenen Runtime wiederholen, bevor
eine stärkere Autorität ausgestellt werden darf.
