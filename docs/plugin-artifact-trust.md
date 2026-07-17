# Kryptografische Plugin-Artefaktprüfung

Dieser Baustein adressiert den Trust-Teil von Issue #104. Er prüft externe
Plugin-JARs **vor** dem Laden von Bytecode. Der öffentliche Index, Installation,
Updates, Rollback und separat veröffentlichte Beispielprojekte bleiben eigene
Folgeschritte.

## Sicherheitsgrenze

`PluginRuntime` bleibt aus Kompatibilitätsgründen unverändert verfügbar.
Sicherheitskritische Installationen öffnen Plugins stattdessen über
`TrustedPluginRuntime`:

```java
PluginRuntimeConfig runtimeConfig = new PluginRuntimeConfig(
    Path.of("plugins"),
    Path.of("rules"),
    true,
    Set.of(),
    Set.of()
);
PluginArtifactTrustConfig trustConfig = new PluginArtifactTrustConfig(
    Path.of("plugins", "trust-store.json"),
    PluginTrustPolicy.REQUIRE_VERIFIED
);
try (TrustedPluginRuntime runtime =
         TrustedPluginRuntime.open(runtimeConfig, trustConfig)) {
    PluginRuntime verifiedRuntime = runtime.runtime();
    PluginArtifactGate.GateResult evidence = runtime.gateResult();
}
```

Das Gate liest jedes JAR genau einmal. Geprüft und anschließend in ein privates
Staging-Verzeichnis geschrieben werden dieselben Bytes. Dadurch entsteht kein
Zeitfenster, in dem ein Artefakt nach der Prüfung gegen andere Bytes
ausgetauscht werden kann.

Die Methode `RegelsuchePlugin.signature()` ist nur ein nach der Instanziierung
sichtbarer Metadatenhinweis. Sie ist keine Sicherheitsgrenze: Ein Plugin dürfte
seinen Rückgabewert selbst wählen und der Code müsste bereits geladen werden,
um ihn abzufragen. Autorisierung erfolgt deshalb ausschließlich über das
Detached Manifest und den lokalen Trust Store.

## Policies

- `WARN` erhält das historische permissive Verhalten. Jedes lesbare JAR wird
  zugelassen; fehlende oder ungültige Signaturen bleiben als strukturierte
  Verification-Evidence sichtbar.
- `REQUIRE_VERIFIED` lässt ausschließlich JARs mit gültiger Ed25519-Signatur,
  bekanntem Publisher-Key und ohne Widerruf in den ClassLoader.

Nicht lesbare Dateien werden unter beiden Policies blockiert.

## Detached Signature Manifest

Neben `example.jar` liegt `example.jar.sig.json`:

```json
{
  "schema": "regelsuche.plugin-signature/v1",
  "artifactFileName": "example.jar",
  "artifactSha256": "sha256:…",
  "publisherId": "org.example.publisher",
  "keyId": "release-2026",
  "algorithm": "Ed25519",
  "signatureBase64": "…"
}
```

Die Signatur bindet alle Identitätsfelder außer `signatureBase64`. Die
Binärdarstellung ist längenpräfixiert; Zeilenumbrüche oder eingebettete
Feldnamen können deshalb keine Mehrdeutigkeit erzeugen. Publisher-Werkzeuge
verwenden genau:

```java
byte[] payload = PluginSignatureManifest.signedPayload(
    artifactFileName,
    artifactSha256,
    publisherId,
    keyId,
    PluginSignatureManifest.ALGORITHM
);
```

Der private Schlüssel gehört ausschließlich in die Release-Infrastruktur und
niemals in Repository, Manifest, Plugin-JAR oder Trust Store. Der Trust Store
enthält nur den X.509-kodierten öffentlichen Ed25519-Schlüssel.

## Trust Store, Rotation und Widerruf

Beispiel `plugins/trust-store.json`:

```json
{
  "schema": "regelsuche.plugin-trust-store/v1",
  "keys": [
    {
      "publisherId": "org.example.publisher",
      "keyId": "release-2026",
      "algorithm": "Ed25519",
      "publicKeyBase64": "…",
      "status": "RETIRED",
      "successorKeyId": "release-2027"
    },
    {
      "publisherId": "org.example.publisher",
      "keyId": "release-2027",
      "algorithm": "Ed25519",
      "publicKeyBase64": "…",
      "status": "ACTIVE",
      "successorKeyId": ""
    }
  ],
  "revokedArtifacts": [
    {
      "artifactSha256": "sha256:…",
      "reason": "publisher incident response"
    }
  ]
}
```

Schlüsselstatus:

