# Wissenschaftliche Reproduzierbarkeit

Regelsuche behandelt Reproduzierbarkeit als Teil des fachlichen Vertrags, nicht
als nachträgliche Sammlung von Logs. Konfiguration, Inputs, Inventar,
ausgeführte Arbeit, Ergebnisse, Fehler und Claim-Entscheidungen werden in
versionierten, hashgebundenen Artefakten festgehalten.

## Begriffe

| Begriff | Bedeutung in Regelsuche |
| --- | --- |
| Wiederholbarkeit | derselbe Checkout und dieselbe Umgebung erzeugen erneut dasselbe kanonische Ergebnis |
| Reproduzierbarkeit | eine deklarierte, gepinnte Umgebung rekonstruiert Ergebnis und Evidence aus den gebundenen Inputs |
| unabhängige Reproduktion | ein separates, begrenztes Artefakt wird ohne Vertrauen in den ursprünglichen Writer verifiziert und ausgeführt |
| diagnostische Vergleichbarkeit | Wandzeit, Durchsatz und Ressourcen werden mit Umgebung erfasst, sind aber nicht zwangsläufig byteidentisch |

Keine dieser Stufen beweist externe mathematische Neuheit oder allgemeine
Gültigkeit außerhalb des jeweiligen Vertrags.

## Reproduzierbare Einheit

Ein reproduzierbarer Lauf bindet mindestens:

- Repository-Revision und Dirty-Worktree-Policy;
- Schema- und Toolrevisionen;
- mathematische Inputs, Annahmen und Split-Identitäten;
- aktives Regelprofil und content-addressed Inventar;
- Such-, Lern-, Solver- und Ressourcenpolitik;
- Seeds und deterministische Tie-Break-Regeln;
- konfigurierte, ausgeführte, übersprungene und verbleibende Arbeit;
- Candidate- und Pfad-Lineage;
- Validation-, Counterexample- und Proof-Ergebnisse;
- Claim-Status, Blocker und Nicht-Claims;
- Hashes und Byteidentitäten aller autoritativen Artefakte.

Eine lose Sammlung erzeugter Dateien ohne Manifest und Cross-Bindings ist kein
vollständiges Reproduktionsartefakt.

## Kanonische und diagnostische Daten

### Kanonisch

Kanonische Felder bestimmen mathematische und wissenschaftliche Identität:

- normalisierte Ausdrücke und Annahmen;
- Regel-, Fall-, Corpus- und Programmidentitäten;
- Budgets und Work-Zähler;
- Ergebnisse und Terminalstatus;
- sortierte Lineage und Ressourcenbilanzen;
- Evidence-Roots und Claim-Entscheidungen.

Wo ein Vertrag Byteidentität fordert, werden UTF-8-Bytes, Feldreihenfolge,
Zahlenformat und Sortierung exakt festgelegt.

### Diagnostisch

Folgende Daten können umgebungsabhängig sein:

- Wandzeit und Durchsatz;
- Hardware, Runner und Container-Host;
- temporäre Pfade und Ports;
- Prozess-IDs;
- ausführliche Logs und nichtkanonische Traces.

Diagnostische Felder werden nicht in mathematische Content-Hashes aufgenommen,
sofern der konkrete Vertrag nichts anderes festlegt.

## Determinismus

Regelsuche reduziert unkontrollierte Quellen von Nichtdeterminismus durch:

- feste Seeds;
- kanonische Sortierung;
- deterministische Tie-Breaks;
- explizite Parallelitäts- und Collection-Grenzen;
- content-addressed Inputs und Inventare;
- vollständige Budget- und Terminalstatusbilanz;
- Wiederholung mit mehreren Parallelitätsgraden;
- Vergleich gegen unabhängige Hash- und Schema-Rekonstruktion.

Parallelität darf Reihenfolge und kanonisches Ergebnis nicht verändern. Ist ein
Algorithmus absichtlich probabilistisch, werden Seed und Samplingvertrag Teil
der Evidence.

## Negative Ergebnisse

Reproduzierbarkeit umfasst auch:

- keine Kandidaten erzeugt;
- Ziel nicht erreicht;
- Gegenbeispiel gefunden;
- Backend nicht verfügbar;
- Timeout oder technische Unterbrechung;
- mandatory Fall übersprungen;
- Nullresultat unter eingefrorenen Schwellen;
- Vergleichstrack verloren.

Solche Ergebnisse werden nicht entfernt oder durch einen Ersatzlauf
überschrieben. Ein vollständiges negatives Ergebnis ist wissenschaftlich
wertvoller als ein selektiv veröffentlichter Erfolg.

## Checkout-eigener Verifikationsvertrag

Der autoritative Repository-Aufruf lautet:

```bash
./gradlew --no-configuration-cache ciCheck
```

Er führt Tests, Evidence-Verifier, Dokumentationsprüfung, Benchmarkverträge,
Containerintegration und Reportgenerierung aus. GitHub Actions enthält keine
alternative fachliche Erfolgsdefinition.

Für fokussierte lokale Prüfung:

```bash
./gradlew check
./gradlew fullCheck
```

Die genaue Taskzuordnung steht in [Testing und Verifikation](testing.md).

## Qualifizierte Referenz-Campaign

