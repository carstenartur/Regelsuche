# Gespeicherte Discovery-Exporte in der Workbench

Die vorhandene Seite **Eigene Discovery-Domänen** kann einen verifizierten
Vier-Dateien-Export der eingebauten Domänen
`expression-rewrite@v1`, `integer-sequence-finite-difference@v1` und
`integer-sequence-linear-recurrence@v1` importieren, unveränderlich laden und
begrenzt reproduzieren. Das ist ein produktiver Consumer von
`VerifiedDomainExport` für einen konkreten Objekttyp. Es schließt den weiteren
Lifecycle-Umfang von #224 nicht.

`scoped-logic@v1` lässt sich über denselben Import als logische Quellenansicht
laden und mit sämtlichen Originaldateien herunterladen. Der Arbeitsbereich
führt dabei keine Obligation, Suche oder Beweisprüfung aus. Ergebnisstatus und
Zertifikate werden als gespeicherte Angaben angezeigt. Für einen Replay dieser
Domäne ist hier noch kein unabhängiger Kernel-/Rebuild-Empfänger eingerichtet.

## Benutzung

1. In der Workbench **Eigene Discovery-Domänen** öffnen. Die Seite ist unter
   `/static/discovery-domains.html` und `/discovery-domains.html` erreichbar.
2. Unter **Gespeicherte Discovery-Exporte** gemeinsam `domain.json`,
   `evidence.json`, `lifecycle-handoff.json` und `export-manifest.json` auswählen.
   Die vier Originaldateien dürfen zusammen höchstens 512 KiB belegen.
3. Gespeicherte Formel und Suchziel oder Folge und Holdout-Werte, Kandidatenversuche, Zertifikat,
   Suchzustände, Übergänge und fünf Ressourcenzeilen prüfen. Die Quelle behält
   ihre eigene Ergebnisinterpretation, einschließlich `REFUTED` und fehlendem
   Zertifikat.
4. Optional **Vollständige Evidence reproduzieren** ausführen. Replay verwendet
   genau den ursprünglichen Seed und das ursprüngliche Budget. Es erzeugt
   zusätzliche Arbeit; historische Zähler werden nicht geändert.
5. Bei Sequenz-Exporten optional **Endliche Daten prüfen** für den separaten
   [endlichen Validierungsbeleg](domain-downstream-finite-validation.md) oder
   **Modellgesetz prüfen** für einen
   [Beleg zum ausdrücklich angenommenen affinen Modell](domain-model-proof.md)
   ausführen. Beide Aktionen binden die ausgewählte unveränderte Quelle und
   weisen ihre zusätzliche Arbeit getrennt aus. Formel-Exporte zeigen diese
   beiden Aktionen nicht an; direkte API-Aufrufe stellen keinen Sequenzbeleg aus.
6. Originaldateien über ihre Dateilinks herunterladen. Ein Link der Form
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

Für einen Formel-Lauf verwendet derselbe Exportvertrag die bestehende
`ExpressionRewriteDiscoveryDomain`. Ihr Konstruktor erhält das Suchziel,
der Seed die Ausgangsformel. Das Ziel wird in
`domainEvidence.properties.expectedTarget` gespeichert. Beim Replay wird
genau dieses Ziel rekonstruiert. Die Startformel allein bestimmt den Lauf
nicht: Zwei Exporte mit gleichem Seed und unterschiedlichem Ziel behalten
getrennte Manifest- und Ansichtsidentitäten.

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
| `replaySupported` | Ob ein lokaler Replay-Empfänger vorhanden ist und die unveränderten Quellenbudgets innerhalb seiner Grenze liegen |
| `claimBoundary` | Grenze der Quellenansicht und der endlichen Validierung |
| `contentHash` | SHA-256 der UTF-8-Ansicht vor Hinzufügen dieses Feldes, in der vom Renderer ausgegebenen Feldreihenfolge |

