# Generischer Domain-Discovery-Export

Dieser Baustein führt Issue #224 weiter, indem die bereits domänenneutrale
`DomainDiscoveryEvidence` und der `DiscoveryLifecycleHandoff` als gemeinsam
prüfbarer, persistenter Export bereitgestellt werden. Er verändert weder die
mathematische Bedeutung der Evidence noch die Such-, Validierungs-, Proof-,
Novelty-, Promotion- oder Public-Evidence-Semantik.

## Zweck

`DomainDiscoveryExport` akzeptiert genau einen abgeschlossenen Lauf des
`DiscoveryDomain<State, Candidate, Certificate>`-Vertrags. Der Export behält
mathematische Details in ihrer jeweils eigenen Evidence und bietet gleichzeitig
einen repräsentationsfreien Einstiegspunkt für nachgelagerte Komponenten.

Für Ausdrucks-Rewrites und endliche Differenzen entsteht dieselbe Dateistruktur:

```text
<export>/
  domain.json
  evidence.json
  lifecycle-handoff.json
  export-manifest.json
```

- `domain.json` enthält den vollständigen, versionierten
  `DiscoveryDomainDescriptor`.
- `evidence.json` enthält die vollständige `DomainDiscoveryEvidence` einschließlich
  domänenspezifischer Zustände, Kandidaten, Zertifikate und Evidence-Payloads.
- `lifecycle-handoff.json` enthält ausschließlich Campaign-, Domain-, Input-,
  Evidence-, Ressourcen- und Statusidentitäten. Repräsentationsfelder wie
  Ausdrucksstrings, Folgenwerte, Zustände oder Pfade sind dort ausgeschlossen.
- `export-manifest.json` ist der Commit-Marker des vollständigen Exports.

## Bindungen

Vor jedem Schreibvorgang erzwingt der Java-Vertrag:

```text
handoff.campaignId         = evidence.campaignId
handoff.domainId           = evidence.domain.domainId
handoff.domainRevision     = evidence.domain.revision
handoff.domainContractHash = evidence.domain.contentHash
handoff.inputHash          = evidence.seed.contentHash
handoff.sourceEvidenceHash = evidence.contentHash
```

Das Manifest `regelsuche.domain-discovery-export/v1` bindet zusätzlich:

- Campaign-, Domain- und Revisionsidentität;
- Descriptor-, Evidence- und Handoff-Root-Hashes;
- pro Artefakt die feste Rolle und den kanonischen Dateinamen;
- den semantischen `sourceContentHash`;
- SHA-256 und Länge der exakt gespeicherten UTF-8-Bytes;
- das Commit-Protokoll und einen vollständigen Manifest-Hash.

Die drei Rollen `DOMAIN_DESCRIPTOR`, `DISCOVERY_EVIDENCE` und
`LIFECYCLE_HANDOFF` müssen jeweils genau einmal vorhanden sein. Teilmanifeste,
doppelte Rollen, alternative Dateinamen oder nicht passende Root-Hashes werden
abgelehnt. Die Artefaktliste wird unabhängig von der Eingabereihenfolge
kanonisch nach Dateiname sortiert; diese Reihenfolge ist Teil des Manifest-Hashes.

## Fail-closed Commit-Protokoll

Der Export verwendet `MANIFEST_LAST_ATOMIC_RENAME`:

1. Ein vorhandenes Manifest wird entfernt, bevor Artefakte ersetzt werden.
2. Jede neue Datei wird zunächst in eine temporäre Datei im Zielverzeichnis
   geschrieben und auf das Dateisystem geflusht.
3. Die temporäre Datei ersetzt das Ziel durch einen atomaren Rename.
4. Das neue Manifest wird zuletzt geschrieben.

Ein fehlendes Manifest kennzeichnet damit einen unvollständigen Export. Ein
Consumer darf keine lose Sammlung vorhandener Dateien als committed behandeln.
Er muss zuerst das Manifest validieren, alle Rollen und Root-Bindungen prüfen und
dann Byte-Hash sowie Byte-Länge jeder referenzierten Datei nachrechnen.

## Evidence-Grenze

Der Export bestätigt keine mathematische Wahrheit über die bereits vorhandene
Validierung hinaus. Insbesondere bleiben im Handoff unverändert:

```text
proofStatus=NOT_EVALUATED
externalNoveltyStatus=NOT_EVALUATED
promotionStatus=NOT_EVALUATED
publicEvidenceStatus=NOT_EVALUATED
```

Das Manifest ist Provenance- und Persistenz-Evidence. Es ist weder ein formaler
Beweis noch ein Release- oder Veröffentlichungsentscheid.

## Reproduzierbarkeit

Der Workflow `Domain Discovery Export` erzeugt Referenzexporte für:

- die bestehende Expression-Rewrite-Domäne;
- die Finite-Difference-Sequence-Domäne.

Er verlangt byte-identische Wiederholungen, validiert Descriptor-, Evidence-,
Handoff- und Export-Schema, berechnet alle gespeicherten Byte-Hashes und den
Manifest-Hash unabhängig neu und prüft die Repräsentationsgrenze rekursiv.

Lokale Reproduktion:

```bash
./gradlew :regelsuche-discovery:test \
  --tests de.regelsuche.discovery.domain.DomainDiscoveryExportTest
```

Veröffentlichte Schemas:

- `docs/schemas/regelsuche-discovery-domain-descriptor-v1.schema.json`
- `docs/schemas/regelsuche-domain-discovery-evidence-v1.schema.json`
- `docs/schemas/regelsuche-discovery-lifecycle-handoff-v1.schema.json`
- `docs/schemas/regelsuche-domain-discovery-export-v1.schema.json`

## Verbleibender Umfang von #224

Dieser Export macht generische Läufe persistent, schließt #224 aber nicht. Offen
bleiben insbesondere:

- der Verbrauch des Handoffs durch die vollständige produktive Validation-,
  Novelty-, Proof-, Release- und Public-Evidence-Komposition;
- domänenneutrale App- und API-Flächen statt ausdrucksspezifischer Modelle;
- gemeinsame Ablation-, Telemetrie- und Release-Evidence über mehrere Domänen;
- ein eigenes, fail-closed Evidence Profile für einen späteren stärkeren
  domain-generic Capability-Claim;
- eine reproduzierbare Mehrdomänen-Kampagne ohne abgeleitete Behauptung externer
  mathematischer Neuheit.
