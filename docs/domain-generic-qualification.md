# Qualification für domänengenerische Discovery

## Zweck und Status

Issue #224 beschreibt einen stärkeren Capability-Claim als die bereits
qualifizierte algebraische Aussage `AUTONOMOUS_CAMPAIGN`. Der hier dokumentierte
Vertrag führt dafür ein **eigenständiges, versioniertes Evidence Profile** ein.
Er erweitert weder den vorhandenen Autonomie-Claim noch die Bedeutung von
Proof, Novelty, Promotion oder Public Evidence.

Die Referenzimplementierung umfasst:

- `DomainGenericEvidenceProfile` als unveränderlichen Profile-Katalog;
- `DomainGenericDiscoveryQualification` als fehlersicher sperrende Auswertung;
- `DomainGenericQualificationRunner` als reproduzierbare Referenzkampagne;
- drei Draft-2020-12-Schemas für Katalog, Qualification Report und Run-Receipt;
- einen dedizierten CI-Workflow mit unabhängiger Hash- und Schema-Prüfung.

## Normative Sprache

Die Begriffe **MUSS**, **DARF NICHT**, **SOLLTE** und **KANN** sind normativ zu
verstehen:

- **MUSS / DARF NICHT** kennzeichnen eine Voraussetzung für einen
  `READY`-Report;
- **SOLLTE** kennzeichnet eine betriebliche Empfehlung, deren Abweichung
  dokumentiert werden muss;
- **KANN** beschreibt optionale Implementierungsdetails ohne Einfluss auf den
  Claim.

Bei Widersprüchen zwischen dieser Beschreibung, dem Java-Modell und den
JSON-Schemas gilt die strengere, fehlersicher sperrende Auslegung. CI hält die
kanonischen Referenzartefakte und die öffentlichen Schemas synchron.

## Autorisierter Claim

`DOMAIN_GENERIC_DISCOVERY` autorisiert ausschließlich folgende Aussage:

> Reproduzierbare Generation, begrenzte Suche, Counterexample-Suche,
> Validierung, Certificate-Rendering und Evidence über mindestens zwei
> verschiedene mathematische Objekttypen hinweg.

Der Profile-Katalog
`regelsuche.domain-generic-evidence-profile-catalog/v1` trägt deshalb getrennte
Flags:

```text
authorizesDomainGenericClaim=true
authorizesAutonomousCampaignClaim=false
```

Ein `READY`-Report DARF ausschließlich `domainGenericClaimAuthorized=true`
setzen. `autonomousCampaignClaimAuthorized` MUSS in Katalog, Report und
Run-Receipt `false` bleiben. Der bestehende `AUTONOMOUS_CAMPAIGN`-Katalog und
seine algebraische Release-Aussage werden weder erweitert noch umgedeutet.

## Trust Boundary und Beweiswert

Die Qualification verarbeitet keine frei übergebenen Pfade oder ungeprüften
JSON-Dokumente. Sie akzeptiert ausschließlich privat konstruierbare
`VerifiedDomainExport`-Snapshots aus `DomainDiscoveryExportVerifier`.
Dadurch sind vor der Qualification bereits geprüft:

- die vollständige Pfad-Ancestry auf symbolische Links;
- exakte Verzeichnis-Mitgliedschaft;
- Byte-Längen und SHA-256-Werte aller Artefakte;
- ein während des Lesens unverändertes Manifest;
- striktes JSON ohne doppelte, unbekannte oder nachgestellte Felder;
- Campaign-, Domain-, Revision-, Descriptor-, Seed-, Evidence- und
  Handoff-Bindungen.

Der Qualification Report ist **kanonische, reproduzierbare Evidence**, aber
keine digitale Signatur und kein externer Attestierungsnachweis. Ein Consumer
DARF Schema-Validierung allein nicht als Autorisierung behandeln. Für einen
vertrauenswürdigen Claim MUSS er entweder:

1. die Qualification aus verifizierten Snapshots erneut ausführen; oder
2. Report und Run-Receipt einschließlich aller referenzierten Hashes gegen eine
   anderweitig authentisierte Auslieferungsgrenze prüfen.

Die `contentHash`-Felder erkennen unbeabsichtigte oder nicht konsistent
nachgeführte Änderungen. Sie ersetzen keine Signatur, wenn die Quelle der
Artefakte nicht bereits vertrauenswürdig ist.

## Referenzkampagne

Die Referenzkampagne verwendet zwei semantisch verschiedene Adapter desselben
generischen Vertrags `DiscoveryDomain<State, Candidate, Certificate>`:

| Domäne | Seed | State-Typ | Validierungsgrenze |
|---|---|---|---|
| `expression-rewrite` | `x + 0` | kanonischer Ausdruckszustand | bestätigte algebraische Rewrite-Evidence |
| `integer-sequence-finite-difference` | beobachtet `1,4,9,16`; Holdout `25,36` | endlicher Sequenzzustand | Finite-Difference-Witness für Beobachtung und Holdout |

