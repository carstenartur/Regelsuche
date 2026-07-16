# Release Readiness für Regelsuche 0.2

Regelsuche verwendet getrennte, versionierte Evidence Profiles. Ein Profil autorisiert genau den Claim, den es beschreibt. Ein erfolgreiches niedrigeres Profil darf keinen stärkeren Claim freigeben.

Die verwendeten Begriffe sind im zentralen [Glossar](glossary.md) definiert.

## Profile

| Profil | Claim | Autorisiert den Autonomie-Claim? |
|---|---|---:|
| `SEARCH_REPRODUCIBILITY` | reproduzierbare targetfreie Suche unter gepinnten Inputs | nein |
| `HIDDEN_RULE_REDISCOVERY` | leak-freie Wiederentdeckung zurückgehaltener bekannter Regeln | nein |
| `OPEN_TARGET_DISCOVERY` | targetfreie Candidate Formation mit Validation, Novelty, Proof und Lifecycle | nein |
| `AUTONOMOUS_CAMPAIGN` | autonome Generation, unabhängige Qualifikation und Retention mathematischer Kandidaten | ja |
| `EXTERNAL_NOVELTY_REVIEW` | extern geprüfte mathematische Novelty | nein |

Nur `AUTONOMOUS_CAMPAIGN` kann den Release-0.2-Autonomie-Claim autorisieren. Externe Novelty, Promotion und Public Evidence bleiben separate Entscheidungen.

## Referenzläufe

Der Campaign-only-Diagnoselauf startet drei reale Production Campaigns mit unterschiedlichen Parallelitätsgraden und bleibt für nicht bereitgestellte Qualification Evidence fail-closed:

```bash
./gradlew :regelsuche-release:runReleaseReadiness
```

Der vollständige qualifizierte Referenzlauf erzeugt zusätzlich den leak-freien Hidden-Rule-Bericht und qualifiziert exakt den von der Production Campaign retained Kandidaten:

```bash
./gradlew :regelsuche-release:runQualifiedReleaseReadinessWithHiddenRuleEvidence
```

Die qualifizierte Ausgabe liegt unter:

```text
regelsuche-release/build/reports/release-readiness-qualified/
```

Der strikte Autonomie-Gate-Befehl lautet:

```bash
./gradlew :regelsuche-release:verifyAutonomousCampaignRelease
```

Er schlägt fehl, wenn `AUTONOMOUS_CAMPAIGN` nicht `READY` ist. Ein `BLOCKED`-Report des normalen Diagnoselaufs ist dagegen ein gültiges fail-closed Ergebnis.

## Gebundene Production-Campaign-Evidence

Der Campaign-Adapter übernimmt ausschließlich Fakten aus der vollständigen Production Campaign:

- ein versionierter targetfreier Research Brief;
- zwei Seed-Familien und zwölf immutable Observations;
- Aggregate Mining mit exakter Kandidaten-Lineage;
- ein expliziter Zero-output-Reject;
- frische positive und negative Development-Holdouts;
- mehrere Counterexample-Strategien;
- Projekt-Novelty;
- symbolische Proof-Evidence;
- konservativer Lifecycle-Handoff;
- vollständige Budgets, Feedback und Campaign-Manifest;
- drei reale Clean Runs mit identischen kanonischen Outputs.

Nicht vorhandene oder nicht gebundene Evidence wird als `false`, `0`, `NOT_EVALUATED` oder über einen versionierten `NOT_PROVIDED`-Hash retained. Kein Adapter setzt eine Release-Anforderung aufgrund eines geschlossenen Issues oder einer Dokumentationsbehauptung auf bestanden.

## Unabhängige Kandidatenqualifikation

Issue #359 ergänzt für denselben retained Kandidaten genau die drei zuvor fehlenden Release-Achsen.

### Vollständig zurückgehaltener Strukturcluster

Die Qualification Suite verwendet den vorab benannten Cluster:

```text
composite-common-factor-gap-two/v1
```

Die zwölf positiven Fälle verwenden zusammengesetzte Faktoren, die weder in der Candidate Formation noch in den Development-Holdouts vorkommen. Der Split-Audit vergleicht exakte kanonische Werte und Alpha-Strukturen gegen sämtliche Upstream-Ausdrücke.

Input und erwartetes Target desselben positiven Falls dürfen mathematisch äquivalent sein; das ist die beabsichtigte Aufgabe des Falls. Kollisionen zwischen verschiedenen Qualification-Fällen oder mit Formation beziehungsweise Development bleiben blockierend.

### Ausgeglichene 12/12-Suite

