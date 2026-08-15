# Release Readiness und Evidence Profiles

Regelsuche verwendet getrennte, versionierte Evidence Profiles. Ein Profil
entscheidet ausschließlich über den Claim, für den es definiert wurde. Ein
erfolgreiches Profil darf keine stärkere, unabhängige Aussage freigeben.

Die öffentliche Capability-Matrix verwendet für einen vollständig erfüllten,
reproduzierten internen Claim den Status `QUALIFIED`. Innerhalb eines einzelnen
Release-Readiness-Reports kann das zugehörige Profil als `READY` erscheinen.
Diese Begriffe beschreiben unterschiedliche Ebenen und sind keine Synonyme für
externe mathematische Neuheit oder produktive Betriebsreife.

## Profile

| Profil | Autorisierte Aussage | Autorisiert externe Neuheit? |
| --- | --- | ---: |
| `SEARCH_REPRODUCIBILITY` | reproduzierbare targetfreie Suche unter gebundenen Inputs | nein |
| `HIDDEN_RULE_REDISCOVERY` | leak-freie Wiederentdeckung zurückgehaltener bekannter Regeln | nein |
| `OPEN_TARGET_DISCOVERY` | targetfreie Candidate Formation mit getrennten Qualification-Stufen | nein |
| `AUTONOMOUS_CAMPAIGN` | autonome Generation, unabhängige interne Qualifikation und Retention | nein |
| `EXTERNAL_NOVELTY_REVIEW` | externe Neuheitsentscheidung innerhalb eines dokumentierten Suchumfangs | nur dieses Profil |

Promotion, Public Evidence, formal bestätigter Proof und unabhängig bewertete
Interessantheit besitzen eigene Verträge.

## Autoritative Ausführung

### Diagnostischer Campaign-Lauf

```bash
./gradlew :regelsuche-release:runReleaseReadiness
```

Dieser Lauf erzeugt die Campaign- und Profilanalyse. Fehlt erforderliche
Qualification Evidence, ist ein `BLOCKED`-Report das korrekte Ergebnis.

### Qualifizierter Referenzlauf

```bash
./gradlew :regelsuche-release:runQualifiedReleaseReadinessWithHiddenRuleEvidence
```

Der Lauf kombiniert:

- die vollständige targetfreie Production Campaign;
- leak-freie Hidden-Rule-Evidence;
- die unabhängige Qualification des exakt retained Kandidaten;
- gepaarte Held-out-Utility unter identischen Budgets;
- mehrere deterministische Ausführungen.

Ausgabe:

```text
regelsuche-release/build/reports/release-readiness-qualified/
```

### Striktes Autonomie-Gate

```bash
./gradlew :regelsuche-release:verifyAutonomousCampaignRelease
```

Das Gate schlägt fehl, wenn das Profil `AUTONOMOUS_CAMPAIGN` nicht `READY` ist.
Es verändert keine Candidate-, Corpus- oder Threshold-Evidence.

### Repositoryweiter Vertrag

Release Readiness ist Teil des checkout-eigenen Verifikationssystems. Der
vollständige CI-Aufruf lautet:

```bash
./gradlew --no-configuration-cache ciCheck
```

Es gibt keinen separaten fachlichen GitHub-Workflow für Release Readiness.
GitHub Actions provisioniert die Umgebung, führt den Checkout-Vertrag aus und
veröffentlicht dessen Artefakte.

## Gebundene Production-Campaign-Evidence

Der Release-Adapter darf ausschließlich retained Fakten übernehmen, darunter:

- versionierter targetfreier Research Brief;
- Seed-Familien und immutable Observations;
- Aggregate Mining mit exakter Kandidaten-Lineage;
- Nullausgaben und Rejects;
- positive und negative Development-Holdouts;
- mehrere Counterexample-Strategien;
- Projekt-Novelty;
- Proof-Obligationen und vorhandene Proof-Evidence;
- konservativer Lifecycle-Handoff;
- vollständige Budgets, Feedback und Campaign-Manifest;
- reproduzierte kanonische Outputs.

Fehlende oder nicht gebundene Evidence bleibt `false`, `0`, `BLOCKED`,
`NOT_EVALUATED` oder erhält eine explizite `NOT_PROVIDED`-Identität. Ein
geschlossenes Issue oder eine Dokumentationsbehauptung kann keine
Release-Anforderung erfüllen.

## Unabhängige Kandidatenqualifikation

Die Qualification bezieht sich exakt auf den von der Production Campaign
retained Kandidaten. Ein nachträglicher Ersatzkandidat ist nicht zulässig.

### Zurückgehaltener Strukturcluster

Die Referenzqualifikation verwendet einen vorab gebundenen Cluster mit
zusammengesetzten Faktoren, die weder in Candidate Formation noch in den
Development-Holdouts vorkommen. Ein Split-Audit vergleicht unter anderem:

- Fallidentitäten;
- kanonische Werte;
- Exact- und Alpha-Strukturen;
- Inputs und Targets;
- Formation- und Development-Oberflächen.

Mathematische Äquivalenz von Input und erwartetem Target innerhalb desselben
positiven Falls ist die Aufgabe des Falls und keine Split-Kollision. Kollisionen
zwischen verschiedenen Fällen oder mit früheren Stufen bleiben blockierend.

