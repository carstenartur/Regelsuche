# Aktueller Discovery-Stand

Stand: 16. Juli 2026

Diese Seite fasst den gemessenen Forschungsstand von Regelsuche zusammen. Sie trennt technische Suchverbesserungen, Rediscovery, projektinterne Open-Target-Hypothesen, den internen Autonomie-Claim und mögliche externe mathematische Neuheit.

## Kurzfassung

Regelsuche besitzt inzwischen eine durch Tests und CI abgesicherte, targetfreie Produktionskette:

1. versionierter Research Brief ohne Zielausdruck;
2. zwei Seed-Generatorfamilien und zwölf immutable Observations;
3. Aggregate Mining aus selbst erzeugten Suchgraphen;
4. Generalisierung zu einer parametrisierten Hypothese;
5. Kompilierung als quarantänisierter ausführbarer Operator;
6. frische positive und negative Holdouts;
7. deterministische Gegenbeispielsuche;
8. projektinterne Exact-/Alpha-Neuheitsprüfung;
9. versionierte symbolische Proof-Obligation;
10. konservativer Lifecycle-Handoff;
11. vollständige Ressourcenbilanz, Feedback und nächster Plan;
12. unabhängige 12/12-Releasequalifikation und Paired Held-out Utility;
13. drei semantisch identische Clean Runs sowie bytegleiche Gradle-/Docker-Evidence.

Das maschinengeprüfte Profil `AUTONOMOUS_CAMPAIGN` ist damit für den algebraischen Regelsuche-0.2-Claim `READY`. Diese Aussage bedeutet, dass Regelsuche den retained Kandidaten targetfrei erzeugt, unabhängig qualifiziert und reproduzierbar gebunden hat. Sie bedeutet nicht, dass der Kandidat weltweit neue Mathematik darstellt.

## Gemessene Ergebnisse

