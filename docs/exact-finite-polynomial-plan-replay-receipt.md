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
Speichergrenze. Ein Receipt oder Planlauf wird zunächst durch eine versionierte
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
Artifact-ID. Die Rollen `replay-receipt` und `plan-run` sind getrennt und
können nicht gegeneinander eingesetzt werden.

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

## Kanonischer Planlauf

`ExactFinitePolynomialPlanRunArtifactCodec` erzeugt ausschließlich aus einem
bereits validierten `ExactFinitePolynomialPlanRun` eine kanonische kompakte
Projektion. Sie enthält:

- Codec-, Resolver- und Solverrevision;
- Plan-, Planlauf- und Solverresultat-Hash;
- normalisierte Quelle und Ansatzschablone;
- alle typisierten endlichen Hole-Domänen und exakten Werte;
- Belegungszähler, Retained-Limit und beide Laufstatus;
- jede gespeicherte Lösung mit typisierten Bindungen, Ausdruck und Normalform;
- jeden Kandidaten mit Lösung-, Resolution-, Plan- und Solverresultat-Bindung;
- einen eigenen Hash über dieselbe Darstellung ohne Selbst-Hash.

Diese Bytes erhalten an der Speichergrenze noch keine semantische Autorität.
Auch ein kompakter, aber falscher JSON-Gegenstand kann unter seiner eigenen
Adresse als unveränderte Bytefolge bestätigt werden.

## Unabhängige Replay-Bestätigung

`ExactFinitePolynomialPlanReplayConfirmationVerifier` verbindet erst danach die
beiden unabhängigen Artefakte mit den eingefrorenen Formationseingaben:

```text
VerifiedReplayReceiptArtifact
+ VerifiedArtifactBytes(role = plan-run)
+ Plan, Quelle, Ansatz, Domänen und Retained-Limit
  -> erste vollständige Resolver-/Solverausführung
  -> Receipt-/Planlauf-/Solver-/Status-/Zähler-/Kandidaten-Bindung
  -> kanonischen Planlauf vollständig neu rendern
  -> exakte Byte- und ArtifactReference-Gleichheit
  -> zweite vollständige Ausführung durch den Replay-Verifier
  -> identisches kanonisches Replay-Receipt
  -> VerifiedReplayConfirmation
```

Die erste Ausführung rekonstruiert die unabhängig gespeicherten Planlauf-Bytes.
Die zweite Ausführung verlangt über den bestehenden Replay-Verifier
Objektgleichheit des gesamten Planlaufs und reproduziert das bereits semantisch
geprüfte Receipt. Eingabe-, Receipt-, Rollen-, Byte- oder Laufsubstitution wird
fail-closed abgelehnt.

`VerifiedReplayConfirmation` ist wieder eine versiegelte öffentliche
Nur-Lese-Schnittstelle mit genau einer privaten Implementierung. Ihre
versionierte Identität bindet beide Artifact-Referenzen, beide
Verifikationsidentitäten, den rekonstruierten Planlauf und das erneut erzeugte
Receipt.

## Präzise Vertrauensgrenze

Die Bestätigung weist nach, dass die unabhängig geladenen Planlauf-Bytes unter
den eingefrorenen Eingaben durch zwei vollständige aktuelle Ausführungen
reproduziert werden und mit dem semantisch geprüften Receipt übereinstimmen.

Sie weist noch nicht nach, dass die resultierende Transformation als Folge
einzelner primitiver Rewrite-Anwendungen unabhängig ausgeführt wurde. Der
nächste stärkere Schritt muss deshalb Regel-ID, AST-Position, Vorher-/Nachher-
Ausdruck, Annahmen und Work-Evidence jeder primitiven Anwendung laden und
replayen, bevor ein vollständig geprüfter Plan in ein ausführbares
`RewriteProgram` kompiliert werden darf.

Auch die Replay-Bestätigung bleibt von formaler Proof-Evidence, autonomer
Ansatzgrammatik, externer Neuheit und Promotion-Autorität getrennt.

## Reproduktion

```bash
./gradlew :regelsuche-learning:test \
  --tests '*ExactFinitePolynomialPlanReplayVerifierTest' \
  --tests '*ExactFinitePolynomialPlanReplayArtifactVerifierTest' \
  --tests '*ExactFinitePolynomialPlanReplayReceiptArtifactVerifierTest' \
  --tests '*ExactFinitePolynomialPlanReplayConfirmationVerifierTest'

./gradlew --no-configuration-cache ciCheck
```
