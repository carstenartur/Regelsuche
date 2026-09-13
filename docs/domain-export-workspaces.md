# Gespeicherte Sequenz-Exporte in der Workbench

Die vorhandene Seite **Eigene Discovery-Domänen** kann einen verifizierten
Vier-Dateien-Export der eingebauten Domäne
`integer-sequence-finite-difference@v1` importieren, unveränderlich laden und
begrenzt reproduzieren. Das ist ein produktiver Consumer von
`VerifiedDomainExport` für einen konkreten Objekttyp. Es schließt den weiteren
Lifecycle-Umfang von #224 nicht.

## Benutzung

1. In der Workbench **Eigene Discovery-Domänen** öffnen. Die Seite ist unter
   `/static/discovery-domains.html` und `/discovery-domains.html` erreichbar.
2. Unter **Gespeicherte Sequenz-Exporte** gemeinsam `domain.json`,
   `evidence.json`, `lifecycle-handoff.json` und `export-manifest.json` auswählen.
   Die vier Originaldateien dürfen zusammen höchstens 512 KiB belegen.
3. Gespeicherte Folgen, Holdout-Werte, Kandidatenversuche, Zertifikat,
   Suchzustände, Übergänge und fünf Ressourcenzeilen prüfen. Die Quelle behält
   ihre eigene Ergebnisinterpretation, einschließlich `REFUTED` und fehlendem
   Zertifikat.
4. Optional **Vollständige Evidence reproduzieren** ausführen. Replay verwendet
   genau den ursprünglichen Seed und das ursprüngliche Budget. Es erzeugt
   zusätzliche Arbeit; historische Zähler werden nicht geändert.
5. Originaldateien über ihre Dateilinks herunterladen. Ein Link der Form
   `#export=<64-stelliger Manifest-Digest>` lädt später dieselbe Quelle.

Ein kleiner öffentlicher Beispiel-Export lässt sich mit dem vorhandenen
Java-Vertrag erstellen:

```java
var domain = new FiniteDifferenceSequenceDomain();
var seed = DiscoveryDomain.DiscoverySeed.create(
    "sequence-source", domain.domainId(),
    "observed=1,4,9,16;holdout=25,36", "local public control");
var evidence = new DomainDiscoveryRunner().run(
    "sequence-control", domain, seed,
    new DiscoveryDomain.DiscoveryBudget(4, 16, 32, 8, 8, 32)).evidence();
new DomainDiscoveryExport().write(Path.of("sequence-export"), evidence);
```

Die Klassen stammen aus `de.regelsuche.discovery.domain`, `Path` aus
`java.nio.file`. Eine einzelne Evidence-Datei der Provider-CLI ist kein
Vier-Dateien-Export. Fremde Provider, andere Domain-IDs und andere Revisionen
werden von dieser Ansicht nicht aktiviert oder ausgeführt.

## Neuer Ansichtsvertrag, bestehende Quellenautorität

`DomainExportWorkspace.fromVerified(...)` akzeptiert ausschließlich das opake
Ergebnis des bestehenden
[Export-Verifiers](domain-discovery-export-verification.md). Der Dispatch prüft
Domain-ID, Revision und den vollständigen eingebauten Descriptor. Die typisierten
Konstruktoren berechnen Seed- und Evidence-Identitäten erneut; vollständige
kanonische Equality und der aus der Evidence abgeleitete Handoff müssen zu den
verifizierten Originalen passen. Doppelte JSON-Felder, unbekannte oder fehlende
Felder und fehlerhafte verschachtelte Werte werden abgewiesen.

Das additive Schema `regelsuche.domain-export-workspace/v1` enthält:

| Feld | Bindung |
| --- | --- |
| `runId` | Unveränderter `contentHash` des ursprünglichen Export-Manifests |
| `domainId`, `domainRevision`, `inputHash` | Originaler Domain-Vertrag und Seed-Hash |
| `manifest`, `verification` | Bestehende Manifest-, Artefaktwurzel- und Originalbyte-Identitäten |
| `evidence`, `lifecycleHandoff` | Vollständige typisierte Quelle, ohne zusätzliche Proof-/Novelty-Autorität |
| `artifacts` | Rollenprojektionen mit explizitem Quellschema, Quellhash und Status |
| `replaySupported` | Ob die unveränderten Quellenbudgets innerhalb der lokalen Replay-Grenze liegen |
| `claimBoundary` | Grenze der Quellenansicht und der endlichen Validierung |
| `contentHash` | SHA-256 der UTF-8-Ansicht vor Hinzufügen dieses Feldes, in der vom Renderer ausgegebenen Feldreihenfolge |

Der Ansichts-Hash ist eine Integritätsbindung, keine zweite Laufidentität.
`RepresentationDiscoveryRunPlan/v1`, R1–R4 und deren skalare Arbeitsverträge
bleiben unverändert. Es wird keine Sequenz auf R1 abgebildet und kein Summenscore
aus unterschiedlichen Arbeitsdimensionen gebildet.

Die fünf Ressourcenrollen bleiben vollständig: `EXPLORED_STATES`,
`GENERATED_SUCCESSORS`, `CANDIDATE_EVALUATIONS`, `CERTIFICATE_ATTEMPTS` und
`COUNTEREXAMPLE_ATTEMPTS`. Jede Zeile muss zum konfigurierten Quellenbudget passen
und `configured = executed + skipped + remaining` erfüllen.

