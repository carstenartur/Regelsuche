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

Der vollständige Matrixlauf startet drei reale Production Campaigns mit unterschiedlichen Parallelitätsgraden, vergleicht ihre kanonischen Manifest-Hashes und erzeugt die Release-Evidence:

```bash
./gradlew :regelsuche-release:runReleaseReadiness
```

Ausgabe:

```text
regelsuche-release/build/reports/release-readiness/
```

Enthalten sind:

- `profiles.json` — versionierter Profilkatalog;
- `evidence-summary.json` — aus retained Campaign-Artefakten abgeleitete Fakten;
- `release-readiness-report.json` — alle Soll-/Ist-Prüfungen und Blocker;
- `release-readiness-run.json` — äußeres hashgebundenes Run-Manifest;
- `campaign/` — vollständige retained Production-Campaign-Evidence.

Der strikte Gate-Befehl lautet:

```bash
./gradlew :regelsuche-release:verifyAutonomousCampaignRelease
```

Er schlägt fehl, solange `AUTONOMOUS_CAMPAIGN` nicht `READY` ist. Ein `BLOCKED`-Report ist dagegen ein gültiges Ergebnis des normalen Matrixlaufs und kein erfundener Buildfehler.

## Aktuell gebundene Evidence

Der Release-Adapter übernimmt ausschließlich Fakten aus der vollständigen Production Campaign:

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

Nicht vorhandene oder nicht gebundene Evidence wird als `false`, `0` oder `NOT_EVALUATED` retained. Der Adapter setzt keine Release-Anforderung aufgrund eines geschlossenen Issues oder einer Dokumentationsbehauptung auf bestanden.

## Aktueller Autonomie-Status

Der technische Production-Campaign-Nachweis ist abgeschlossen. Der stärkere Release-Claim bleibt dennoch fail-closed blockiert, bis dieselbe retained Kandidatenlinie zusätzlich besitzt:

1. mindestens eine vollständig zurückgehaltene Familie oder einen vollständig zurückgehaltenen Strukturcluster;
2. eine versionierte, ausgeglichene Release-Holdout-Suite mit mindestens zwölf positiven und zwölf negativen Fällen, vollständig ausgeführt und ohne Refutation;
3. eine Paired Held-out Utility-Auswertung mit positivem, vorab definiertem Nutzen ohne Korrektheitsverlust.

Die Zahl der Holdouts ist kein universeller mathematischer Wahrheitsmaßstab. Sie ist eine Mindestgröße für dieses Release-Profil und wird durch strukturelle Trennung, Positiv-/Negativbalance, Counterexample Search und Proof ergänzt.

## Bewusste Entkopplungen

### Domain-generische Discovery

Die generische `DiscoveryDomain<State, Candidate, Certificate>`-Architektur aus Issue #224 ist ein eigenständiges Erweiterungsziel. Regelsuche 0.2 darf den nachgewiesenen algebraischen Autonomie-Claim freigeben, ohne bereits eine zweite mathematische Objektklasse zu unterstützen.

### Hidden-rule Rediscovery

Das Profil bleibt getrennt vom Autonomie-Claim. Vorhandene Hidden-rule-Benchmark-Evidence muss über einen eigenen Adapter gebunden werden; der Production-Campaign-Adapter darf sie nicht allein aufgrund von Repository-Status behaupten.

### Interestingness

Interestingness ist weder Wahrheit noch Novelty noch Proof. Die Softwareverträge können intern geprüft werden; unabhängig bewertete empirische Interestingness-Evidence bleibt Issue #332.

### Externe Novelty und Public Evidence

Projekt-Novelty wird intern geprüft. Externe mathematische Novelty und Public Evidence benötigen eigene Review-Artefakte und bleiben `NOT_EVALUATED`, solange diese fehlen.

## CI-Vertrag

Der Workflow `Release Readiness`:

1. kompiliert und testet `:regelsuche-release`;
2. führt den realen Matrixlauf aus;
3. verlangt alle Release- und Campaign-Artefakte;
4. prüft, dass exakt fünf Profile ausgegeben werden;
5. prüft den aktuellen fail-closed Autonomie-Status und seine Blocker;
6. archiviert Evidence und Diagnosen.

Sobald die fehlende Qualifikation implementiert ist, wird derselbe Gate-Vertrag auf `READY` umgestellt; es entsteht kein zweiter Release-Pfad.
