# Exaktes Replay-Receipt für endliche Polynompläne

## Deterministischer Replaylauf

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
unter den gebundenen Revisionen. Es ist keine unabhängige Proof-Evidence,
replayt keine primitiven Rewrite-Regeln, kompiliert kein `RewriteProgram` und
erteilt keine Promotion-Autorität. Quelle, Ansatz, Ziel und ausführbare
Transformation stehen absichtlich nicht im Receipt.

## Unabhängig geladene Artifact-Bytes

`ExactFinitePolynomialPlanReplayArtifactVerifier` ergänzt eine getrennte
Speichergrenze. Receipt und Planlauf besitzen unterschiedliche Rollen,
Inhaltsschemata und Medientypen. Ein Artefakt wird durch eine versionierte
`ArtifactReference` beschrieben:

```text
Artifact-Rolle
+ Inhaltsschema
+ Medientyp
+ SHA-256 der exakten Bytes
+ exakte Byte-Länge
  -> metadata-bound Artifact-ID
```

Der Verifier fordert die Bytes ausschließlich unter der erwarteten Artifact-ID
von einer minimalen `ArtifactSource` an. Er prüft den zurückgegebenen Schlüssel,
erzeugt eine defensive Kopie, erzwingt die 1.000.000-Byte-Grenze, dekodiert
striktes UTF-8, lehnt BOM und äußere Leerzeichen ab und berechnet Länge, Hash
und metadata-gebundene Artifact-ID selbst erneut.

`VerifiedArtifactBytes` ist eine versiegelte Schnittstelle mit einer einzigen
privaten Implementierung. Alle Byte-Zugriffe liefern Kopien. Identische Bytes
unter einer anderen Rolle oder einem anderen Inhaltsschema besitzen eine andere
Artifact-ID. Receipt-Bytes können nicht als Planlauf und Planlauf-Bytes nicht
als Receipt verifiziert werden.

## Kanonische semantische Receipt-Prüfung

`ExactFinitePolynomialPlanReplayReceiptArtifactVerifier` akzeptiert nur ein
bereits ausgestelltes `VerifiedArtifactBytes`. Er prüft anschließend das
schema-spezifische Receipt selbst:

```text
VerifiedArtifactBytes
  -> exakte Feldreihenfolge und Feldmenge
  -> unescaped canonical ASCII für IDs, Hashes und Enums
  -> kanonische nichtnegative Dezimalzahlen
  -> aktuelle Receipt-, Replay-Verifier- und Solverrevision
  -> Plan-, Planlauf-, Solverresultat- und Kandidatenhashes
  -> Status-/Zähler-Invarianten
  -> eindeutige, sortierte Kandidatenidentitäten
  -> Receipt-Content-Hash erneut berechnen
  -> vollständiges kanonisches JSON erneut rendern
  -> exakte Gleichheit mit den geladenen Bytes
  -> VerifiedReplayReceiptArtifact
```

Der Parser ist absichtlich kein toleranter Universal-JSON-Parser. Alternative
Feldreihenfolgen, zusätzliche Felder, führende Nullen und semantisch gleiche
Escape-Schreibweisen sind nicht dieselbe kanonische Evidence und werden
abgelehnt.

`VerifiedReplayReceiptArtifact` bindet die metadata-geprüfte Artifact-Referenz,
die semantischen Receipt-Felder und eine eigene versionierte
Verifikationsidentität. Die einzige Implementierung ist privat und wird nur nach
vollständiger Prüfung erzeugt.

## Kanonische Planlauf-Projektion

`ExactFinitePolynomialPlanRun` besitzt zusätzlich zu seinem semantischen
`contentHash` eine eigenständig versionierte kanonische Artefaktdarstellung:

```text
schema
+ artifactRevisionHash
+ Resolver-, Solver- und Planidentitäten
+ normalisierte Quelle und Ansatzschablone
+ sämtliche typisierten Hole-Domänen und exakten Werte
+ vollständige Zähler, Limits und terminale Status
+ sämtliche gespeicherten Lösungen und typisierten Bindungen
+ sämtliche Kandidaten mit vollständiger Resolution und Evidence-Links
  -> kanonisches kompaktes JSON
```

Die Reihenfolge stammt ausschließlich aus den bereits validierten und
kanonisch normalisierten Domänen, Lösungen, Kandidaten, Bindungen und
Obligation-Outcomes. Die Darstellung wird vor ihrer Ausgabe erneut gegen die
1.000.000-Byte-Grenze geprüft.

Der semantische `planRunHash` und der SHA-256 der gespeicherten Bytes bleiben
absichtlich verschiedene Identitäten. Der erste bindet die mathematisch
relevante Laufstruktur; der zweite bindet exakt diese versionierte externe
Darstellung. Eine Änderung des Artefaktschemas ändert die Byteadresse, ohne den
alten semantischen Lauf stillschweigend umzudeuten.

## Unabhängig aufbewahrter Planlauf

Ein semantisch gültiges Receipt genügt noch nicht. Der zugehörige Planlauf muss
unter einer getrennten `plan-run`-Artifact-ID geladen und an die aktuelle
typisierte Repräsentation gebunden werden.

