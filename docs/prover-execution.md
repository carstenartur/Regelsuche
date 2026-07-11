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
| `ProofPolicy`                       | Steuert, ob ein bestätigter Beweis für Promotion/Galerie erforderlich ist. |
| `ProofConfirmation`                 | Persistentes Snapshot einer externen Beweisbestätigung (inkl. Revision-Hash). |
| `ProofScriptValidator`              | Prüft Skripte auf `sorry`/`admit`-Platzhalter vor der Ausführung. |

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

## Proof-Richtlinien (ProofPolicy)

Die `ProofPolicy` konfiguriert, ob externe Beweisbestätigung als Gate für
Promotion oder öffentliche Galerie-Evidenz erzwungen wird:

| Wert                               | Bedeutung                                                                |
|------------------------------------|--------------------------------------------------------------------------|
| `PROOF_OPTIONAL`                   | Kein Beweis erforderlich (Standardverhalten).                             |
| `PROOF_REQUIRED_FOR_PROMOTION`     | `PROVER_CONFIRMED` ist Pflicht, bevor ein Kandidat promotet werden kann. |
| `PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE` | `PROVER_CONFIRMED` ist Pflicht für Promotion **und** Galerie-Anzeige.  |

Invariante: **`SCRIPT_GENERATED` erfüllt niemals eine obligatorische Proof-Richtlinie.**
Timeout, Rejection und nicht verfügbarer Prover sind jeweils eigene Blocker.

### Beispiel: Promotion mit obligatorischem Beweis

```java
PromotionObservation obs = new PromotionObservation(
    candidateId, campaignId, date, family,
    left, right, true, "AGREE", evidence,
    "DEGRADED", operator, pack, assumptions, rationale,
    rulePath, true, false, false, false,
    ProofPolicy.PROOF_REQUIRED_FOR_PROMOTION,
    "PROVER_CONFIRMED"   // oder z. B. "PROVER_TIMEOUT"
);
PromotionRecord record = new PromotionDecider().decide(obs);
// record.promotionBlockers() enthält "proof=PROVER_TIMEOUT"
// wenn der Prover nicht bestätigt hat.
```

## Beweisbestätigung persistieren (ProofConfirmation)

`ProofConfirmation` bindet eine externe Proverbestätigung an den exakten
Hypothesen-Revisions-Hash und Annahmen-Fingerprint. Wird der Kandidateninhalt
oder werden die Annahmen geändert, ist die Bestätigung ungültig:

```java
ProofConfirmation conf = ProofConfirmation.of(result, artifactHash,
    assumptionsFingerprint, candidateRevisionHash, command, timeoutMs);

// Wiederverwendbarkeit prüfen:
conf.isValidFor(currentRevisionHash, currentAssumptionsFingerprint);
// → false, wenn sich Inhalt oder Annahmen geändert haben
```

## Skript-Validierung (ProofScriptValidator)

Vor dem Einsenden an den Prover sollte das Skript validiert werden:

```java
ProofScriptValidator.ValidationResult result =
    ProofScriptValidator.validate(script, "lean4");

if (result.hasAdmittedStatement()) {
    // sorry / admit im Skript → darf niemals als PROVER_CONFIRMED gelten
}
```

## Containerisierte Reproduktion

`ProofConfirmation.containerReproductionCommand(mountPath)` generiert einen
`docker run`-Befehl, mit dem der Beweis in einer isolierten Umgebung
reproduziert werden kann:

```
docker run --rm -v "/proofs:/workspace" regelsuche/prover-sandbox:4.3.0
    timeout 60 lean4 /workspace/<artifact>
```

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

