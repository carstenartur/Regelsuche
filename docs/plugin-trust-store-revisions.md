# Authentisierte Plugin-Trust-State-Revisionen

Dieser Baustein führt den Trust-Teil von Issue #104 über die lokale
Artefaktprüfung hinaus. Er definiert eine signierte, hashverkettete Verteilung
von Publisher-Keys, Rotationszuständen und Artefaktwiderrufen, ohne daraus bereits
einen Netzwerkdienst oder Package Manager zu machen.

## Sicherheitsgrenze

Der bestehende `PluginTrustStore` bleibt das semantische Dokument, das von
`PluginArtifactVerifier` und `TrustedPluginRuntime` ausgewertet wird. Eine lose
`trust-store.json` besitzt jedoch keine Aussage darüber,

- wer diese Revision veröffentlicht hat;
- ob eine ältere Revision erneut eingespielt wurde;
- ob eine Zwischenrevision ausgelassen wurde;
- ob zwei widersprüchliche Nachfolger derselben Revision vorliegen.

`PluginTrustStoreRevision` und `PluginTrustStoreRevisionVerifier` ergänzen genau
diese Verteilungs- und Replay-Grenze. Sie ersetzen nicht die Prüfung einzelner
Plugin-Artefakte.

## Getrennte Root- und Arbeits-Trust-Stores

Der Verifier erhält zwei verschiedene Zustände:

1. einen **lokal gepinnten Root-Trust-Store**, der ausschließlich die zulässigen
   Trust-State-Authorities und ihre öffentlichen Ed25519-Keys enthält;
2. den **zu verteilenden Arbeits-Trust-Store**, der Publisher-Keys,
   Rotationsbeziehungen und Artefaktwiderrufe enthält.

Eine Authority wird nicht dadurch vertrauenswürdig, dass sie im neu geladenen
Arbeits-Trust-Store auftaucht. Autorisierung erfolgt ausschließlich aus dem lokal
gepinnten Root-Trust-Store.

## Signierte Revision

`regelsuche.plugin-trust-store-revision/v1` bindet:

- `trustDomainId` als stabile Vertrauensdomäne;
- eine strikt positive, monotone `sequence`;
- `previousRevisionHash` als Hash der unmittelbar vorherigen signierten Revision;
- `trustStoreContentHash` über die kanonischen UTF-8-Bytes des Arbeits-Trust-Stores;
- `authorityId`, `keyId` und `algorithm=Ed25519`;
- eine kanonisch gepaddete Base64-Signatur über exakt 64 Ed25519-Bytes;
- `contentHash` über das vollständige kanonische Revisionsmanifest.

Der signierte Payload verwendet für Namen und Werte jeweils 32-Bit-Längenpräfixe.
Damit entstehen keine mehrdeutigen Stringkonkatenationen.

### Genesis

Die erste akzeptierbare Revision besitzt:

```text
sequence = 1
previousRevisionHash = ""
```

Eine höhere Sequenz ohne lokalen Checkpoint wird als `GENESIS_REQUIRED`
abgewiesen. Ein Deployment darf daher nicht stillschweigend auf einer beliebigen
späteren Revision beginnen.

### Nachfolger

Für jede spätere Revision gilt:

```text
revision.sequence = checkpoint.sequence + 1
revision.previousRevisionHash = checkpoint.revisionHash
revision.trustDomainId = checkpoint.trustDomainId
```

Ältere oder gleiche Sequenzen werden als Replay abgewiesen. Sprünge erzeugen
`SEQUENCE_GAP`; abweichende Vorgänger erzeugen `PREVIOUS_HASH_MISMATCH` und
machen Forks sichtbar.

## Verifikation

`PluginTrustStoreRevisionVerifier.verify(...)` prüft in fail-closed Reihenfolge:

1. Hash des kanonischen Arbeits-Trust-Stores;
2. Genesis- oder Checkpoint-Kette;
3. Authority und Key im lokal gepinnten Root-Trust-Store;
4. Key-Status und Algorithmus;
5. Detached-Ed25519-Signatur.

Mögliche Statuswerte umfassen:

- `TRUST_STORE_HASH_MISMATCH`;
- `GENESIS_REQUIRED`;
- `TRUST_DOMAIN_MISMATCH`;
- `REPLAYED_REVISION`;
- `SEQUENCE_GAP`;
- `PREVIOUS_HASH_MISMATCH`;
- `UNKNOWN_AUTHORITY` und `UNKNOWN_KEY`;
- `REVOKED_KEY` und `INVALID_SIGNATURE`;
- `VERIFIED_RETIRED_KEY` und `VERIFIED_TRUSTED`.