Die State-, Candidate- und Certificate-Typen MÜSSEN sich domänenspezifisch im
Descriptor erhalten. Mathematische Objekte DÜRFEN NICHT in ein gemeinsames,
mehrdeutiges Stringformat abgeflacht werden.

## Fehlersicher sperrende Anforderungen

`regelsuche.domain-generic-discovery-qualification/v1` wertet genau die
folgenden Requirement-Codes aus:

| Requirement-Code | Normative Anforderung |
|---|---|
| `VERIFIED_EXPORT_SNAPSHOTS` | Jeder gezählte Input MUSS ein erfolgreich verifizierter, unveränderlicher Export-Snapshot sein. |
| `AT_LEAST_TWO_DISTINCT_DOMAINS` | Es MÜSSEN mindestens zwei verschiedene Domain-IDs vorliegen. |
| `DISTINCT_MATHEMATICAL_STATE_TYPES` | Es MÜSSEN mindestens zwei verschiedene mathematische State-Typen vorliegen. |
| `EXPRESSION_REWRITE_DOMAIN_RETAINED` | Der bestehende Adapter `expression-rewrite` MUSS enthalten sein. |
| `NON_EXPRESSION_DOMAIN_RETAINED` | Mindestens eine gezählte Domäne MUSS eine Nicht-Expression-Domäne sein. |
| `CONFIRMED_CANDIDATES_WITH_CERTIFICATES` | Jede gezählte Domäne MUSS `CONFIRMED` sein und Kandidaten- sowie Certificate-Hash binden. |
| `SHARED_RESOURCE_ACCOUNTING` | Alle Domänen MÜSSEN dieselbe vollständige Menge kanonischer Ressourcenrollen verwenden. |
| `BALANCED_RESOURCE_ACCOUNTING` | Für jede Ressource MUSS `configured = executed + skipped + remaining` gelten. |
| `REPRESENTATION_FREE_LIFECYCLE_HANDOFF` | Der Lifecycle-Handoff DARF keine Zustände, Pfade, Ausdrücke, Sequenzterme oder sonstige Repräsentationspayloads enthalten. |
| `THREE_CLEAN_MULTI_DOMAIN_RUNS` | Mindestens drei vollständige Mehrdomänen-Läufe MÜSSEN byte-identische gebundene Ergebnisse liefern. |
| `PROOF_STATUS_NOT_EVALUATED` | Proof MUSS `NOT_EVALUATED` bleiben. |
| `EXTERNAL_NOVELTY_STATUS_NOT_EVALUATED` | Externe Novelty MUSS `NOT_EVALUATED` bleiben. |
| `PROMOTION_STATUS_NOT_EVALUATED` | Promotion MUSS `NOT_EVALUATED` bleiben. |
| `PUBLIC_EVIDENCE_STATUS_NOT_EVALUATED` | Public Evidence MUSS `NOT_EVALUATED` bleiben. |

Für `READY` MÜSSEN alle vierzehn Checks vorhanden und erfolgreich sein;
`blockers` MUSS leer sein. Jeder fehlgeschlagene Check erzwingt `BLOCKED`,
`domainGenericClaimAuthorized=false` und mindestens einen expliziten Blocker.
Unbekannte Felder oder alternative Claim-Texte werden von den öffentlichen
Schemas abgewiesen.

## Ressourcen- und Repräsentationsgrenze

Die v1-Qualification verlangt exakt diese fünf, alphabetisch kanonisierten
Ressourcenrollen:

```text
CANDIDATE_EVALUATIONS
CERTIFICATE_ATTEMPTS
COUNTEREXAMPLE_ATTEMPTS
EXPLORED_STATES
GENERATED_SUCCESSORS
```

Gleich benannte Rollen bedeuten domänenübergreifend dieselbe
Abrechnungssemantik, nicht dieselbe mathematische Operation. Die detaillierten
Domänenartefakte behalten weiterhin ihre eigenen Annahmen, Counterexamples und
Certificates.

Der Lifecycle-Handoff bleibt absichtlich repräsentationsfrei. Insbesondere sind
verschachtelte Felder wie `payload`, `canonicalState`, `seedExpression`,
`selectedExpression`, `sequenceTerms`, `states` und `path` nicht zulässig.
Detaillierte mathematische Evidence bleibt über ihre Hashwurzeln erreichbar,
wird aber nicht in die gemeinsame Übergabe kopiert.

## Wissenschaftliche Grenzen

Architekturgeneralität und Reproduzierbarkeit sind weder ein formaler
mathematischer Beweis noch eine externe Neuheitsbewertung. In jedem
Domain-Export, im Qualification Report und im Run-Receipt MÜSSEN daher gelten:

```text
proofStatus=NOT_EVALUATED
externalNoveltyStatus=NOT_EVALUATED
promotionStatus=NOT_EVALUATED
publicEvidenceStatus=NOT_EVALUATED
```

Die domänenspezifischen Certificates behalten ihre jeweilige Stärke. Das
Finite-Difference-Witness validiert den endlichen beobachteten Datensatz und den
Holdout, beweist aber keine eindeutige unendliche Folge. Eine spätere Proof- oder
Novelty-Komponente MUSS eine eigene, versionierte Entscheidung mit unveränderter
Semantik erzeugen.

