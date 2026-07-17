# Unveränderlicher Plugin- und Paketindex

Dieser Baustein führt den Katalog-Teil von Issue #104 weiter. Er definiert eine
lokal reproduzierbare Index- und Auflösungssemantik für veröffentlichte
Erweiterungsartefakte. Netzwerkzugriff, Installation, Update, Entfernung und
Rollback bleiben getrennte Folgeschritte.

## Sicherheits- und Verantwortungsgrenze

Der Index ist keine Trust-Entscheidung. Er beschreibt, **welche unveränderlichen
Bytes** unter welcher Komponenten- und Versionsidentität veröffentlicht wurden.

Für Java-Plugins gilt weiterhin:

1. Der Resolver erzeugt einen content-addressed Plan.
2. Ein späterer Downloader muss exakt den im Plan gebundenen SHA-256 erhalten.
3. Vor dem Classloading muss das Artefakt zusätzlich durch
   `TrustedPluginRuntime` und den Publisher-Trust-Store geprüft werden.

`networkAccessStatus=NOT_PERFORMED`, `installationStatus=NOT_PERFORMED` und
`trustVerificationStatus=NOT_EVALUATED` verhindern, dass eine reine
Katalogauflösung als Download, Installation oder Signaturprüfung dargestellt
wird.

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
- HTTPS- oder absolute `file:`-URI;
- Detached-Signaturmanifest-URI für Java-Plugins;
- Source-/Release-Provenance-URI;
- Publisher-ID;
- einen `identityHash` über alle vorgenannten Felder.

Der vollständige Index besitzt zusätzlich einen `contentHash`. Ein Eintrag kann
nicht unter zwei Koordinaten denselben Byte-Hash wiederverwenden. Doppelte
Artefakt-IDs, doppelte Art/Komponente/Version-Koordinaten und fehlende
Pflichtabhängigkeiten werden abgelehnt.

## Versionssemantik

Versionen verwenden SemVer 2.0:

- Releaseversionen stehen nach ihren Prereleases;
- numerische Prerelease-Teile werden numerisch verglichen;
- numerische Prerelease-Teile mit führenden Nullen sind ungültig;
- Build-Metadaten verändern die Präzedenz nicht.

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
- die expliziten Nicht-Ausführungsstatus für Netzwerk, Installation und Trust;
- einen vollständigen Receipt-Hash.

Jeder Planschritt wiederholt absichtlich Byte-Hash, Distribution, Publisher,
Signaturmanifest und Provenance. Ein späterer Installationsprozess muss damit
arbeiten und darf diese Identitäten nicht erneut aus einem veränderlichen
Katalog ableiten.

## Gespeicherte Referenzevidence

Der Workflow `Plugin Artifact Index` erzeugt:

- `index.json`;
- `resolved.json` für eine erfolgreiche Latest-Compatible-Auflösung;
- `unresolved.json` für eine explizit inkompatible Exact-Auflösung.

CI verlangt byte-identische Wiederholungen, validiert beide Draft-2020-12-
Schemas und berechnet unabhängig:

- alle Entry-Identitätshashes;
- den vollständigen Index-Hash;
- Request-Hashes;
- Receipt-Hashes;
- Planreihenfolge und Root-Bindung.

## Offener Umfang von #104

Dieser Slice stellt noch keinen öffentlichen Dienst und keinen Package Manager
dar. Offen bleiben insbesondere:

- gehosteter oder föderierter Indextransport;
- Signatur bzw. Transparenzprotokoll für Indexrevisionen;
- Download mit Hashprüfung;
- Installation, Update, Entfernung und Rollback;
- Revocation-Feed und Publisher-Key-Verteilung;
- Source-to-binary-Provenance-Attestierungen;
- eigenständig veröffentlichte Beispielprojekte und Community-Einreichungen.

Schemas:

- `docs/schemas/regelsuche-plugin-artifact-index-v1.schema.json`
- `docs/schemas/regelsuche-plugin-artifact-resolution-v1.schema.json`