Die versionierte Suite enthält:

- zwölf positive Fälle mit strukturell unterschiedlichen zusammengesetzten Faktoren;
- zwölf negative Fälle mit falschem Abstand, abweichendem Koeffizienten, Subtraktion oder nicht identischen Faktoren;
- vollständige Ausführung ohne mandatory skips;
- null refuting holdouts;
- null surviving counterexamples;
- retained Split-, Evaluation- und Lineage-Hashes.

### Paired Held-out Utility

Jeder positive Fall wird unter identischen Budgets zweimal gesucht:

1. mit dem unveränderten Baseline-Inventar;
2. mit genau dem retained, dynamisch kompilierten Kandidaten.

Material Gain bedeutet mindestens einen neu gelösten Fall, einen kürzeren Pfad oder weniger erkundete Zustände. Jede Verschlechterung bei Erreichbarkeit, Pfadlänge oder erkundeten Zuständen wird als Correctness beziehungsweise Utility Regression sichtbar und blockiert das Gate.

### Reproduzierbarkeit

Der Release Runner führt die Production Campaign und die Kandidatenqualifikation mit Parallelitätsgraden 1, 2 und 4 aus. Qualification Evidence und Run-Hash müssen in allen drei Läufen identisch sein. Zusätzlich vergleicht CI den vollständigen Gradle- und Docker-Output byteweise.

## Artefakte

Der qualifizierte Lauf erzeugt:

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

Alle Qualification-Artefakte sind über SHA-256-Hashes mit Campaign Manifest, Research Brief, Inventar, Modell, Mining Evidence, exakter Observation Lineage und dem finalen Release Run verbunden.

Die JSON-Verträge liegen unter `docs/schemas/`:

- [`regelsuche.autonomous-candidate-qualification-suite/v1`](schemas/regelsuche-autonomous-candidate-qualification-suite-v1.schema.json)
- [`regelsuche.autonomous-candidate-qualification-split/v1`](schemas/regelsuche-autonomous-candidate-qualification-split-v1.schema.json)
- [`regelsuche.open-target-conjecture-evaluation/v1`](schemas/regelsuche-open-target-conjecture-evaluation-v1.schema.json)
- [`regelsuche.autonomous-candidate-qualified-utility/v1`](schemas/regelsuche-autonomous-candidate-qualified-utility-v1.schema.json)
- [`regelsuche.autonomous-candidate-qualification/v1`](schemas/regelsuche-autonomous-candidate-qualification-v1.schema.json)
- [`regelsuche.autonomous-candidate-qualification-run/v1`](schemas/regelsuche-autonomous-candidate-qualification-run-v1.schema.json)
- [`regelsuche.release-readiness-run/v1`](schemas/regelsuche-release-readiness-run-v1.schema.json)

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

Das Runtime-Image enthält ausschließlich die Java-21-`installDist`-Ausgabe. Der Repository-Quellbaum und Gradle sind nicht Bestandteil des Runtime-Images.

## Aktuelle Matrix

Mit gebundener Hidden-Rule- und Qualification Evidence gilt:

- `SEARCH_REPRODUCIBILITY`: `READY`
- `HIDDEN_RULE_REDISCOVERY`: `READY`
- `OPEN_TARGET_DISCOVERY`: `READY`
- `AUTONOMOUS_CAMPAIGN`: `READY`
- `EXTERNAL_NOVELTY_REVIEW`: `BLOCKED`

Damit ist der interne, algebraische Autonomie-Claim technisch autorisiert. Das ist keine Behauptung weltweit neuer Mathematik. Externe Novelty und Public Evidence benötigen weiterhin eigenständige Review-Artefakte.

## CI-Vertrag

Der Workflow `Release Readiness`:

1. erzeugt den bestehenden Hidden-Rule-Benchmarkbericht;
2. führt drei reale Production Campaigns und drei Kandidatenqualifikationen aus;
3. verlangt sämtliche Campaign-, Qualification- und Release-Artefakte;
4. prüft Hidden-Rule-, Open-Target- und Autonomous-Campaign-Profile als `READY`;
5. prüft 12/12-Ausführung, Split-Trennung, null Refutations, null Counterexamples und positive Paired Utility ohne Regression;
6. baut `Dockerfile.release-readiness` und führt denselben qualifizierten Gate-Lauf aus;
7. vergleicht Gradle- und Docker-Evidence byteweise;
8. archiviert beide Evidence-Sets und Diagnosen.

Promotion, Public Evidence, externe Novelty und unabhängig bewertete Interestingness bleiben außerhalb dieses Release-Gates.
