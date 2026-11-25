# Kosten über den gesamten Zähl-Lebenszyklus

## Fragestellung und vorab festgelegter Vertrag

Kann eine aus früheren exakten Zählungen gelernte Reihenfolge ihre Erwerbs-
und Auswahlkosten auf anderen Graphfamilien zurückverdienen? Die Studie
vergleicht feste Verfahren, den neuen Faktorindex, die kostengebundene
gelernte Auswahl und eine vollständig bezahlte neue Suche pro Anfrage.

Das [Protokoll](protocol.json) wurde in Commit `04517fa` vor der ersten
Ausführung eingefroren. Weder die Familien noch die Budgets oder Kontrollen
wurden nach Einsicht in die Ergebnisse angepasst. Alle Eingaben zählen
unabhängige Mengen eines Graphen durch dieselben booleschen Paarfaktoren
`[1, 1, 1, 0]`; die Produktionsplaner erhalten keine Familiennamen.

| Phase | Familien | Einmalige Eingaben | Ausführungen je Arm |
| --- | --- | ---: | ---: |
| Erwerb | Pfade, Zyklen, Binärbäume, vollständiger Graph | 7 | 21 Strategieversuche |
| Validierung | Sterne, Räder | 4 | 4 |
| Finale Auswertung | Gitter, vollständige bipartite Graphen, disjunkte Dreiecke | 6 | 96 |

Die drei Phasen teilen keine Familie. Die 16 finalen Wiederholungen sind
**sechs verschiedene Aufgaben**, nicht 96 unabhängige Testfälle. Das Modell
bleibt nach dem Erwerb unverändert. Ein Hash über die tatsächlichen
Modellbeobachtungen und die Erwerbsarbeit wird vor der Validierung und nach
der finalen Auswertung geprüft und in den Rohdaten gespeichert.

Der vorab festgelegte feste Vergleich ist Min-Fill; Min-Degree ist eine
zusätzliche feste Kontrolle. Im Ergebnis ist Min-Degree die stärkere der
beiden Kontrollen. Diese Beobachtung wird berichtet, ohne nachträglich den
Auswahlvertrag umzuschreiben. Der neue SDK-Overload erlaubt dem Aufrufer,
seine feste Kontrolle ausdrücklich vor der Anfrage festzulegen; bestehende
Aufrufe behalten Min-Degree als Standard.

Jeder Erwerbsversuch erhält höchstens 20.000 Arbeitseinheiten, der gesamte
Erwerb höchstens 10 Millionen. Jede Anfrage hat höchstens 2 Millionen
Arbeitseinheiten, 200.000 Zustandszellen und 20 Sekunden. Die gelernte
Vorschlagsprüfung darf höchstens 100.000 Einheiten verbrauchen und teilt
das verbleibende Anfragebudget mit dem festen Rückfall. Für die
Amortisationsschätzung erhält die Auswahl vier erwartete Anwendungen in der
Validierung und 96 in der finalen Phase. Die tatsächliche Erwerbsarbeit wird
in der Gesamtbilanz über alle 100 Anfragen genau einmal berechnet.

## Vollständiges Ergebnis

Alle 500 Zeilen der fünf Arme stimmen mit einer separat ausgeführten
vollständigen Aufzählung der ursprünglichen Graphbelegungen überein. Der
Erwerb umfasst 17 erfolgreiche und **vier nicht verfügbare** Strategieversuche.
Seine gesamten 147.631 Einheiten gehen einmal in den gelernten Lebenszyklus
ein. Die neue Suche pro Anfrage enthält außerdem 20 erfolglose Teilversuche.
Die Erwerbsversuche haben einzelne Ergebniszeilen. Für die frische Suche
stehen pro Anfrage die Anzahl erfolgloser Teilversuche und ihre im gesamten
Anfrageaufwand enthaltenen Kosten; separate Belege jedes frischen Teilversuchs
werden nicht ausgegeben. Keiner dieser Aufwände wurde aus der Bilanz entfernt.

| Verfahren | Validierung | Finale Anfragen | Gesamte Arbeit einschließlich Erwerb | Richtige Antworten |
| --- | ---: | ---: | ---: | ---: |
| Fest: Min-Fill | 31.326 | 759.536 | 790.862 | 100/100 |
| Fest: Min-Degree | 27.792 | 700.496 | **728.288** | 100/100 |
| Indexierte Elimination, Min-Fill | 30.628 | 749.664 | 780.292 | 100/100 |
| Kostengebundene gelernte Auswahl | 31.782 | 769.104 | 948.517 | 100/100 |
| Neue vollständige Strategiewahl je Anfrage | 167.270 | 3.139.168 | 3.306.438 | 100/100 |

Die gelernte Auswahl verwendet in **allen 100 Anfragen die feste Kontrolle**.
Die vorliegenden Merkmale und beobachteten Erwerbskosten rechtfertigen keinen
günstigeren Vorschlag. Das ist ein Nullbefund für den Lernvorteil. Gegenüber
der festgelegten Min-Fill-Kontrolle kosten Erwerb und Auswahl insgesamt etwa
19,9 Prozent mehr; gegenüber der stärksten beobachteten festen Kontrolle
Min-Degree sind es etwa 30,2 Prozent mehr.