Der Ansichts-Hash ist eine Integritätsbindung, keine zweite Laufidentität.
`RepresentationDiscoveryRunPlan/v1`, R1–R4 und deren skalare Arbeitsverträge
bleiben unverändert. Es wird keine Sequenz auf R1 abgebildet und kein Summenscore
aus unterschiedlichen Arbeitsdimensionen gebildet.

Logische Exporte behalten `replaySupported=false`; die Rolle `PATH_REPLAY` nennt
`SCOPED_PROOF_REPLAY_NOT_CONFIGURED`. Die getrennte Assessment-Antwort nennt
`REPLAY_NOT_CONFIGURED` und lässt Proof und Gegenbeispielprüfung unbewertet.
Die direkten Sequenzvalidierungs- und Modellbeweis-Routen geben `UNSUPPORTED`
zurück, ohne einen Replay-Versuch oder eine mathematische Prüfung zu buchen.
Auch ein importierter `CONFIRMED`-Status mit Zertifikat erzeugt dadurch keinen
neuen Beweis, kein verifiziertes Neuheitssubjekt und keine Freigabe. Die drei
bisherigen Domänen behalten ihre Ansichtsidentitäten und Replay-Verträge.

Die fünf Ressourcenrollen bleiben vollständig: `EXPLORED_STATES`,
`GENERATED_SUCCESSORS`, `CANDIDATE_EVALUATIONS`, `CERTIFICATE_ATTEMPTS` und
`COUNTEREXAMPLE_ATTEMPTS`. Jede Zeile muss zum konfigurierten Quellenbudget passen
und `configured = executed + skipped + remaining` erfüllen.

Für `expression-rewrite@v1` muss zusätzlich der Payload-Vertrag
`expression-rewrite-evidence/v1` mit einem nichtleeren `expectedTarget`
vorliegen. Der Import führt keine Suche aus und parst keine unbegrenzten
Quellformeln. Fehlende oder fremde Konfigurationen werden abgewiesen.
Eine mit neu berechneten Hashes geänderte Zielangabe kann zwar ein neues
strukturell gültiges Quelldokument ergeben; sie reproduziert das alte
Suchprotokoll nicht und wird beim vollständigen Replay abgewiesen.

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

### Einzelne logische Kandidaten mit einem unabhängigen Empfänger prüfen

Die Ansicht eines logischen Exports bietet bei jedem gespeicherten Versuch
**Kandidat erneut prüfen** an. Ein Java-Consumer kann dieselbe Prüfung über
`ScopedLogicWorkspaceCheck.check(workspace, candidateHash, receiver)` ausführen
und stellt dafür den vertrauenswürdigen `ScopedProofExecutor` bereit. Der Abruf
und der Import eines Workspaces starten diese Prüfung nicht.

Die Web-Workbench führt die Gegenbeispielprüfung auch ohne Lean-Installation aus.
Für die anschließende formale Prüfung verwendet sie zwei separat bereitgestellte,
gleich gepinnte Lean-Projekte. Konfiguration über `REGELSUCHE_LEAN_PROJECT` und
`REGELSUCHE_LEAN_REBUILD_PROJECT` oder die Java-Eigenschaften
`regelsuche.lean.project` und `regelsuche.lean.rebuild.project`. Lean speichert
seine Nachweise im Verzeichnis aus `regelsuche.proof.evidence`.

Die Prüfung rekonstruiert den Vorschlag aus dem ursprünglichen Seed und verlangt
den exakten Kandidatenhash, den passenden gespeicherten Zustand und seine
akzeptierte Quellkante. Kandidat und Zustände müssen innerhalb von 4.096 Einheiten
Graphbildung und einem Seed von höchstens 8.192 UTF-16-Codeeinheiten überprüfbar
sein. Bis zu 256 konstante Interpretationen liefern einen getrennten
Gegenbeispielbefund. Ein gespeicherter Zeuge gilt nur bei identischem Zeugen und
gleicher Zahl von Versuchen als reproduziert. Der Empfänger führt die formale
Prüfung nur nach `NONE_FOUND` aus.

