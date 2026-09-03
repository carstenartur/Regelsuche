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

Der Verifier fordert die Bytes anschließend ausschließlich unter der erwarteten
Artifact-ID von einer minimalen `ArtifactSource` an. Er vertraut weder dem
zurückgegebenen Schlüssel noch einem fremden Längen- oder Hashwert, sondern:

1. vergleicht den zurückgegebenen Schlüssel mit der angeforderten ID;
2. erzeugt eine defensive Byte-Kopie;
3. erzwingt die feste Grenze von 1.000.000 Bytes;
4. dekodiert UTF-8 mit Fehlerbehandlung `REPORT`;
5. lehnt UTF-8-BOM, äußere Leerzeichen und Nicht-Objekt-Payloads ab;
6. berechnet Byte-Länge und SHA-256 selbst;
7. berechnet die Artifact-ID aus Rolle, Schema, Medientyp, Hash und Länge neu;
8. stellt nur bei vollständiger Übereinstimmung `VerifiedArtifactBytes` aus.

`VerifiedArtifactBytes` ist wiederum eine versiegelte Schnittstelle mit einer
einzigen privaten Implementierung. Alle Byte-Zugriffe liefern Kopien, sodass
weder ein Store noch ein späterer Aufrufer den bestätigten Snapshot verändern
kann. Identische Bytes unter einer anderen Rolle oder einem anderen
Inhaltsschema besitzen eine andere Artifact-ID.

## Präzise Vertrauensgrenze

Die Byte-Stufe prüft **noch nicht**, ob ein kompakter JSON-Blob semantisch ein
gültiges Replay-Receipt ist. Selbst korrekt adressierte Bytes können in diesem
Slice nur als unveränderter, metadata-gebundener Snapshot gelten. Ein späterer
schema-spezifischer Verifier muss unter anderem:

```text
VerifiedArtifactBytes
  -> kanonische Receipt-Felder parsen
  -> Receipt-Content-Hash neu berechnen
  -> Planlauf-Referenz binden
  -> separat gespeicherten Planlauf laden
  -> Resolver und Solver unter eingefrorener Runtime erneut ausführen
```

Erst danach wäre eine stärkere reproduzierte Evidence-Aussage möglich. Auch sie
wäre noch von primitiver Rewrite-Evidence, einem ausführbaren
`RewriteProgram`, formaler Proof-Evidence und Promotion-Autorität zu trennen.

## Reproduktion

```bash
./gradlew :regelsuche-learning:test \
  --tests '*ExactFinitePolynomialPlanReplayVerifierTest' \
  --tests '*ExactFinitePolynomialPlanReplayArtifactVerifierTest'

./gradlew --no-configuration-cache ciCheck
```
