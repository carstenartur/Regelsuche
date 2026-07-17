# Unveränderlicher Plugin- und Paketindex

Dieser Baustein führt den Katalog-Teil von Issue #104 weiter. Er definiert eine
lokal reproduzierbare Index-, Signatur- und Auflösungssemantik für veröffentlichte
Erweiterungsartefakte. Netzwerkzugriff, Installation, Update, Entfernung und
Rollback bleiben getrennte Folgeschritte.

## Sicherheits- und Verantwortungsgrenze

Der `contentHash` eines Indexes bestätigt Integrität und Reproduzierbarkeit, aber
noch keine Herkunft. Eine vertrauenswürdige Indexrevision benötigt zusätzlich:

1. ein Detached-Ed25519-Signaturmanifest;
2. dieselbe `indexId`, `revision`, denselben `indexContentHash` und `curatorId`
   in Index und Signaturpayload;
3. einen passenden Curator-Key im bestehenden Plugin-Trust-Store;
4. einen nicht widerrufenen Key und eine gültige Signatur.

`PluginArtifactIndexVerifier.requireTrusted(...)` liefert den Index nur zusammen
mit einer vertrauenswürdigen Verification Evidence zurück. Unbekannte Curators,
unbekannte oder widerrufene Keys, Identitätsabweichungen und ungültige
Signaturen werden fail-closed abgewiesen. Retired Keys bleiben wie bei bereits
veröffentlichten Plugin-Artefakten für historische Signaturen verifizierbar und
erzeugen eine sichtbare Warnung.

Der lokale Trust-Store wird dabei wiederverwendet; es entsteht keine zweite
Schlüsselverwaltung. Dieser Vertrag verteilt jedoch weder Index, Keys noch
Revocation-Informationen und erzeugt kein Transparenzprotokoll.

Für Java-Plugins gilt weiterhin:

1. Ein vertrauenswürdig geladener Index oder ein bewusst untrusted verwendeter
   lokaler Index liefert die unveränderlichen Katalogdaten.
2. Der Resolver erzeugt einen content-addressed Plan.
3. Ein späterer Downloader muss exakt den im Plan gebundenen SHA-256 erhalten.
4. Vor dem Classloading muss das Artefakt zusätzlich durch
   `TrustedPluginRuntime` und den Publisher-Trust-Store geprüft werden.

Eine gültige Indexsignatur ersetzt keine Signaturprüfung der heruntergeladenen
Plugin-JARs. `networkAccessStatus=NOT_PERFORMED`,
`installationStatus=NOT_PERFORMED` und `trustVerificationStatus=NOT_EVALUATED`
im Resolution Receipt verhindern weiterhin, dass eine reine Katalogauflösung als
Download, Installation oder Artefakt-Signaturprüfung dargestellt wird. Die
Index-Trust-Entscheidung ist eine getrennte Evidence.

## Index v1

`regelsuche.plugin-artifact-index/v1` unterscheidet drei Artefaktarten:

- `JAVA_PLUGIN` für `ServiceLoader`-JARs;
- `RULE_PACKAGE` für `.regelsuche`- und `.rules`-Pakete;
- `KNOWLEDGE_PACK` für kuratierte Datenpakete.

Jeder Eintrag bindet:

- eindeutige Artefakt-ID;
- Art, Komponenten-ID und SemVer;
- Plugin-API- und Core-Kompatibilitätsgrenzen;
- deklarierte Capabilities;
- erforderliche und optionale Abhängigkeiten;
- einfachen Dateinamen und SHA-256 der Binärbytes;
- HTTPS- oder absolute lokale `file:`-URI;
- Detached-Signaturmanifest-URI für Java-Plugins;
- Source-/Release-Provenance-URI;
- Publisher-ID;
- einen `identityHash` über alle vorgenannten Felder.

Der vollständige Index besitzt zusätzlich einen `contentHash`. Ein Eintrag kann
nicht unter zwei Koordinaten denselben Byte-Hash wiederverwenden. Doppelte
Artefakt-IDs, doppelte Art/Komponente/Version-Koordinaten und fehlende
Pflichtabhängigkeiten werden abgelehnt.

## Signierte Indexrevision v1

`regelsuche.plugin-artifact-index-signature/v1` ist eine Detached-Signatur über
einen längenpräfixierten Payload aus:

- Signaturschema;
- `indexId`;
- `revision`;
- `indexContentHash`;
- `curatorId`;
- `keyId`;
- Algorithmus `Ed25519`.

Die Längenpräfixe verhindern mehrdeutige Stringkonkatenationen. Die Signatur
bindet bewusst semantische Indexidentitäten und nicht einen veränderlichen
Transportpfad. Der strikte Indexparser validiert den gebundenen Content-Hash,
bevor der Trust-Verifier die Herkunft prüft.