Das Ergebnis enthält die Run-, Workspace-, Evidenz-, Seed-, Kandidaten- und
Zustandshashes sowie gegebenenfalls neue Kernel- und Rebuild-Quittungen. Der
Vorschlag darf ein anderes Ziel als der Seed haben. Die Prämissen, Deklarationen,
Theorien und Quellprovenienz bleiben exakt gebunden. Historische Quittungen
werden während der neuen Prüfung nicht als Nachweis verwendet. Die neue Prüfung
bewertet einen Kandidaten und gibt das Ergebnis in der laufenden Ansicht aus.
Die Quellenansicht und gespeicherte Prüfbelege zur vollständigen historischen
Laufwiederholung behalten ihren eigenen Vertrag.

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
| `POST /{runDigest}/scoped-check` | Dieselben beiden Quellhashes und der `candidateHash` eines gespeicherten logischen Versuchs. Gibt den neuen Gegenbeispiel- und gegebenenfalls formalen Prüfbefund zurück. |
| `POST /{runDigest}/validate` | Unabhängige endliche Prüfung mit denselben zwei Quellenbindungen; eigener Validierungsbeleg |
| `GET /{runDigest}/validate/{receiptDigest}` | Gespeicherter endlicher Validierungsbeleg mit geprüfter Quellenbindung |
| `POST /{runDigest}/model-proof` | Bedingtes affines Modellgesetz nach Replay und endlicher Prüfung; eigener Modellbeleg |
| `GET /{runDigest}/model-proof/{receiptDigest}` | Gespeicherter Modellbeleg nach vollständiger erneuter Reproduktion |

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

Für Formel-Exporte gilt vor jedem Parser- oder Suchaufruf eine engere
Admission: Startformel und gespeichertes Ziel dürfen jeweils höchstens
256 UTF-16-Codeeinheiten enthalten. Größere Formel-Exporte bleiben
unverändert lesbar und herunterladbar, erhalten aber keine Replay-Aktion.
Die bestehende Domain prüft ihre Zustandsinvarianten und AST-Grenzen selbst.
Die Workspace-Identität bereits gespeicherter Sequenz-Exporte bleibt unverändert.

## Evidence-Grenze

Laden bestätigt Struktur und Identität. Erst ein erfolgreiches Replay bestätigt
die Wiederholung des protokollierten Laufs mit dem unterstützten lokalen
Domain-Vertrag. Beides behält die ursprünglichen Statuswerte
`proofStatus`, `externalNoveltyStatus`, `promotionStatus` und
`publicEvidenceStatus` als `NOT_EVALUATED` bei. Es folgt weder ein formaler Beweis
noch externe mathematische Neuheit, ein Default-/Utility-Claim oder eine neue
[domain-generic Qualification](domain-generic-qualification.md).

Die nachgelagerten [endlichen Prüfungen](domain-downstream-finite-validation.md)
und [Modellbeweise](domain-model-proof.md) verändern diese ursprünglichen
Statuswerte nicht. Ein Modellbeleg kann ein Gesetz des ausdrücklich definierten
affinen Modells bestätigen; dass eine anderweitig definierte unendliche Quelle
diesem Modell folgt, bleibt unbewiesen. Die Quellenansicht, der endliche Beleg
und der Modellbeleg haben getrennte Identitäten und Aussagegrenzen.

## Gebundene Auswertung aller drei Domänen

Im Workspace startet **Kandidat und Belege prüfen** einen gebundenen POST an
`/{runDigest}/assessment`. Der Request enthält `expectedWorkspaceHash` und
`expectedEvidenceHash`. Die Antwort enthält den Inhalts-Hash und einen
`Location`-Pfad. Jeder GET dieses Pfads öffnet den gespeicherten Originalexport
neu, führt die bounded Checks erneut aus und verlangt denselben Inhalts-Hash
und identische Receipt-Bytes. Die Ablage ist auf 16 Belege pro Export und
1 MiB je Beleg begrenzt. Der unveränderte Quellbeleg verweist weiter auf
seinen historischen Validierungsstand.

