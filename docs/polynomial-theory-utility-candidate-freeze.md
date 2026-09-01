# Candidate-Freeze der Polynomtheorie-Nutzenstudie

## Zweck

Dieser Slice friert die vollständig eingesammelten, weiterhin zielblinden
Ergebnisse der Polynomtheorie-Nutzenstudie als kanonisches, content-adressiertes
Artefakt ein. Er öffnet weder die versiegelte Qualifikation noch führt er einen
mathematischen Profiladapter aus.

Die Eingangsgrenze ist ausschließlich das validierte In-Memory-Batch des
30-Run-Runners:

```text
5 Profile × 6 Checkpoints × 20 Fälle = 600 Resultate
```

## Artefaktidentität

`PolynomialTheoryUtilityCandidateFreeze` erzeugt genau die versionierte Datei

```text
polynomial-theory-utility-candidate-freeze-v1.json
```

mit dem Schema

```text
regelsuche.polynomial-theory-utility-candidate-freeze/v1
```

und dem Evidenzstatus

```text
TARGET_BLIND_CANDIDATE_FREEZE
```

Die JSON-Darstellung besitzt eine einzige kanonische UTF-8/LF-Serialisierung.
Aus den exakten Bytes werden Bytelänge und SHA-256 berechnet. Der
`Artifact`-Konstruktor rekonstruiert die kanonischen Bytes und weist ab:

- eine andere Zeilenreihenfolge;
- fehlende, zusätzliche oder doppelte Resultate;
- veränderte Input-, Plan-, Run-, Fall-, Profil-, Checkpoint- oder Adapter-IDs;
- ein Resultat, dessen ID nicht mehr zu seinem terminalen Payload passt;
- veränderte kanonische Bytes, Bytelängen oder Inhalts-Hashes;
- Resultatwerte außerhalb der bereits eingefrorenen Eingabeautorität.

## Gebundene Quellen

Der Freeze bindet, ohne sie neu zu interpretieren:

- das exakte 600-Zeilen-Ausführungseingabeartefakt;
- den versionierten Ausführungsplan;
- den target-blinden Formationskorpus;
- Pfad, Bytelänge und SHA-256 der versiegelten Qualifikation;
- Schema und Status des noch nicht serialisierten Ergebnisbatches.

Die Qualifikation bleibt im Zustand

```text
HASH_ONLY_NOT_OPENED
```

Die Serialisierung enthält insbesondere keine erwarteten Resultate,
Reduzierbarkeits- oder Multiplizitätslabels, Referenzausdrücke,
Klassifikator-Sollwerte oder Produktentscheidung.

## Zeilenvertrag

Jede der 600 Zeilen enthält nur die bereits target-blind verfügbaren
Ausführungs- und Resultatdaten:

```text
candidateResultId
inputId / executionRowId / runId
caseId / profileId / checkpointId / adapterId
terminalStatus / detailCode
primitiveWorkConsumed
mechanicalWorkConsumed
factorizationWorkConsumed
generatedTransitions
verifierOutcome
transitionEvidenceHash
```

Die vorhandene `CandidateResult`-Identität bleibt die Zeilenidentität; der
Freeze erfindet keine zweite, konkurrierende Ergebnis-ID. Bei der Konstruktion
wird jede Zeile aus dem exakten eingefrorenen Input und ihrem terminalen Payload
neu aufgebaut. Nur wenn die resultierende ID übereinstimmt, darf sie in die
kanonischen Bytes gelangen.

## Verifikation

Der fokussierte Vertrag wird ausgeführt mit:

```bash
./gradlew :regelsuche-experiments:test \
  --tests de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCandidateFreezeTest
```

Die Charakterisierung prüft:

- vollständige 5 × 6 × 20-Abdeckung;
- 120 Nullprofil- und 480 explizit nicht unterstützte Testresultate;
- Byte- und Hash-Stabilität bei identischem Input;
- Hashänderung bei einer einzelnen terminalen Payloadänderung;
- Ablehnung von Zeilenvertauschung und wiederverwendeter Resultat-ID;
- Ablehnung gefälschter kanonischer Bytes, Bytelängen und Inhalts-Hashes;
- unveränderliche Zeilenlisten und defensive Byteausgabe;
- das vollständige Verbot qualifikations- oder entscheidungsbezogener Felder.

## Claim-Grenze

Dieser Slice belegt ausschließlich, dass ein vollständiger target-blinder
Resultatsatz deterministisch und fail-closed eingefroren werden kann. Er belegt
keine erfolgreiche Faktorisierung, keine Profilparität, keine held-out
Qualifikation, keinen Suchvorteil, keine Cache-Amortisation und keine
Produktentscheidung.

Erst nach Merge und vollständiger Qualifikation dieses Vertrags dürfen die vier
mathematischen Profiladapter einzeln angebunden werden. Die versiegelte
Qualifikation darf weiterhin erst nach dem endgültigen Candidate-Freeze geöffnet
werden.
