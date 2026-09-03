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
Speichergrenze. Ein Receipt wird zunächst durch eine versionierte
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
Artifact-ID.

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

## Präzise Vertrauensgrenze

Die semantische Stufe bestätigt, dass unabhängig geladene Bytes genau ein
kanonisches, intern konsistentes Receipt der aktuellen Revision darstellen. Sie
bestätigt noch nicht, dass ein separat gespeicherter Planlauf geladen wurde oder
dass dieser Planlauf unter den gebundenen Formationseingaben erneut ausführbar
ist.

Der nächste stärkere Schritt lautet daher:

```text
VerifiedReplayReceiptArtifact
+ unabhängig geladener kanonischer Planlauf
+ eingefrorener Plan, Quelle, Ansatz, Domänen und Limits
  -> Bindung an receipt.planRunHash
  -> vollständige Resolver-/Solverausführung
  -> neues identisches Replay-Receipt
```

Auch dieses Ergebnis bleibt von primitiver Rewrite-Evidence, einem ausführbaren
`RewriteProgram`, formaler Proof-Evidence und Promotion-Autorität getrennt.

## Reproduktion

```bash
./gradlew :regelsuche-learning:test \
  --tests '*ExactFinitePolynomialPlanReplayVerifierTest' \
  --tests '*ExactFinitePolynomialPlanReplayArtifactVerifierTest' \
  --tests '*ExactFinitePolynomialPlanReplayReceiptArtifactVerifierTest'

./gradlew --no-configuration-cache ciCheck
```
