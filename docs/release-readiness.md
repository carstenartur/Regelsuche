# Release Readiness für Regelsuche 0.2

Regelsuche verwendet getrennte, versionierte Evidence Profiles. Ein Profil autorisiert genau den Claim, den es beschreibt. Ein erfolgreiches niedrigeres Profil darf keinen stärkeren Claim freigeben.

Die verwendeten Begriffe sind im zentralen [Glossar](glossary.md) definiert.

## Profile

| Profil | Claim | Autorisiert den Autonomie-Claim? |
|---|---|---:|
| `SEARCH_REPRODUCIBILITY` | reproduzierbare targetfreie Suche unter gepinnten Inputs | nein |
| `HIDDEN_RULE_REDISCOVERY` | leak-freie Wiederentdeckung zurückgehaltener bekannter Regeln | nein |
| `OPEN_TARGET_DISCOVERY` | targetfreie Candidate Formation mit Validation, Novelty, Proof und Lifecycle | nein |
| `AUTONOMOUS_CAMPAIGN` | autonome Generation, Qualifikation und Retention mathematischer Kandidaten | ja |
| `EXTERNAL_NOVELTY_REVIEW` | extern geprüfte mathematische Novelty | nein |

Nur `AUTONOMOUS_CAMPAIGN` kann den Release-0.2-Autonomie-Claim autorisieren. Externe Novelty bleibt ein separater optionaler Claim.

## Ausführung

Der Campaign-only-Matrixlauf startet drei reale Production Campaigns mit unterschiedlichen Parallelitätsgraden und bleibt für nicht bereitgestellte Evidence fail-closed:

```bash
./gradlew :regelsuche-release:runReleaseReadiness
```

Der vollständige aktuelle Referenzlauf erzeugt zusätzlich den leak-freien Hidden-Rule-Bericht aus #227 und bindet ihn als eigenständige Evidence-Achse:

```bash
./gradlew :regelsuche-release:runReleaseReadinessWithHiddenRuleEvidence
```

Ausgabe:

```text
regelsuche-release/build/reports/release-readiness/
```

Enthalten sind:

- `profiles.json` — versionierter Profilkatalog;
- `evidence-summary.json` — aus retained Campaign-Artefakten abgeleitete Fakten;
- `hidden-rule-release-evidence.json` — aus dem retained Hidden-Rule-Benchmark abgeleitete, hashgebundene Release-Fakten;
- `release-readiness-report.json` — alle Soll-/Ist-Prüfungen und Blocker;
- `release-readiness-run.json` — äußeres hashgebundenes Run-Manifest;
- `campaign/` — vollständige retained Production-Campaign-Evidence.

Der strikte Autonomie-Gate-Befehl lautet:

```bash
./gradlew :regelsuche-release:verifyAutonomousCampaignRelease
```

Er schlägt fehl, solange `AUTONOMOUS_CAMPAIGN` nicht `READY` ist. Ein `BLOCKED`-Report ist dagegen ein gültiges Ergebnis des normalen Matrixlaufs und kein erfundener Buildfehler.

## Aktuell gebundene Campaign-Evidence

Der Campaign-Adapter übernimmt ausschließlich Fakten aus der vollständigen Production Campaign:

- ein versionierter targetfreier Research Brief;
- zwei Seed-Familien und zwölf immutable Observations;
- Aggregate Mining mit exakter Kandidaten-Lineage;
- ein expliziter Zero-output-Reject;
- positive und negative Holdouts;
- mehrere Counterexample-Strategien;
- Projekt-Novelty;
- symbolische Proof-Evidence;
- konservativer Lifecycle-Handoff;
- vollständige Budgets, Feedback und Campaign-Manifest;
- drei reale Clean Runs mit identischen kanonischen Outputs.

Nicht vorhandene oder nicht gebundene Evidence wird als `false`, `0`, `NOT_EVALUATED` oder über einen versionierten `NOT_PROVIDED`-Hash retained. Kein Adapter setzt eine Release-Anforderung aufgrund eines geschlossenen Issues oder einer Dokumentationsbehauptung auf bestanden.