- `ACTIVE`: Signaturen sind vertrauenswürdig.
- `RETIRED`: Bereits veröffentlichte Signaturen bleiben prüfbar, erzeugen aber
  `RETIRED_PUBLISHER_KEY`. `successorKeyId` dokumentiert die Rotation.
- `REVOKED`: Keine Signatur dieses Schlüssels autorisiert Codeausführung.

Ein Artefaktwiderruf gewinnt immer gegen eine ansonsten gültige Signatur. Ein
unbekannter Publisher oder Key ist keine implizite Vertrauenskette.

### Authentisierte Verteilung des Trust States

Der lokale Trust Store darf nicht unbemerkt durch eine ältere oder fremde Datei
ersetzt werden. Der ergänzende Vertrag
[Authentisierte Plugin-Trust-State-Revisionen](plugin-trust-store-revisions.md)
bindet deshalb jede kanonische Trust-Store-Version an eine Ed25519-signierte,
monotone Hashkette und einen lokalen letzten Checkpoint.

Die Authority-Keys stammen aus einem getrennten, lokal gepinnten Root-Trust-Store.
Ein Key wird nicht dadurch zur Authority, dass er im neu verteilten Arbeits-Trust-
Store enthalten ist. Replay-, Gap-, Fork- und Trust-Domain-Abweichungen werden
fail-closed abgewiesen. Der lokale Checkpoint muss gegen Rollback geschützt
persistiert werden; eine normale kopierbare Datei allein kann keinen vollständigen
lokalen State-Rollback verhindern.

## Verification-Evidence

Für jedes gefundene JAR entsteht
`regelsuche.plugin-artifact-verification/v1` mit:

- Artefakt- und Manifestname;
- SHA-256 des tatsächlich gelesenen JAR-Snapshots;
- Publisher und Key;
- Signatur-/Trust-Flags;
- einem expliziten Status wie `VERIFIED_TRUSTED`,
  `ARTIFACT_HASH_MISMATCH`, `REVOKED_KEY` oder
  `MISSING_SIGNATURE_MANIFEST`;
- stabil sortierten Warnungen;
- einem `contentHash`, der die komplette Verification-Evidence bindet.

`PluginArtifactGate.GateResult` hält zusätzlich die vollständig bilanzierten
Listen `admittedArtifacts` und `blockedArtifacts`. Jede Verification muss genau
in einer dieser Listen erscheinen. Der kanonische Gate-Report verwendet
`regelsuche.plugin-artifact-gate/v1`; CI validiert die vollständige Bilanz und
die referenzierte Einzelverifikation.

Die Felder `signatureVerified` und `trustedSource` im historischen
`PluginRuntime`-Katalog bleiben aus Kompatibilitätsgründen bestehen. Für den
Vorlade-Trust ist jedoch `TrustedPluginRuntime.gateResult()` die maßgebliche,
auf Artefaktbytes bezogene Evidence.

## Fail-closed Verhalten

Unter `REQUIRE_VERIFIED` werden insbesondere blockiert:

- fehlende oder nicht parsebare Sidecars;
- abweichender Dateiname oder Content-Hash;
- unbekannte Publisher oder Keys;
- widerrufene Keys oder Artefakte;
- ungültige Ed25519-Signaturen;
- Symlinks und nicht reguläre Dateien;
- nicht lesbare Artefakte.

Ein fehlerhafter Trust Store verhindert den Aufbau von `TrustedPluginRuntime`.
Er wird nicht stillschweigend durch eine leere oder permissive Konfiguration
ersetzt.

## Versionierte Schemas

- `docs/schemas/regelsuche-plugin-signature-v1.schema.json`
- `docs/schemas/regelsuche-plugin-trust-store-v1.schema.json`
- `docs/schemas/regelsuche-plugin-trust-store-revision-v1.schema.json`
- `docs/schemas/regelsuche-plugin-trust-store-chain-checkpoint-v1.schema.json`
- `docs/schemas/regelsuche-plugin-trust-store-revision-verification-v1.schema.json`
- `docs/schemas/regelsuche-plugin-artifact-verification-v1.schema.json`
- `docs/schemas/regelsuche-plugin-artifact-gate-v1.schema.json`

Die Revision- und Checkpoint-Parser sind zusätzlich strikt gegenüber
unbekannten JSON-Feldern, doppelten Feldern und nachgestellten JSON-Werten.
Die älteren Trust-Store- und Artefaktmanifest-Parser behalten ihre jeweils
dokumentierten Verträge und werden hier nicht pauschal als gleich streng
klassifiziert.