| Stufe | Ergebnis | Bedeutung | Keine daraus folgende Behauptung |
| --- | --- | --- | --- |
| Zielgerichtete Suchsteuerung (#219) | TEST von 7 auf 5 erkundete Zustände, also 28,5 % Verbesserung ohne Korrektheitsverlust | Erklärbare TRAIN-Evidenz beeinflusst die reale Frontier-Priorität | Noch keine mathematische Entdeckung |
| Hidden-Rule-Rediscovery (#227) | 19 von 20 akzeptierte ausführbare Rediscoveries über 4 Familien; 0 False Positives unter 38 ausgeführten Negativ-Holdouts; 2 Prüfungen explizit übersprungen | Die Discovery-Kette kann bekannte, vor dem Lauf entfernte Regeln aus atomaren Pfaden wiederaufbauen | Keine externe Neuheit; die Referenzregeln waren post-hoc bekannt |
| Open-Target-Formation (#221) | Parametrisierte Hypothese aus mehreren alpha-distinkten, untargeteten Konvergenzbeobachtungen | Kandidatenbildung funktioniert ohne Zielausdruck oder versteckte erwartete Antwort | Noch keine Wahrheit, Novelty oder Promotion |
| Cross-Family-Transfer (#222/#326) | Familienblinde Bridge-Bildung, vollständig zurückgehaltene Familie und gepaarte Utility | Strukturelle Kandidaten können über Familiengrenzen hinweg geprüft werden | Kein universeller Nutzenbeleg |
| Production Campaign (#348) | Zwei Seed-Familien, 12 Observations, Aggregate Mining, exakte Lineage, Zero-output-Reject, Validation, Counterexample Search, Projekt-Novelty, Proof, Lifecycle, Feedback und vollständiges Manifest | Die gesamte targetfreie Discovery-Kette läuft unattended und reproduzierbar | Keine externe mathematische Novelty |
| Releasequalifikation (#359) | 12 positive und 12 negative Fälle vollständig ausgeführt; ein vollständig zurückgehaltener Strukturcluster; keine Split-Kollision, kein mandatory skip, keine Refutation, kein surviving counterexample; positive Paired Utility ohne Regression | Derselbe retained Produktionskandidat erfüllt die drei zusätzlichen Release-Achsen | Die Mindestgröße 12/12 ist kein universeller Wahrheitsmaßstab |
| Release-Gate (#226) | `SEARCH_REPRODUCIBILITY`, `HIDDEN_RULE_REDISCOVERY`, `OPEN_TARGET_DISCOVERY` und `AUTONOMOUS_CAMPAIGN` sind `READY`; `EXTERNAL_NOVELTY_REVIEW` bleibt `BLOCKED` | Der interne algebraische Autonomie-Claim ist maschinenautorisiert | Keine Public-Evidence- oder weltweite Neuheitsbehauptung |

## Retained Produktionskandidat

Die Production Campaign hat aus beiden Seed-Familien die parametrisierte Umformung retained:

```text
(A + 2)*x + A*x → (2*A + 2)*x
```

Die Releasequalifikation bindet exakt diesen Kandidaten über Campaign Manifest, Research Brief, Inventar- und Modellhash, Mining Evidence, Observation Lineage, Muster, Parameterrelationen und Annahmen. Eine manuell eingesetzte oder nachträglich ausgewählte Ersatzregel würde das Gate nicht erfüllen.

## Unabhängige Releasequalifikation

### Split-Trennung

Der vollständig zurückgehaltene Strukturcluster `composite-common-factor-gap-two/v1` verwendet zusammengesetzte Faktoren, die in Formation und Development nicht vorkommen. Exakte und Alpha-normalisierte Fingerprints werden gegen alle Upstream-Ausdrücke geprüft.

Die mathematische Gleichheit von Input und erwarteter Ausgabe innerhalb desselben positiven Falls ist beabsichtigt und keine Leakage-Kollision. Gleichheit oder Alpha-Äquivalenz zwischen verschiedenen Fällen beziehungsweise mit Upstream-Evidence blockiert den Split.

### Ausgeglichene Holdouts

- 12 konfigurierte und 12 ausgeführte positive Fälle;
- 12 konfigurierte und 12 ausgeführte negative Fälle;
- 0 mandatory skips;
- 0 refuting holdouts;
- 0 surviving counterexamples;
- keine nachträglich inferierten Annahmen.

### Paired Held-out Utility

Jeder positive Fall wird unter identischen Budgets mit Baseline-Inventar und mit dem exakt retained Kandidaten gesucht. Mindestens ein Fall muss neu gelöst, mit kürzerem Pfad oder mit weniger erkundeten Zuständen erreicht werden. Jede Verschlechterung wird als Regression retained und blockiert das Gate.

## Evidenzstufen

Regelsuche unterscheidet fünf Stufen:

1. **Search improvement:** Ein vorgegebenes Ziel wird effizienter erreicht.
2. **Hidden-rule rediscovery:** Eine bekannte, vor dem Lauf entfernte Regel wird wiederentdeckt.
3. **Inventory-new open-target hypothesis:** Ohne Zielausdruck wird eine gegenüber dem Projektinventar neue Hypothese gebildet und unabhängig geprüft.
4. **Autonomous campaign qualification:** Ein unattended erzeugter Kandidat besteht die versionierte Releasequalifikation und reproduzierbare Evidence-Bindung.
5. **Externally novel mathematics:** Zusätzlich sind Literatur-, Datenbank- und unabhängige fachliche Neuheitsprüfungen erforderlich.

Nur Stufe 5 rechtfertigt einen Anspruch auf weltweit neue Mathematik. Das `READY`-Profil `AUTONOMOUS_CAMPAIGN` ist Stufe 4.

## Statusanzeigen richtig lesen

Statusfelder wie Gültigkeit, Novelty, Proof, Utility, Promotion oder Public Evidence beziehen sich auf einen konkreten Kandidaten und eine konkrete Evidenzstufe. Ein Wert `BLOCKED`, `NOT_EVALUATED` oder `INCONCLUSIVE` ist kein globaler Projektstatus.

Folgende Achsen bleiben getrennt:

- mathematische Gültigkeit;
- projektinterne Neuheit;
- externe Neuheit;
- Interessantheit;
- Suchnutzen;
- Evidenzvollständigkeit;
- Promotion;
- Public Evidence.

## Reproduktion

Der vollständige qualifizierte Referenzlauf lautet:

```bash
./gradlew :regelsuche-release:runQualifiedReleaseReadinessWithHiddenRuleEvidence
```

Der strikte interne Autonomie-Gate-Lauf lautet:

```bash
./gradlew :regelsuche-release:verifyAutonomousCampaignRelease
```

Die vollständigen Artefakte, JSON-Schemas und Docker-Schritte sind in [Release Readiness für Regelsuche 0.2](release-readiness.md) dokumentiert.

## Nächste Forschungs- und Architekturarbeit

Nach Abschluss von #359 und #226 sind die nächsten sinnvollen Arbeitsblöcke voneinander unabhängig:

1. **#235 – Vergleichsbenchmarks:** faire Baselines pro Capability und Informationsregime, statt eines irreführenden Gesamt-Leaderboards.
2. **#233/#234 – Solver-neutrale IR und Portfolio:** versionierte Obligations, Capability Matching, Backend-Auswahl und getrennte Such-/Validation-/Proof-Ergebnisse.
3. **#224 – Domain-generische Discovery:** Expression Rewrites als erste Implementierung einer allgemeinen `DiscoveryDomain` und anschließend eine zweite Objektklasse.
4. **#220 – Evolutionäre Operatorsuche:** erst mit strikt getrennten Generation-, Validation- und finalen TEST-Splits.
5. **#332 – unabhängige Interestingness-Evaluation:** blinde Expert Reviews realer Kandidaten ohne Rückkopplung in Formation oder Calibration/TEST-Leakage.
6. **#104 – öffentliches Plugin-Ökosystem:** veröffentlichter Index, Distribution, Kompatibilitätsmatrix und Supply-Chain-Policy.

Das übergeordnete Epic #102 bleibt offen, weil autonome mathematische Discovery ein fortlaufendes Forschungsprogramm und kein einzelner Infrastruktur-PR ist.

## Verbindliche wissenschaftliche Grenzen

- Suchpfade und Kandidaten müssen von Regelsuche selbst erzeugt werden.
- Oracles und Prover validieren oder widerlegen; sie konstruieren nicht die Hypothese.
- Ziel-, Referenz-, Familien- und Qualification-Informationen dürfen nicht in Open-Target-Mining einfließen.
- Konfigurierte, ausgeführte und übersprungene Prüfungen werden getrennt bilanziert.
- Unvollständige Evidenz darf nicht vakuos als bestanden gelten.
- Promotion und Public Evidence benötigen ihre eigenen Novelty-, Proof-, Ablation- und Provenance-Gates.
- Eine externe Neuheitsbehauptung braucht eine separate externe Prüfung.