```bash
./gradlew :regelsuche-release:runQualifiedReleaseReadinessWithHiddenRuleEvidence
```

Der Lauf erzeugt die gebundene Campaign-, Hidden-Rule-, Qualification-, Utility-
und Release-Readiness-Evidence unter:

```text
regelsuche-release/build/reports/release-readiness-qualified/
```

Mehrere Ausführungen und die Runtime-Image-Reproduktion müssen die kanonischen
Wurzeln erneut erzeugen. Siehe [Release Readiness](release-readiness.md).

## Vergleichende Benchmarks

```bash
bash scripts/run-comparative-benchmarks-verification.sh
```

Vergleichstracks binden Informationszugriff, Inventare, Budgets,
Konfiguration-mal-Fall-Matrizen und Coverage Gaps. Ergebnisse unterschiedlicher
Tracks werden nicht in einem universellen Score zusammengeführt.

Siehe [Comparative Discovery Benchmarks](discovery-benchmarks.md).

## Unabhängiges Reproduktionsartefakt

Das unabhängige Artefakt enthält nur deklarierte Inputs, Runtimebestandteile,
Verifier und erwartete Wurzeln. Es soll eine Reproduktion ermöglichen, ohne den
ursprünglichen Writer oder den vollständigen Entwicklungscheckout als Autorität
zu verwenden.

Der Vertrag umfasst insbesondere:

- Manifest mit vollständiger Dateimitgliedschaft;
- SHA-256 und Bytelängen;
- symlink-sichere Pfadprüfung;
- Schema- und Cross-Artifact-Verifikation;
- Ausführungsreceipt mit explizitem Ergebnis;
- keine implizite Aufwertung von Claims.

Details: [Independent Reproduction](independent-reproduction.md).

## Containerreproduktion

Container dienen als gepinnte Ausführungsumgebung, nicht als eigene
wissenschaftliche Semantik. Das Runtime-Image enthält nur die benötigte
Distribution und Tools; der Writer im Checkout und der Consumer im Container
werden getrennt verglichen.

Ein erfolgreicher Containerlauf muss:

1. die gebundenen Inputs verwenden;
2. dieselben kanonischen Artefakte erzeugen;
3. vollständige Fehler- und Ressourcenstatus retainen;
4. keine Source-Tree-Datei verändern;
5. byteidentische Wurzeln liefern, soweit vorgeschrieben.

## Split- und Leakage-Reproduzierbarkeit

Bei TRAIN-/VALIDATION-/FINAL-TEST- oder CALIBRATION-/TEST-Verträgen werden nicht
nur Dateien, sondern Informationsgrenzen reproduziert.

Verifier prüfen je nach Experiment:

- disjunkte Case-, Family-, Exact-, Alpha-, Input- und Target-Identitäten;
- verbotene Referenzen in Source- und Runtime-Oberflächen;
- Abwesenheit späterer Ergebnisse zum Freeze-Zeitpunkt;
- stage-spezifische Reveal-Autorisierung;
- genau einmalige FINAL-TEST-Nutzung;
- vollständige Exposure- und Selection-Bindung.

Ein mathematisch identischer Lauf mit verletzter Informationsgrenze gilt nicht
als Reproduktion desselben wissenschaftlichen Experiments.

## Performance-Reproduktion

Performancewerte benötigen zusätzlich:

- JDK/JVM und Toolversion;
- Hardware oder Containerumgebung;
- Warm-up, Forks, Iterationen und Stichprobenpolitik;
- Einheit und Benchmarkidentität;
- Allocation-Messung, soweit unterstützt;
- Vergleich von Referenz- und optimiertem Backend bei identischer Semantik.

Wandzeit darf ein Engineering-Ratchet begründen, ersetzt aber keine kanonischen
primitive, programminternen, äußeren Such- oder Exact-Audit-Work-Zähler.

## Archivierung

Ein archivierter wissenschaftlicher Stand bindet:

- unveränderliche Release- oder Commitrevision;
- Quellcode und Runtimeartefakte;
- Roh-Evidence und generierte Tabellen/Abbildungen;
- Schema- und Toolrevisionen;
- Claim- und Limitationsdokumentation;
- Zitationsmetadaten;
- DOI oder einen vergleichbaren unveränderlichen Archivverweis.

Ein später aktualisiertes Repository darf nicht als identisch mit dem
archivierten Evaluationsstand dargestellt werden.

## Grenzen

Auch eine erfolgreiche technische Reproduktion bestätigt nur:

- dass der deklarierte Lauf erneut ausgeführt wurde;
- dass die gebundenen Artefakte und Entscheidungen rekonstruiert wurden;
- dass die dokumentierten Invarianten in der geprüften Umgebung gelten.

Sie bestätigt nicht automatisch:

- mathematische Wahrheit außerhalb des Validators;
- formalen Proof ohne entsprechende Solver-Evidence;
- externe Neuheit;
- Interessantheit oder Bedeutung;
- Skalierbarkeit auf andere Corpora oder Domänen;
- produktive Betriebsreife.

## Siehe auch

- [Independent Reproduction](independent-reproduction.md)
- [Release Readiness](release-readiness.md)
- [Discovery- und Forschungsstand](discovery-status.md)
- [Testing und Verifikation](testing.md)
- [Schema-Katalog](schema-catalog.md)
