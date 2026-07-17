# Verifikation generischer Domain-Discovery-Exporte

Dieser Baustein setzt auf dem manifestgebundenen
[Domain-Discovery-Export](domain-discovery-export.md) auf. Er macht einen
persistierten Export sicher lesbar, ohne seine mathematische Bedeutung zu
erweitern.

## Bedrohungsmodell

Ein Exportverzeichnis kann unvollständig, veraltet, manipuliert oder während des
Lesens verändert worden sein. Außerdem können symbolische Links oder unerwartete
Dateien dazu führen, dass ein Consumer andere Bytes verarbeitet als diejenigen,
die im Manifest beschrieben sind.

`DomainDiscoveryExportVerifier` behandelt deshalb keinen Pfad allein als
vertrauenswürdigen Input. Ein erfolgreicher Aufruf liefert ausschließlich einen
privat konstruierbaren `VerifiedDomainExport` mit defensiven Byte-Snapshots.
Nachgelagerter Code muss die Dateien nicht erneut öffnen und vermeidet damit eine
Verify-then-read-Lücke.

## Fail-closed Prüfungen

`requireVerified(exportDirectory)` verlangt:

1. ein lokales Verzeichnis, das selbst kein symbolischer Link ist;
2. genau `export-manifest.json` und die drei kanonischen Artefakte;
3. keine unbekannten Dateien, Unterverzeichnisse oder symbolischen Links;
4. positive, konfigurierbar begrenzte Dateigrößen;
5. Lesen über `NOFOLLOW_LINKS` in genau einen unveränderlichen Byte-Snapshot;
6. exakte Byte-Länge und SHA-256 aus dem Manifest;
7. ein identisches Manifest vor und nach dem Lesen aller Artefakte;
8. striktes JSON einschließlich Duplicate-Field-Erkennung;
9. die exakten Top-Level-Verträge für Descriptor, Discovery Evidence und
   Lifecycle Handoff;
10. vollständige Campaign-, Domain-, Revision-, Descriptor-, Seed-, Evidence-
    und Handoff-Bindungen.

Ein fehlendes oder nachträglich verändertes Manifest, zusätzliche Dateien,
Symlinks, Größenüberschreitungen, Hashabweichungen, Identitätssubstitutionen und
strukturell unbekannte Felder werden abgewiesen.

Die Standardgrenzen betragen:

```text
Manifest:  1 MiB
Artefakt: 64 MiB je Datei
```

Beide Werte können für einen Consumer enger konfiguriert werden, bleiben aber auf
`Integer.MAX_VALUE` begrenzt, da das authority-bearing Ergebnis exakte
Byte-Snapshots hält.

## Cross-Document-Bindungen

Der Verifier verlangt insbesondere:

```text
descriptor.domainId                  = manifest.domainId
descriptor.revision                  = manifest.domainRevision
descriptor.contentHash               = manifest.domainDescriptorHash

evidence.campaignId                  = manifest.campaignId
evidence.domain                      = descriptor
evidence.contentHash                 = manifest.discoveryEvidenceHash
evidence.seed.domainId               = manifest.domainId

handoff.sourceKind                   = DOMAIN_DISCOVERY_EVIDENCE
handoff.stage                        = DISCOVERY_VALIDATION
handoff.campaignId                   = manifest.campaignId
handoff.domainId                     = manifest.domainId
handoff.domainRevision               = manifest.domainRevision
handoff.domainContractHash           = descriptor.contentHash
handoff.inputHash                    = evidence.seed.contentHash
handoff.sourceEvidenceHash           = evidence.contentHash
handoff.contentHash                  = manifest.lifecycleHandoffHash
```

Descriptor und eingebetteter Evidence-Descriptor müssen als JSON-Struktur exakt
übereinstimmen. Dadurch kann keine andere Domainbeschreibung unter denselben
äußeren Manifestwurzeln eingeschoben werden.

## Verifikations-Receipt

`regelsuche.domain-discovery-export-verification/v1` bindet:

- den semantischen Manifest-Hash;
- SHA-256 der exakten Manifestbytes;
- Campaign, Domain und Revision;
- Descriptor-, Evidence- und Handoff-Root-Hashes;
- einen kanonischen Hash über Rollen, Byte-Hashes und Byte-Längen aller drei
  Snapshots;
- die vollständige Artefaktzahl;
- `identityBindingStatus=VERIFIED`;
- `mathematicalValidationStatus=NOT_EVALUATED`;
- einen vollständigen Receipt-Hash.

Der Receipt enthält bewusst keinen lokalen Pfad. Er ist übertragbare Evidence
für die geprüften Bytes und Identitäten, nicht für den Speicherort.

## Wissenschaftliche Grenze

Byte- und Identitätsprüfung bestätigt weder eine mathematische Aussage noch
externe Neuheit. Sowohl die Discovery Evidence als auch der Handoff müssen
weiterhin folgende Grenzen tragen:

```text
proofStatus=NOT_EVALUATED
externalNoveltyStatus=NOT_EVALUATED
promotionStatus=NOT_EVALUATED
publicEvidenceStatus=NOT_EVALUATED
```

Der Verifier lehnt Exporte ab, die diese Status im Handoff oder in der Discovery
Evidence hochstufen. Das Receipt selbst hält die mathematische Validierung daher
explizit auf `NOT_EVALUATED`.

## Reproduzierbarkeit

Die Referenztests erzeugen und verifizieren je einen Expression-Rewrite- und
einen Finite-Difference-Sequence-Export. Negative Tests decken ab:

- Byte-Manipulation;
- fehlende oder zusätzliche Dateien;
- Größenüberschreitungen;
- symbolische Links;
- Campaign-Substitution trotz konsistent aktualisierter Byte-Hashes;
- doppelte JSON-Felder trotz konsistent aktualisiertem Manifest;
- ungültige Grenzen und Null-Eingaben;
- defensive Kopien und private Konstruktion des authority-bearing Ergebnisses.

Lokale Reproduktion:

```bash
./gradlew :regelsuche-discovery:test \
  --tests de.regelsuche.discovery.domain.DomainDiscoveryExportTest \
  --tests de.regelsuche.discovery.domain.DomainDiscoveryExportVerifierTest
```

Schemas:

- `docs/schemas/regelsuche-domain-discovery-export-v1.schema.json`
- `docs/schemas/regelsuche-domain-discovery-export-verification-v1.schema.json`

## Verbleibender Umfang von #224

Auch ein vollständig verifizierter Export ist noch kein produktiver
Mehrdomänen-Lifecycle. Offen bleiben weiterhin der Verbrauch der Snapshots in
Validation-, Novelty-, Proof-, Release- und Public-Evidence-Komponenten,
domänenneutrale App-/API-Flächen sowie ein eigenes fail-closed Qualification
Profile für den stärkeren domain-generic Capability-Claim.
