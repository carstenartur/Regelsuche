# Qualification für domänengenerische Discovery

Issue #224 beschreibt einen stärkeren Capability-Claim als die bereits
qualifizierte algebraische `AUTONOMOUS_CAMPAIGN`-Aussage. Dieser Vertrag hält
beide Aussagen strikt getrennt.

## Eigenständiges Evidence Profile

`DOMAIN_GENERIC_DISCOVERY` autorisiert ausschließlich folgenden Claim:

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

Der bestehende `AUTONOMOUS_CAMPAIGN`-Katalog und seine algebraische
Release-Aussage werden weder erweitert noch umgedeutet.

## Eingabegrenze

Die Qualification verarbeitet keine frei übergebenen Dateien. Sie akzeptiert
nur `VerifiedDomainExport`-Snapshots, die zuvor durch
`DomainDiscoveryExportVerifier` geprüft wurden. Damit sind Manifest,
Descriptor, vollständige Discovery Evidence und repräsentationsfreier
Lifecycle-Handoff byte- und wurzelgebunden.

Die Referenzkampagne verwendet:

- `expression-rewrite` mit dem Seed `x + 0`;
- `integer-sequence-finite-difference` mit beobachteten Quadratzahlen und
  getrenntem Holdout `25,36`.

Beide Läufe verwenden denselben generischen
`DiscoveryDomain<State, Candidate, Certificate>`-Runner, aber unterschiedliche
State-, Candidate- und Certificate-Typen.

## Fail-closed Anforderungen

`regelsuche.domain-generic-discovery-qualification/v1` verlangt:

1. vollständig verifizierte Export-Snapshots;
2. mindestens zwei verschiedene Domain-IDs;
3. mindestens zwei verschiedene mathematische State-Typen;
4. den bestehenden Expression-Rewrite-Adapter;
5. mindestens eine Nicht-Expression-Domäne;
6. `CONFIRMED`-Evidence mit ausgewähltem Kandidaten und Certificate für jede
   gezählte Domäne;
7. dieselbe vollständige Ressourcenrollenmenge;
8. für jede Ressource
   `configured = executed + skipped + remaining`;
9. repräsentationsfreie Lifecycle-Handoffs;
10. mindestens drei byte-identische saubere Mehrdomänen-Läufe.

Fehlt eine Anforderung, ist der Status `BLOCKED`, die Blocker bleiben vollständig
sichtbar und `domainGenericClaimAuthorized=false`.

## Wissenschaftliche Grenzen

Der Architektur- und Reproduzierbarkeitsnachweis ist kein formaler Beweis und
keine externe mathematische Neuheitsbewertung. Sowohl in den Domain-Exporten als
auch im Qualification Report bleiben deshalb zwingend:

```text
proofStatus=NOT_EVALUATED
externalNoveltyStatus=NOT_EVALUATED
promotionStatus=NOT_EVALUATED
publicEvidenceStatus=NOT_EVALUATED
```

Die domänenspezifischen Certificates behalten ihre jeweilige Stärke. Das
Finite-Difference-Witness validiert den endlichen beobachteten Datensatz und den
Holdout, beweist aber keine eindeutige unendliche Folge.

## Reproduzierbare Kampagne

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

Der Run-Fingerprint bindet pro Domäne die Manifest- und
Export-Verifikationsidentität. Alle drei Fingerprints müssen identisch sein.

Lokale Reproduktion:

```bash
./gradlew :regelsuche-release:runDomainGenericQualification
```

Gezielte Tests:

```bash
./gradlew :regelsuche-release:test \
  --tests de.regelsuche.release.DomainGenericQualificationRunnerTest
```

## Negative Charakterisierung

Die Tests halten insbesondere fest:

- zwei Kopien derselben Domäne erfüllen keine Mehrdomänen-Qualification;
- eine refutierte zweite Domäne kann den Claim nicht autorisieren;
- zwei statt drei saubere Läufe bleiben blockiert;
- manipulierte Report-Hashes werden abgewiesen;
- der bestehende algebraische Autonomie-Claim bleibt immer unautorisiert.

## Schemas

- `docs/schemas/regelsuche-domain-generic-evidence-profile-catalog-v1.schema.json`
- `docs/schemas/regelsuche-domain-generic-discovery-qualification-v1.schema.json`
- `docs/schemas/regelsuche-domain-generic-discovery-qualification-run-v1.schema.json`

## Verbleibender Umfang von #224

Dieses Profile qualifiziert die bereits generische Generation-, Search-,
Counterexample-, Validation-, Certificate- und Evidence-Schicht. Noch offen
bleiben die Portierung ausdrucksspezifischer App- und Persistenzflächen sowie die
produktive Wiederverwendung der verifizierten Snapshots in weiterführenden
Novelty-, Proof-, Release- und Public-Evidence-Komponenten. Diese späteren Gates
dürfen ihre bisherigen Bedeutungen nicht verlieren.