`ExactFinitePolynomialPlanReplayConfirmationVerifier` behandelt deshalb auch
ein bereits konstruiertes `ExactFinitePolynomialPlanRun` zunächst als
unvertrauenswürdig. Es wird nur akzeptiert, wenn seine kanonische UTF-8-Ausgabe
bytegenau dem unabhängig geladenen und metadata-geprüften Planlauf-Artefakt
entspricht:

```text
unabhängig geladene VerifiedArtifactBytes(role=plan-run)
+ zunächst unvertrauenswürdiger typisierter Planlauf
  -> kanonische JSON-Bytes des typisierten Werts
  -> exakte Byte- und ArtifactReference-Gleichheit
  -> erst danach darf der typisierte Wert verwendet werden
```

Damit ist kein toleranter Planlauf-Parser nötig, der zwei unterschiedliche
JSON-Darstellungen als dieselbe Evidence behandeln könnte. Der typisierte Wert
liefert nur eine Rekonstruktionsbehauptung; die separat geladenen Bytes bleiben
die Speicherautorität.

## Vollständige Cross-Bindung und erneute Ausführung

Nach der Bytegleichheit prüft die Confirmation-Stufe:

```text
receipt.planHash                   == plan.contentHash
receipt.planRunHash                == retainedPlanRun.contentHash
receipt.solverResultHash           == retainedPlanRun.solverResult.contentHash
receipt.solverRevisionHash         == retainedPlanRun.solverResult.solverRevisionHash
receipt.runStatus und alle Zähler  == retainedPlanRun
receipt.resolvedCandidateHashes    == retainedPlanRun.candidates
```

Anschließend erhält der bestehende Replay-Verifier den eingefrorenen Plan, die
Quelle, den Ansatz, die Hole-Domänen, das Retained-Limit und den bytegebundenen
Planlauf. Er führt Resolver und Solver **ein weiteres Mal vollständig** aus. Nur
wenn das neu erzeugte Receipt exakt dem semantisch geprüften, unabhängig
gespeicherten Receipt entspricht, entsteht ein privates, versiegeltes
`ConfirmedReplay`.

Die Bestätigung bindet beide Artifact-Referenzen, die semantische
Receipt-Verifikationsidentität, Plan-, Planlauf-, Solverresultat- und
Solverrevisionshash, Status, Zähler, die geordneten Kandidatenidentitäten, die
Anzahl der frischen vollständigen Replayausführungen und einen eigenen
`confirmationHash`.

Damit werden Änderungen an Quelle, Ansatz, Domänenart, Domänenwerten,
Domänenreihenfolge, Retained-Limit, Solverresultat oder Kandidatenidentitäten
fail-closed erkannt. Vollständige Nullergebnisse, vollständige Lösungsmengen und
abgeschnittene gespeicherte Lösungsmengen bleiben getrennte Zustände.

## Präzise Vertrauensgrenze

Die Confirmation-Stufe bestätigt gemeinsam:

- unabhängige Speicheridentität der Receipt-Bytes;
- kanonische semantische Gültigkeit des Receipts;
- unabhängige Speicheridentität der Planlauf-Bytes;
- bytegenaue Bindung des typisierten Planlaufs;
- Cross-Bindung von Plan, Planlauf und Solverresultat;
- eine frische vollständige Reproduktion unter den eingefrorenen
  Formationseingaben.

Auch dieses Ergebnis ist noch keine primitive Rewrite-Evidence. Der Solver hat
einen äquivalenten instanziierten Ausdruck gefunden, aber keine Folge
gewöhnlicher AST-Regelanwendungen erzeugt. Eine solche Folge nachträglich zu
erfinden würde falsche Provenienz erzeugen.

Der nächste ehrliche Schritt ist deshalb zunächst eine explizit ausgewählte,
verifier-eigene **nicht ausführbare** Transformationsevidence für genau einen
bestätigten Kandidaten:

```text
ConfirmedReplay
+ validierter Planlauf
+ expliziter Kandidatenhash
  -> Mitgliedschaft und sämtliche Links erneut prüfen
  -> Quelle und instanziierten Ausdruck binden
  -> vollständige Solver-/Replayarbeit binden
  -> non-executable VerifiedPlanCandidateEvidence
```

Erst ein späterer, work-aware Adapter darf daraus eine benannte exakte
Theorieoperation für die vorhandene Such- und `RewriteProgram`-Infrastruktur
machen. Formaler Beweis, primitive Regelhistorie, gelernte Ansatzgrammatik,
Promotion, Neuheit und Publikation bleiben getrennt.

## Reproduktion

```bash
./gradlew :regelsuche-learning:test \
  --tests '*ExactFinitePolynomialPlanReplayVerifierTest' \
  --tests '*ExactFinitePolynomialPlanReplayArtifactVerifierTest' \
  --tests '*ExactFinitePolynomialPlanReplayReceiptArtifactVerifierTest' \
  --tests '*ExactFinitePolynomialPlanRunArtifactVerifierTest' \
  --tests '*ExactFinitePolynomialPlanReplayConfirmationVerifierTest'

./gradlew --no-configuration-cache ciCheck
```