| Abschnitt | Aussage und benötigte Quelle |
|---|---|
| Validierung und Gegenbeispiele | Exakter Replay vergleicht die gesamte kanonische Evidence. Der Beleg erhält jeden Kandidatenversuch. Für den ersten widerlegten Kandidaten eines unterstützten affinen Modells prüft eine separate rationale Berechnung bis zu 64 gespeicherte Werte. Ein gefundener Gegenbeleg enthält Index, beobachteten und berechneten Wert sowie Kandidaten-Hash. |
| Proof | Bei Folgen führt der vorhandene endliche Datencheck zum bedingten Modellbeweis. Bei Formeln prüft der exakte rationale Polynomchecker Quelle, ausgewählten Ausgangs- und Zielterm und aufgezeichnetes Ziel unter Polynomring-Semantik. Unterschiedliche Normalformen, nicht unterstützte Terme und Budgetgrenzen erteilen keine Beweisbestätigung. |
| Ressourcen und Ablation | Ein zweiter Lauf ersetzt allein die maximale Suchtiefe durch null. Er übernimmt Seed, Adapter und alle anderen Budgets. Beide Läufe behalten die fünf Ressourceneinheiten. Das ist ein Tiefenvergleich auf dem konkreten Seed und keine allgemeine Effizienzaussage. |
| Literatur | Der ausgewählte, reproduzierte Kandidat besitzt einen privaten Prüfschlüssel aus Manifest-, Kandidaten- und Evidence-Hash. Die vorhandene Literaturansicht verwaltet dokumentierte Recherchen und Vorarbeiten. Übertragung einer Anfrage an TheoremSearch benötigt dieselbe explizite Freigabe wie bisher. |
| Release und Public Evidence | Die Release-Qualifikation rekonstruiert neun Auswertungen aus den drei Referenzläufen. Ein reproduzierbarer Forschungsbeleg kann berichtet werden. Promotion und öffentliche mathematische Claims verlangen eigene Belege. |

Das Schema [domain-snapshot-assessment/v1](schemas/regelsuche-domain-snapshot-assessment-v1.schema.json)
beschreibt die Auswertung. Die mathematische Nachprüfung und die Bindung der
Originalbytes erfolgen beim Erzeugen und beim Wiederladen. Alte Workspace-,
Validierungs-, Modell- und Qualification-Formate behalten ihre eigenen Identitäten.

### Signierte Literaturentscheidung zu einer geprüften Formel

Die Anwendungs-CLI gibt mit `domains review-subject EXPORT` die genaue
Gleichung, ihren Geltungsbereich, die Rewrite-Annahmen und die Identitäten
für einen eingefrorenen Rechercheplan aus. `formationEvidenceRoot` ist der
Hash der vollständigen reproduzierten Domänenauswertung. Der Pfad setzt
einen exakten rationalen Polynomnachweis voraus. Die private Dokumentation
von Literaturbefunden steht auch den Folgendomänen offen.

```text
domains verify-review EXPORT PINNED_FREEZE PINNED_CATALOGUE TRUSTED_ROSTER.json REVIEW.json
```

Die Empfängerseite gibt die vor der Recherche gesicherten Plan- und
Katalogwurzeln sowie ihre unabhängig festgelegte Gutachterliste an.
Die CLI wiederholt die vorhandene Prüfung von Rechercheabdeckung, Stimmen
und Ed25519-Signaturen. Sie vergleicht anschließend die Kandidatenidentität,
die Gleichung, die sortierten Rewrite-Annahmen einschließlich des
Polynom-Geltungsbereichs und die gesamte eingefrorene Evidence-Wurzel
mit der Domänenauswertung. Ein anderer Term oder Geltungsbereich schlägt
auch bei gültigen Signaturen fehl. Der Beleg enthält die geprüfte Entscheidung,
Plan-, Katalog-, Paket-, Intake- und Gutachterlistenwurzel. Die Aussage
bezieht sich auf diese Gleichung und Recherche. Promotion und Public Evidence
behalten eigene Entscheidungen. Der Befehl arbeitet vollständig offline.