| Ansichtsrolle | Ziel und Verfügbarkeit |
| --- | --- |
| `DOMAIN_DESCRIPTOR` | Ursprünglicher Descriptor-Hash und dessen Schema |
| `SEARCH_GRAPH`, `REPRESENTATION_CANDIDATES`, `CANDIDATE_DOSSIERS`, `PROGRESS_LEDGER` | Bestehende Domain-Evidence: Zustände/Übergänge, Kandidatenversuche und getrennte Ressourcenzeilen |
| `LIFECYCLE_HANDOFF` | Ursprünglicher Handoff-Hash und dessen Schema |
| `EXPORT_BUNDLE` | Ursprüngliches Export-Manifest |
| `PATH_REPLAY` | Domain-Evidence als Replay-Quelle, nur innerhalb der begrenzten Admission |
| `RULE_RADAR`, `PROOF_OBLIGATIONS`, `EXTERNAL_NOVELTY`, `PROMOTION`, `PUBLIC_EVIDENCE` | `NOT_PRODUCED`, kein erfundener Zielhash |

Die Rollen mit `AVAILABLE` sind Projektionen ihres ausdrücklich benannten
Domain-Schemas. Sie behaupten keine Kompatibilität mit ausdrucksspezifischen
Graph-, Dossier- oder Proof-Artefakten. Eine fehlende Guard- oder Proof-Struktur
wird nicht aus einer Kandidatenmetrik erfunden.

## Unveränderlicher Speicher und begrenzte API

Die bestehenden Server-Authentifizierungs- und OpenAPI-Grenzen gelten auch für
die neuen Routen unter `/api/discovery-domains/exports`:

| Methode / Suffix | Verhalten |
| --- | --- |
| `POST /` | Envelope `regelsuche.domain-export-upload/v1` mit genau `schema` und `files`; die vier festen Dateinamen bilden Base64-Strings der Originalbytes ab |
| `GET /?offset=0&limit=25` | Index `regelsuche.domain-export-workspace-index/v1`; höchstens 25 Einträge pro Seite |
| `GET /{runDigest}` | Die erneut verifizierte, gespeicherte Quellenansicht |
| `GET /{runDigest}/files/{fileName}` | Eine der vier byteidentischen Originaldateien |
| `POST /{runDigest}/replay` | Genau `expectedWorkspaceHash` und `expectedEvidenceHash`; nur passend zur ausgewählten Quelle |

`runDigest` ist der kleingeschriebene 64-stellige SHA-256-Digest des ursprünglichen
Manifests. Die Ansicht liefert `X-Regelsuche-Run-Id` und einen ETag aus ihrem
Integritäts-Hash. Replay liefert `regelsuche.domain-export-replay/v1` mit
`runId`, `workspaceContentHash`, `sourceEvidenceHash`, vollständiger erneut
erzeugter Evidence und bei Erfolg `status=IDENTICAL_CANONICAL_EVIDENCE`.

Der Repository-Pfad liegt neben dem vorhandenen konfigurierten Laufverzeichnis:
aus `regelsuche.discovery.runs.directory=/path/runs` wird
`/path/runs-domain-exports`. Pro Originalmanifest wird atomar ein Verzeichnis
mit `workspace.json` und `export/` angelegt. Auch die ursprünglichen Manifestbytes
bleiben erhalten. Schon geänderte Whitespace-Bytes eines Manifests mit derselben
semantischen Exportidentität führen beim erneuten Import zu einem Konflikt;
der erste Snapshot bleibt erhalten. Jeder Lade-, Download- und Replay-Pfad prüft
die Originaldateien sowie die daraus rekonstruierte Ansicht erneut.

Der Speicher umfasst höchstens 256 Exporte. Uploads sind auf 1 MiB HTTP-Bytes
und 512 KiB decodierte Originalbytes begrenzt; ein kleineres Serverlimit gilt
zusätzlich. Dateinamen und Rollen sind fest, Symlinks und geänderte
Verzeichnismitgliedschaften werden abgewiesen. Fehlerhafte Importe werden vor
der Aufnahme in den Speicher mit 400 zurückgewiesen. Eine andere Originalquelle
unter gleicher Manifestidentität oder eine fremde Replay-Bindung liefert 409.
Fehlerhafte Replay-JSON-Bodies liefern ebenfalls strukturierte 400-Antworten.

Replay akzeptiert höchstens 8.192 Zeichen Seed-Payload und die unveränderten
Budgetgrenzen `(maxDepth=8, maxExploredStates=256, maxGeneratedSuccessors=1024,
maxCandidatesPerState=128, maxCandidateAttempts=128,
maxCounterexampleAttempts=4096)`. Größere Quellen bleiben lesbar, erhalten aber
`PATH_REPLAY=NOT_PRODUCED`. Replay vergleicht die **vollständige** kanonische
Evidence; ein neu gehashtes, falsches Suchprotokoll kann deshalb nicht durch
Übereinstimmung nur des Endkandidaten bestehen.

## Evidence-Grenze

Laden bestätigt Struktur und Identität. Erst ein erfolgreiches Replay bestätigt
die Wiederholung des protokollierten Laufs mit dem unterstützten lokalen
Domain-Vertrag. Beides behält die ursprünglichen Statuswerte
`proofStatus`, `externalNoveltyStatus`, `promotionStatus` und
`publicEvidenceStatus` als `NOT_EVALUATED` bei. Es folgt weder ein formaler Beweis
noch externe mathematische Neuheit, ein Default-/Utility-Claim oder eine neue
[domain-generic Qualification](domain-generic-qualification.md).

Die fokussierten Kontrollen verwenden kleine öffentliche Folgen, echte
HTTP-Imports und den vorhandenen Browser. Sie ersetzen keine Mehrdomänen-Studie
und keine nachgelagerte Proof-, Novelty-, Release- oder Public-Evidence-Komposition.