Ein retired Authority-Key darf historische Revisionen weiter authentisieren,
erzeugt aber `RETIRED_TRUST_AUTHORITY_KEY`. Ein widerrufener Key autorisiert
keine Revision.

## Authority-bearing Ergebnis

`requireTrusted(...)` liefert ausschließlich nach erfolgreicher Signatur- und
Kettenprüfung einen privat konstruierbaren `VerifiedTrustStoreRevision`. Das
Ergebnis enthält:

- den akzeptierten Arbeits-Trust-Store;
- die signierte Revision;
- kanonische Verification Evidence;
- den nächsten lokal zu persistierenden Checkpoint.

Consumer sollen den frei konstruierbaren Eingabeobjekten keine Autorität
zuschreiben, sondern nur diesem Ergebnis.

## Checkpoint und Rollback-Schutz

`regelsuche.plugin-trust-store-chain-checkpoint/v1` bindet:

- Trust-Domain;
- zuletzt akzeptierte Sequenz;
- Hash der zuletzt akzeptierten signierten Revision;
- einen vollständigen Checkpoint-Hash.

Der Checkpoint verhindert Replay nur, solange ein Angreifer ihn nicht gemeinsam
mit dem Arbeits-Trust-Store auf einen älteren Zustand zurückrollen kann. Eine
produktive Installation muss ihn deshalb in einem rollback-geschützten lokalen
State halten, beispielsweise in einem transaktionalen System-State mit
monotonem Versionszähler, hardwaregestütztem Counter oder extern attestierter
Konfiguration. Eine normale kopierbare Datei allein schützt nicht gegen
vollständigen lokalen State-Rollback.

Der Java-Vertrag entscheidet bewusst nicht, welches Persistenzsystem ein
Deployment verwendet. Er gibt aber den exakt zu speichernden und beim nächsten
Update wieder vorzulegenden Zustand vor.

## Verification Evidence

`regelsuche.plugin-trust-store-revision-verification/v1` bindet:

- Trust-Domain, Sequenz und Revisionshash;
- Vorgängerhash;
- Arbeits- und Root-Trust-Store-Hashes;
- Hash des vorherigen Checkpoints;
- Signatur-, Trust- und Replay-Safe-Flags;
- Authority, Key, Warnungen und Status;
- einen vollständigen Evidence-Hash.

Nur `VERIFIED_TRUSTED` und `VERIFIED_RETIRED_KEY` dürfen gleichzeitig
`signatureVerified=true`, `trusted=true` und `replaySafe=true` tragen.

## Reproduzierbarkeit

Der Workflow `Plugin Trust Store Revision` erzeugt zwei aufeinanderfolgende
Referenzrevisionen:

1. Genesis mit einem aktiven Publisher-Key;
2. Nachfolger mit Key-Rotation und einem Artefaktwiderruf.

CI verlangt byte-identische Wiederholungen, validiert alle Schemas und berechnet
unabhängig:

- Trust-Store-Hashes;
- beide längenpräfixierten Signaturpayloads;
- Ed25519-Signaturen mit OpenSSL;
- Revisions-, Verification- und Checkpoint-Hashes;
- Sequenz-, Vorgänger- und Checkpoint-Bindungen.

Lokale Reproduktion:

```bash
./gradlew :app:test \
  --tests de.regelsuche.plugin.PluginTrustStoreRevisionTest
```

Schemas:

- `docs/schemas/regelsuche-plugin-trust-store-revision-v1.schema.json`
- `docs/schemas/regelsuche-plugin-trust-store-chain-checkpoint-v1.schema.json`
- `docs/schemas/regelsuche-plugin-trust-store-revision-verification-v1.schema.json`

## Verbleibender Umfang von #104

Dieser Vertrag authentisiert Trust-State-Revisionen, führt aber selbst keinen
Netzwerkzugriff aus. Weiterhin offen bleiben insbesondere:

- gehosteter oder föderierter Transport signierter Index- und Trust-State-Revisionen;
- exakte Downloads der im Index gebundenen Artefaktbytes;
- Installation, Update, Entfernung und Rollback;
- Source-to-binary-Provenance-Attestierungen;
- separat baubare Community-Beispielprojekte und Publishing-Dokumentation;
- ein optionales Transparenzprotokoll gegen Equivocation zwischen verschiedenen
  Clients.