## Hidden-Rule-Rediscovery

`HiddenRuleBenchmarkReleaseEvidence` liest den bestehenden kanonischen Bericht `regelsuche.hidden-rule-benchmark/v2`. Der Adapter führt keine zweite Rediscovery aus, sondern prüft den retained Report erneut:

- deklarierte Summary-Werte stimmen mit den einzelnen Fällen überein;
- vier Familien und zwanzig Fälle sind vorhanden;
- Split-Kollisionen und Leakage-Verstöße bleiben sichtbar;
- konfigurierte negative Holdouts entsprechen ausgeführten plus explizit übersprungenen Fällen;
- akzeptierte Fälle besitzen vollständige, bestandene Holdouts und Validation;
- False Positives blockieren das Profil;
- mindestens eine akzeptierte ausführbare Rediscovery ist retained;
- Source-Report und abgeleitete Release-Evidence besitzen getrennte kanonische Hashes.

Der aktuelle #227-Referenzbericht enthält 20 Fälle aus 4 Familien, 19 akzeptierte ausführbare Rediscoveries, 40 konfigurierte negative Holdouts, 38 ausgeführte, 2 explizit übersprungene und 0 False Positives. Damit wird `HIDDEN_RULE_REDISCOVERY` `READY`, ohne den Autonomie-Claim zu beeinflussen.

## Aktueller Autonomie-Status

Der technische Production-Campaign-Nachweis ist abgeschlossen. Der stärkere Release-Claim bleibt dennoch fail-closed blockiert, bis dieselbe retained Kandidatenlinie zusätzlich besitzt:

1. mindestens eine vollständig zurückgehaltene Familie oder einen vollständig zurückgehaltenen Strukturcluster;
2. eine versionierte, ausgeglichene Release-Holdout-Suite mit mindestens zwölf positiven und zwölf negativen Fällen, vollständig ausgeführt und ohne Refutation;
3. eine Paired Held-out Utility-Auswertung mit positivem, vorab definiertem Nutzen ohne Korrektheitsverlust.

Diese drei Achsen gehören zu Issue #359. Die Zahl der Holdouts ist kein universeller mathematischer Wahrheitsmaßstab. Sie ist eine Mindestgröße für dieses Release-Profil und wird durch strukturelle Trennung, Positiv-/Negativbalance, Counterexample Search und Proof ergänzt.

## Bewusste Entkopplungen

### Domain-generische Discovery

Die generische `DiscoveryDomain<State, Candidate, Certificate>`-Architektur aus Issue #224 ist ein eigenständiges Erweiterungsziel. Regelsuche 0.2 darf den nachgewiesenen algebraischen Autonomie-Claim freigeben, ohne bereits eine zweite mathematische Objektklasse zu unterstützen.

### Interestingness

Interestingness ist weder Wahrheit noch Novelty noch Proof. Die Softwareverträge können intern geprüft werden; unabhängig bewertete empirische Interestingness-Evidence bleibt Issue #332.

### Externe Novelty und Public Evidence

Projekt-Novelty wird intern geprüft. Externe mathematische Novelty und Public Evidence benötigen eigene Review-Artefakte und bleiben `NOT_EVALUATED`, solange diese fehlen.

## CI-Vertrag

Der Workflow `Release Readiness`:

1. erzeugt den bestehenden Hidden-Rule-Benchmarkbericht;
2. führt drei reale Production Campaigns aus;
3. bindet Campaign- und Hidden-Rule-Evidence über getrennte Hashes;
4. verlangt alle Release-, Benchmark- und Campaign-Artefakte;
5. prüft `HIDDEN_RULE_REDISCOVERY` und `OPEN_TARGET_DISCOVERY` als `READY`;
6. prüft, dass `AUTONOMOUS_CAMPAIGN` weiterhin exakt durch die drei #359-Achsen blockiert ist;
7. archiviert Evidence und Diagnosen.

Issue #359 muss denselben unveränderten Autonomie-Gate-Vertrag auf `READY` bringen; es entsteht kein zweiter Release-Pfad.