Der Faktorindex spart gegenüber derselben Min-Fill-Reihenfolge etwa 1,3
Prozent gezählte Arbeit. Er übertrifft in dieser Studie Min-Degree nicht.
Eine neue Strategiewahl pro Anfrage ist noch deutlich teurer. Diese
Ergebnisse begründen den konservativen Rückfall und einen ausdrücklich
wählbaren festen Vergleich, aber keine allgemeine Überlegenheit des Lernens.

## Zeit und Speicher getrennt von gezählter Arbeit

Die Rohdaten enthalten pro Anfrage Wandzeit, CPU-Zeit des ausführenden
Threads und dessen allokierte Bytes, soweit die JVM diese Messung unterstützt.
Die Werte unten summieren die 100 Anfragen; Erwerb wird separat ausgewiesen.

| Verfahren | Wandzeit (ms) | Thread-CPU (ms) | Allokierte Bytes |
| --- | ---: | ---: | ---: |
| Fest: Min-Fill | 47,227 | 47,159 | 7.800.432 |
| Fest: Min-Degree | 44,603 | 44,607 | 7.052.744 |
| Indexierte Elimination, Min-Fill | 48,984 | 48,889 | 7.913.472 |
| Kostengebundene gelernte Auswahl | 64,258 | 63,881 | 8.937.616 |
| Neue vollständige Strategiewahl je Anfrage | 212,138 | 205,854 | 31.306.248 |

Dies ist ein einzelner lokaler JVM-Lauf mit rotierender Armreihenfolge. Er
lief unter Java `25.0.5+8-LTS` neben anderer Entwicklungsarbeit. Er liefert
keine belastbare Laufzeit- oder Skalierungsaussage; insbesondere ist die
kleinere gezählte Arbeit des Index nicht mit einer Zeitersparnis gleichzusetzen.
Die unabhängige Belegungsaufzählung steht außerhalb aller gemessenen
Algorithmusaufrufe. Die internen Zertifikatsprüfungen sind in deren Arbeit
und Zeit enthalten.

## Herkunft und Reproduktion

- [Vollständige Rohdaten](results.json), einschließlich erfolgloser
  Erwerbsversuche, jeder Anfrage, ausgewählter Strategie und Einzelmessungen.
- Gemessener lokaler Quellcommit: `5524fcaba15e86d30af922a8015ee164579aadf2`.
- Veröffentlichter Commit mit exakt demselben Git-Baum:
  [`40136cea1f464981d098b853a6e3db38191345b7`](https://github.com/carstenartur/Regelsuche/commit/40136cea1f464981d098b853a6e3db38191345b7).
- Protokoll-SHA-256: `35c02970afa02f5fd44fee39c703f1b0baf1fb3b1c6f4baac625b3369830b0ff`.
- Unverändertes Modell vor und nach der Auswertung:
  `38484bee7626c49eef1a61e2d5a9df1675b2fa4089aba5502b7ee0798d839b35`.

Der GitHub-Zugang stellt Commit-Metadaten beim Übertragen neu aus. Die
[Zuordnung der Quellstände](../../../docs/research/source-publication-2026-11-25.json)
bindet deshalb lokale und veröffentlichte Commit-IDs an den jeweils identischen
Git-Baum. Der Protokollstand vor der Messung ist öffentlich als
[`0344d1131e4a42c131b76e2eacea8a62be18ea5f`](https://github.com/carstenartur/Regelsuche/commit/0344d1131e4a42c131b76e2eacea8a62be18ea5f)
erhalten. Die ursprünglichen Revisionsangaben in den Rohdaten bleiben bestehen.

Im Checkout des veröffentlichten Messquellcommits mit JDK 25 und Maven 3.9.9
(die neue Ausgabe nennt dessen veröffentlichte Commit-ID):

```sh
mvn -B -ntp -pl regelsuche-experiments -am install -DskipTests
mvn -B -ntp -pl regelsuche-experiments dependency:build-classpath \
  -Dmdep.outputFile=target/lifecycle-classpath
java -cp "regelsuche-experiments/target/classes:$(cat regelsuche-experiments/target/lifecycle-classpath)" \
  de.regelsuche.benchmark.peer.ConstraintLifecycleComparison \
  research/benchmarks/constraint-lifecycle-v1/protocol.json \
  /tmp/constraint-lifecycle-results.json "$(git rev-parse HEAD)"
```

Der Runner verwirft überlappende Familien, begrenzt die Graphgröße für die
unabhängige Aufzählung und bricht bei einer falschen positiven Anzahl ab.
Die Regression prüft geschlossene Zählformeln und dass der eingefrorene
Hash tatsächlich das verwendete Modell bindet. Die erste Instrumentierung
hashte nur eine vorher erzeugte Berichtskopie. Das wurde vor Speicherung
dieses Ergebnisses korrigiert; der erneute Lauf mit demselben eingefrorenen
Protokoll ergibt dieselben gezählten Kosten und Entscheidungen.

## Konsequenz

Die gemessene Auswahl bleibt konservativ. Weiteres Lernen braucht bessere,
vorab begründete Strukturmerkmale und einen neuen, getrennt eingefrorenen
Versuch. Diese sechs finalen Aufgaben sind nach ihrer Auswertung
Entwicklungswissen und dürfen für eine spätere unabhängige Prüfung nicht
erneut als unberührt bezeichnet werden.
