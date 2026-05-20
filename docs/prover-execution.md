# Prover-Ausführung

Standardmäßig erzeugt die Proof-Bridge nur ein Skript (Lean-Lemma oder
SMT-LIB) und meldet `FORMALLY_PROVABLE`. Auf Wunsch kann Regelsuche das
Skript *zusätzlich* lokal gegen den jeweiligen Prover laufen lassen und
einen Kandidaten erst dann auf `FORMALLY_PROVED` heben.

## Komponenten

| Klasse                              | Rolle                                                  |
|-------------------------------------|--------------------------------------------------------|
| `ProofBridge`                       | Erzeugt ein Beweisartefakt (kein I/O).                 |
| `ProverExecutor`                    | Führt das Artefakt extern aus (Lean, Z3, CVC5, …).     |
| `ProofBridgeService`                | Bündelt beides; persistiert Artefakte; setzt Status.   |
| `ProverExecutionResult`             | Ausgang der Ausführung: Status, exit code, stdout/stderr, Dauer. |

## Status-Werte

```
SCRIPT_GENERATED      – Nur Skript erzeugt, kein Prover konfiguriert
PROVER_NOT_AVAILABLE  – Executable nicht auf PATH
PROVER_TIMEOUT        – Prover lief länger als das konfigurierte Timeout
PROVER_FAILED         – Prover lief, meldete aber kein Erfolg
PROVER_CONFIRMED      – Prover meldete erfolgreichen Beweis
```

Nur `PROVER_CONFIRMED` führt zu `CandidateProofStatus.FORMALLY_PROVED`.
Alle anderen Pfade bleiben höchstens auf `FORMALLY_PROVABLE`.

## Konfiguration

```java
// Vorbereitete Factories
ProverExecutor lean = ProverExecutor.lean();   // ["lean"]
ProverExecutor z3   = ProverExecutor.z3();     // ["z3", "-smt2"]
ProverExecutor cvc5 = ProverExecutor.cvc5();   // ["cvc5", "--lang=smt2"]

// Eigene Kommandozeile + Timeout + Erfolgs-Prüfung
ProverExecutor custom = new ProverExecutor(
    List.of("lean", "--quiet"),
    "lean4",
    ".lean",
    Duration.ofSeconds(30),
    (exit, out, err) -> exit == 0 && !out.contains("sorry")
);

ProofBridgeService service = new ProofBridgeService(
    new LeanProofBridge(),
    Path.of("build/proofs"),
    lean // oder null, wenn nur das Skript reichen soll
);
```

## Sicherheitshinweise

Der `ProverExecutor` ruft den konfigurierten Befehl mit
`ProcessBuilder` direkt auf. Er ist für den Einsatz auf vertrauten
Entwickler-Maschinen gedacht und **nicht** für Webhook-/SaaS-Szenarien
ausgelegt. Setze den Pfad zum Binary niemals aus Benutzereingaben
zusammen.

## Diagnose

Bei `PROVER_FAILED` werden `stdout` und `stderr` des Provers im
`ProverExecutionResult` zurückgegeben und können protokolliert werden.
Das Skript selbst landet auf Wunsch unter `artifactDirectory/<name>.lean`
bzw. `.smt2`.