`regelsuche.plugin-artifact-index-verification/v1` bindet anschließend:

- Index-ID, Revision und Index-Hash;
- Hash des kanonischen Signaturmanifests;
- Hash des kanonischen Trust-Stores;
- Status, Trust-Flags, Curator und Key;
- Warnungen sowie einen vollständigen Evidence-Hash.

Nur `VERIFIED_TRUSTED` und `VERIFIED_RETIRED_KEY` dürfen
`signatureVerified=true` und `trusted=true` tragen.

## Versionssemantik

Versionen verwenden SemVer 2.0:

- Releaseversionen stehen nach ihren Prereleases;
- numerische Prerelease-Teile werden numerisch verglichen;
- numerische Prerelease-Teile mit führenden Nullen sind ungültig;
- Build-Metadaten verändern die Präzedenz nicht;
- numerische Komponenten sind nicht auf 32-Bit-Grenzen beschränkt.

Abhängigkeiten verwenden in v1 bewusst nur:

- `any`; oder
- eine exakte Version `=x.y.z`.

Diese Begrenzung hält Auflösung, Replay und Konfliktdiagnosen eindeutig. Weitere
Version-Constraint-Sprachen benötigen eine neue Vertragsrevision.

## Deterministische Auflösung

`regelsuche.plugin-artifact-resolution-request/v1` unterstützt:

- `EXACT` mit einer festgelegten Version;
- `LATEST_COMPATIBLE` ohne versteckte Floating-Version außerhalb des gebundenen
  Indexes.

Die Auswahl filtert in fester Reihenfolge nach:

1. Art und Komponenten-ID;
2. optional exakter Version;
3. Core-Kompatibilität;
4. API-Version;
5. geforderten Capabilities.

Pflichtabhängigkeiten werden vor ihren Konsumenten in den Plan geschrieben.
Optionale, nicht verfügbare Abhängigkeiten bleiben als Warnung sichtbar.
Widersprüchliche transitive Versionsanforderungen erzeugen einen Blocker; ein
unaufgelöster Receipt enthält keinen teilweise ausführbaren Installationsplan.

## Auflösungs-Receipt

`regelsuche.plugin-artifact-resolution/v1` bindet:

- den vollständigen Index-Hash;
- den gehashten Request;
- Status `RESOLVED` oder `UNRESOLVED`;
- bei Erfolg die Root-Identität und den topologisch geordneten Plan;
- Blocker und Warnungen;
- die expliziten Nicht-Ausführungsstatus für Netzwerk, Installation und
  Artefakt-Trust;
- einen vollständigen Receipt-Hash.

Jeder Planschritt wiederholt absichtlich Byte-Hash, Distribution, Publisher,
Signaturmanifest und Provenance. Ein späterer Installationsprozess muss damit
arbeiten und darf diese Identitäten nicht erneut aus einem veränderlichen
Katalog ableiten.

## Gespeicherte Referenzevidence

Der Workflow `Plugin Artifact Index` erzeugt:

- `index.json`;
- `index-signature.json`;
- `index-trust-store.json`;
- `index-verification.json`;
- `resolved.json` für eine erfolgreiche Latest-Compatible-Auflösung;
- `unresolved.json` für eine explizit inkompatible Exact-Auflösung.

CI verlangt byte-identische Wiederholungen, validiert alle Draft-2020-12-Schemas
und berechnet unabhängig:

- alle Entry-Identitätshashes;
- den vollständigen Index-Hash;
- den Ed25519-Signaturpayload und die Signatur mit OpenSSL;
- Signaturmanifest-, Trust-Store- und Verification-Hashes;
- Request- und Receipt-Hashes;
- Planreihenfolge und Root-Bindung.

## Offener Umfang von #104

Dieser Slice stellt noch keinen öffentlichen Dienst und keinen Package Manager
dar. Offen bleiben insbesondere:

- gehosteter oder föderierter Transport signierter Indexrevisionen;
- authentisierte Verteilung und Rotation von Curator-/Publisher-Keys;
- authentisierter Revocation-Feed und optionales Transparenzprotokoll;
- Download exakt der gebundenen Bytes mit Hashprüfung;
- Installation, Update, Entfernung und Rollback;
- Source-to-binary-Provenance-Attestierungen;
- eigenständig veröffentlichte Beispielprojekte und Community-Einreichungen.

Schemas:

- `docs/schemas/regelsuche-plugin-artifact-index-v1.schema.json`
- `docs/schemas/regelsuche-plugin-artifact-index-signature-v1.schema.json`
- `docs/schemas/regelsuche-plugin-artifact-index-verification-v1.schema.json`
- `docs/schemas/regelsuche-plugin-artifact-resolution-v1.schema.json`