### Ausgeglichene Qualification Suite

Die retained Referenzsuite enthält:

- zwölf positive Fälle;
- zwölf negative Fälle;
- vollständige mandatory Ausführung;
- null refuting Holdouts;
- null surviving Counterexamples;
- gebundene Split-, Evaluation- und Lineage-Hashes.

Die Zahlen beschreiben diese konkrete Suite und dürfen nicht als allgemeine
Fehlerrate interpretiert werden.

### Paired Held-out Utility

Jeder positive Fall wird unter identischen Budgets zweimal ausgeführt:

1. mit dem eingefrorenen Baseline-Inventar;
2. mit genau dem retained, dynamisch kompilierten Kandidaten.

Material Gain bedeutet innerhalb dieses Vertrags mindestens eines von:

- ein zuvor nicht erreichter Fall wird erreicht;
- der retained Pfad wird kürzer;
- es werden weniger Zustände erkundet.

Regressionen bei Erreichbarkeit, Pfadlänge oder erkundeten Zuständen bleiben
sichtbar und blockieren das Gate. Laufzeit allein autorisiert keine Utility-
Aussage.

## Reproduzierbarkeit

Der qualifizierte Runner führt Campaign und Qualification mit mehreren
Parallelitätsgraden aus. Kanonische Qualification Evidence und Run-Hash müssen
identisch bleiben.

Zusätzlich vergleicht der Checkout-Vertrag Gradle- und Runtime-Image-Ausgaben
byteweise, soweit der jeweilige Vertrag Byteidentität fordert. Temporäre Pfade,
Wandzeit und Plattformadressen gehören nicht in kanonische Artefakte.

## Artefakte

Ein qualifizierter Lauf erzeugt unter anderem:

```text
profiles.json
evidence-summary.json
hidden-rule-release-evidence.json
release-readiness-report.json
release-readiness-run.json
campaign/
qualification/
  qualification-suite.json
  qualification-split-audit.json
  qualification-evaluation.json
  qualification-utility.json
  candidate-qualification-evidence.json
  candidate-qualification-run.json
```

Die Qualification-Artefakte sind über SHA-256-Wurzeln mit Campaign Manifest,
Research Brief, Inventar, Modell, Mining Evidence, Observation Lineage und dem
finalen Release Run verbunden.

Die maschinenlesbaren Verträge sind im
[Schema-Katalog](schema-catalog.md) gruppiert; die vollständigen Dateien liegen
unter [`docs/schemas/`](schemas/).

## Docker-Reproduktion

```bash
docker build \
  -f Dockerfile.release-readiness \
  -t regelsuche-release-readiness .

mkdir -p build/release-readiness-docker-output
chmod 0777 build/release-readiness-docker-output

docker run --rm \
  -v "$PWD/build/release-readiness-docker-output:/output" \
  -v "$PWD/app/build/reports/hidden-rule-pilot/report.json:/input/hidden-rule-report.json:ro" \
  regelsuche-release-readiness \
  /output \
  --hidden-rule-report /input/hidden-rule-report.json \
  --qualify-candidate \
  --require-ready
```

Das Runtime-Image enthält die Java-25-Distribution, nicht den Repository-
Quellbaum und nicht Gradle. Die Ausgabe wird gegen den checkout-lokalen Lauf
verglichen.

## Aktuelle interne Matrix

Für die gebundene Referenz-Evidence gilt innerhalb des Release-Readiness-
Reports:

- `SEARCH_REPRODUCIBILITY`: `READY`
- `HIDDEN_RULE_REDISCOVERY`: `READY`
- `OPEN_TARGET_DISCOVERY`: `READY`
- `AUTONOMOUS_CAMPAIGN`: `READY`
- `EXTERNAL_NOVELTY_REVIEW`: `BLOCKED`

Die generierte öffentliche Capability-Matrix bildet den internen
Autonomie-Claim als `AUTONOMOUS_CAMPAIGN = QUALIFIED` ab.

Damit ist ausschließlich der eng definierte interne algebraische
Autonomie-Claim autorisiert. Nicht autorisiert sind:

- externe mathematische Neuheit;
- formaler Beweis des retained Produktionskandidaten, sofern nicht separat
  bestätigt;
- fachliche Interessantheit;
- Promotion in einen aktiven autoritativen Bestand;
- Public Evidence;
- allgemeine Überlegenheit gegenüber anderen Systemen.

## Prüfinvarianten

Der checkout-eigene Vertrag prüft insbesondere:

1. vollständige Campaign-, Qualification- und Release-Artefakte;
2. Profilstatus und explizite Blocker;
3. vollständige positive und negative Qualification-Ausführung;
4. Split-Trennung und Lineage-Bindung;
5. null retained Refutations und Counterexamples für einen positiven Claim;
6. gepaarte Utility ohne Regression;
7. deterministische Mehrfachausführung;
8. byteidentische Containerreproduktion;
9. unveränderte Claim-Grenzen für externe Novelty, Promotion und Public
   Evidence.

## Siehe auch

- [Generierte Capability-Matrix](generated/capability-status.md)
- [Discovery- und Forschungsstand](discovery-status.md)
- [Scientific Reproducibility](scientific-reproducibility.md)
- [Independent Reproduction](independent-reproduction.md)
- [Glossar](glossary.md)