## Persistierte Artefakte

`DomainGenericQualificationRunner` führt drei saubere Läufe aus. Jeder Lauf
schreibt und verifiziert für beide Domänen:

```text
runs/run-N/
  expression/
    domain.json
    evidence.json
    lifecycle-handoff.json
    export-manifest.json
  sequence/
    domain.json
    evidence.json
    lifecycle-handoff.json
    export-manifest.json
  verification-receipts/
    expression.json
    sequence.json
```

Der Root enthält zusätzlich:

```text
profile-catalog.json
qualification-report.json
qualification-run.json
```

Der Run-Fingerprint bindet je Domäne:

```text
domainId | manifest.contentHash | exportVerification.contentHash
```

Die Liste wird nach `domainId` sortiert und unter
`regelsuche.domain-generic-clean-run/v1` gehasht. Alle drei Fingerprints MÜSSEN
identisch sein. `qualification-run.json` bindet zusätzlich Katalog-, Report- und
Fingerprint-Identitäten sowie sämtliche Claim- und Statusgrenzen.

## Consumer-Prüfverfahren

Ein Consumer SOLLTE die Artefakte in dieser Reihenfolge prüfen:

1. alle drei JSON-Schemas gegen Draft 2020-12 validieren;
2. `profile-catalog.json` bytegenau hashen und mit `profileCatalogHash`
   vergleichen;
3. jeden Domain-Export über `DomainDiscoveryExportVerifier` erneut verifizieren;
4. die je Lauf sortierten Fingerprints unabhängig rekonstruieren;
5. mindestens drei identische Fingerprints verlangen;
6. den Qualification Report aus den verifizierten Snapshots neu auswerten;
7. Report- und Run-`contentHash` unabhängig berechnen;
8. Claim-Flags und alle vier `NOT_EVALUATED`-Grenzen abgleichen.

Bei einer Abweichung MUSS der Consumer den Claim sperren. Teilweise gültige
Domänen oder ein gültiger einzelner Lauf dürfen nicht als degradierter
`READY`-Nachweis ausgegeben werden.

## Reproduktion und CI

Lokale Referenzausführung:

```bash
./gradlew :regelsuche-release:runDomainGenericQualification
```

Gezielte Charakterisierung:

```bash
./gradlew :regelsuche-release:test \
  --tests de.regelsuche.release.DomainGenericQualificationRunnerTest
```

Der Workflow `Domain Generic Qualification`:

- führt Test und Referenzkampagne aus;
- verlangt alle erwarteten Artefakte;
- wiederholt die vollständige Kampagne byte-identisch;
- validiert Katalog, Report und Run-Receipt gegen ihre Schemas;
- rekonstruiert Domain-, Report-, Run- und Clean-Run-Hashes unabhängig;
- prüft Claim-, Autonomie-, Proof-, Novelty-, Promotion- und
  Public-Evidence-Grenzen;
- hält negative Schemafälle für Claim-Inflation und unvollständige
  `READY`-Reports fest;
- lädt Evidence und Diagnostik auch bei Fehlern hoch.

## Negative Charakterisierung

Die Tests und Schema-Negativfälle halten insbesondere fest:

- zwei Kopien derselben Domäne erfüllen keine Mehrdomänen-Qualification;
- eine refutierte zweite Domäne kann den Claim nicht autorisieren;
- zwei statt drei saubere Läufe bleiben blockiert;
- manipulierte Report-Hashes werden abgewiesen;
- ein `READY`-Report mit fehlgeschlagenem Pflichtcheck ist ungültig;
- alternative Claim-Texte und unvollständige Ressourcenrollen sind ungültig;
- Proof-Inflation und die Autorisierung von `AUTONOMOUS_CAMPAIGN` sind ungültig.

## Versionierung und Kompatibilität

Die Schemas verwenden stabile IDs mit `/v1`. Eine semantische Änderung an
Requirement-Codes, Claim-Grenze, Ressourcenrollen, Hashmaterial oder
`READY`-Bedingungen erfordert eine neue Schema-Version. Erweiterungen dürfen
nicht stillschweigend über zusätzliche JSON-Felder eingeführt werden, da alle
v1-Objekte `additionalProperties=false` verwenden.

## Schemas

- `docs/schemas/regelsuche-domain-generic-evidence-profile-catalog-v1.schema.json`
- `docs/schemas/regelsuche-domain-generic-discovery-qualification-v1.schema.json`
- `docs/schemas/regelsuche-domain-generic-discovery-qualification-run-v1.schema.json`

## Verbleibender Umfang von #224

Dieses Profile qualifiziert die bereits generische Generation-, Search-,
Counterexample-, Validation-, Certificate- und Evidence-Schicht. Noch offen
bleiben die Portierung ausdrucksspezifischer App- und Persistenzflächen sowie die
produktive Wiederverwendung verifizierter Snapshots in weiterführenden
Novelty-, Proof-, Release- und Public-Evidence-Komponenten. Diese späteren Gates
müssen ihre bisherigen Bedeutungen und die vollständige Source-Evidence-Kette
beibehalten.
