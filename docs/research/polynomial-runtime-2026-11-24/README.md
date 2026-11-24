# Laufzeit der Polynomvereinfachung und der historischen Aufgaben

Stand 24. November 2026. Gemessen wurden die unveränderten öffentlichen
Vergleichsprotokolle für [Polynomvereinfachung](../../../config/benchmarks/native-polynomial-v1.json)
und [historische Aufgaben](../../../config/benchmarks/historical-sympy-v1.json).
Beide führen die nativen Verfahren und das bereits festgelegte SymPy-1.14.0-Verfahren
aus. Der unabhängige Python-Prüfer bewertet jeden einzelnen Versuch.

Die [Messdaten](observations.json.gz) enthalten die Lebenszyklen, Quell- und
Laufzeitbindungen sowie Dateigrößen und SHA-256 der beobachteten Rohdateien.
Die [Fallübersicht](case-outcomes.tsv.gz) enthält die unabhängig geprüften
Status und Kosten der Messungen auf vier festgelegten CPUs. Die Spalte
`observationsPerRun` zählt gleichlautende Zeilen je Lauf. Beide Dateien lassen
sich mit `gzip -dc` auslesen. Neue Läufe liefern eigene Zeiten und Dateihashes.

Die ersten Messblöcke nutzten dieselbe geteilte Linux-x86-64-Umgebung
mit neun sichtbaren CPUs, Temurin 25.0.5+8, Maven 3.9.14, Python 3.12.14,
SymPy 1.14.0 und mpmath 1.3.0.

| Stand | Git-Baum | Geprüfte Änderung |
| --- | --- | --- |
| `6bf44b7` | `ebda69b0f200014a0b5df69a4a4b05e9309f55f5` | Ausgangsstand |
| `a20b112` | `1a44cf51393d70e7eec822042f58c2c06bb4ca06` | Weniger wiederholte JSON-Serialisierung, Literalevidenz und Dienstsuche |
| `3c58e65` | `2990d2b2437701ed3426b2561b4ce8c6e3941b9a` | Univariate Projektion nur bei höchstens einer Variablen und blockweises Schreiben unveränderter JSON-Textabschnitte |
| `ea3d231` | `a1cba104311a6d7912fe50dacf06b5a1929eb50f` | Sparse-Horner-Kandidaten und exakte Prüfung von Konstruktionsschritten anhand der gebundenen Endpunkte |
| `c69b631` | `859c0a166ceb47c56a79eab22ef86501f8b7916e` | Erster Lauf mit direktem Profil auf den öffentlichen Fällen |
| `927b8b4` | `2966834debd69fb1f6a027979f12ff0276da26f8` | Gemeinsamer Stand mit verkürzter Prüfung historischer Konstruktionsnachweise |
| `7b02c35` | `ce7d078e186c16417258cd7446fabf356a2989b5` | Explizite Brüche im direkten Profil |
| `42ec3e6` | `c0c176f5fbb30a9f108cbd2c077bdf1581a14dad` | Abbruch der direkten Kandidatensuche bei erreichter und nachgewiesener Untergrenze der Operationskosten |
| `22d252a` | `27d3df2f0626a78273bde933df33ad70759d8809` | Zweite Messreihe nach der Umstrukturierung der Prüfdaten-Ausgabe und der Suchsitzung |

## Polynomvereinfachung

Jede Sequenz startet frische Prozesse und bearbeitet 75 deklarierte Fälle.
Beim Lernprofil kommen Wissenserwerb, Speichern, ein frischer JVM-Prozess
und erneute Prüfung des Modells hinzu. Die Tabelle zeigt Mediane aus drei
rotierten Wiederholungen. CPU umfasst alle Arbeiterprozesse und den Controller.
RSS ist der höchste Spitzenwert eines beteiligten Arbeiterprozesses pro Sequenz.

| Stand | Profil | Wandzeit (s) | CPU (s) | Spitze (MiB) |
| --- | --- | ---: | ---: | ---: |
| `6bf44b7` | Native Basis | 6,595 | 23,818 | 276,3 |
| `6bf44b7` | Nativ mit gelerntem Wissen | 8,767 | 26,616 | 273,5 |
| `6bf44b7` | SymPy-Portfolio | 1,881 | 1,877 | 110,9 |
| `a20b112` | Native Basis | 4,772 | 16,236 | 279,6 |
| `a20b112` | Nativ mit gelerntem Wissen | 7,046 | 23,477 | 289,6 |
| `a20b112` | SymPy-Portfolio | 1,903 | 1,876 | 110,9 |
| `3c58e65` | Native Basis | 6,111 | 18,554 | 274,5 |
| `3c58e65` | Nativ mit gelerntem Wissen | 8,945 | 25,948 | 295,4 |
| `3c58e65` | SymPy-Portfolio | 2,487 | 2,017 | 110,7 |
| `ea3d231` | Native Basis | 5,609 | 15,965 | 265,4 |
| `ea3d231` | Nativ mit gelerntem Wissen | 8,038 | 22,762 | 274,6 |
| `ea3d231` | SymPy-Portfolio | 2,147 | 2,113 | 110,3 |

Pro Stand bestanden alle 675 Zeilen die unabhängige Prüfung. Davon enthalten
648 eine gültige Ausgabe und 27 einen Fall außerhalb der gemeinsamen Domäne.
In jeder Wiederholung lieferten beide nativen Profile drei Ausgaben mit niedrigeren
arithmetischen Operationskosten und 69 mit gleichen Kosten wie das Portfolio.
Bei `a20b112` und `3c58e65` stimmen Ausgaben, Suchpfade und gespeicherte Modelle
mit dem Ausgangsstand überein. Die erfasste Anfragearbeit sinkt bis `ea3d231`
je Sequenz in der Basis von 19.876.305 auf 13.581.699 und im Lernprofil von
20.288.197 auf 13.951.992 logische Einheiten.
Im dritten Messblock schwankt die Wandzeit des identischen SymPy-Portfolios
zwischen 1,688 und 4,613 Sekunden.

## Historische Aufgaben

Das Protokoll führt für jedes Profil frische Lebenszyklen mit 1, 18 und 72
Anfragen aus. Die 72-Anfragen-Sequenz enthält die 18 Fälle viermal. Die Mediane
enthalten Prozessstart, gegebenenfalls Lernen, Speichern und Wiederherstellen,
Anfragen, unabhängige Prüfung und Prozessende.

| Stand | Profil | Gelöst von 72 | Wandzeit (s) | CPU (s) | Spitze (MiB) |
| --- | --- | ---: | ---: | ---: | ---: |
| `6bf44b7` | Native Basis | 44 | 2,861 | 9,023 | 229,9 |
| `6bf44b7` | Nativ mit gelerntem Wissen | 60 | 3,132 | 9,045 | 198,5 |
| `6bf44b7` | SymPy | 60 | 1,701 | 1,686 | 73,6 |
| `a20b112` | Native Basis | 44 | 2,238 | 7,742 | 217,4 |
| `a20b112` | Nativ mit gelerntem Wissen | 60 | 2,954 | 7,919 | 187,3 |
| `a20b112` | SymPy | 60 | 2,084 | 2,061 | 73,7 |
| `3c58e65` | Native Basis | 44 | 2,230 | 6,968 | 226,8 |
| `3c58e65` | Nativ mit gelerntem Wissen | 60 | 3,087 | 7,750 | 186,0 |
| `3c58e65` | SymPy | 60 | 1,861 | 1,883 | 73,7 |
| `ea3d231` | Native Basis | 44 | 3,350 | 7,925 | 224,6 |
| `ea3d231` | Nativ mit gelerntem Wissen | 60 | 4,564 | 7,640 | 180,6 |
| `ea3d231` | SymPy | 60 | 1,988 | 1,848 | 73,8 |

Pro Stand bestanden alle 819 Zeilen und alle 27 Lebenszyklen die unabhängige
Prüfung. Beide nativen Profile und SymPy lösen alle vier kanonischen Aufgaben.
Die Basis löst sechs der zehn Varianten, das Lernprofil und SymPy jeweils zehn.
Auch die vier Kontrollfälle behalten ihre Lösungsstatus. Bis einschließlich
`ea3d231` stimmen alle kanonischen Antwortdaten ohne Zeitmessungen und alle
neun gespeicherten Modelle bytegenau mit dem Ausgangsstand überein.

Der Laufzeitabstand bleibt in beiden Protokollen sichtbar. In den historischen
Aufgaben erreichen Lernprofil und SymPy dieselbe Lösungsqualität. Der
SymPy-Wandzeitmedian liegt bei den 72-Anfragen-Sequenzen je nach Messblock
zwischen 1,701 und 2,084 Sekunden. Die Messungen beschreiben die beobachteten
vollständigen Abläufe auf dieser geteilten Umgebung. Die einzelnen Protokolle
enthalten alle Fälle, Grenzwerte und Wiederholungen.

## Wiederholung mit vier festgelegten CPUs

Ausgangsstand und `927b8b4` liefen auf derselben Umgebung jeweils mit Affinität
zu den CPUs 5 bis 8. Die Prozesse starteten für jedes Profil frisch. Die
Zeitangaben bleiben Beobachtungen auf einer geteilten Maschine.

| Polynomprofil | Stand | Wandzeit (s) | CPU (s) | Spitze (MiB) |
| --- | --- | ---: | ---: | ---: |
| Native Basis | `6bf44b7` | 6,904 | 20,463 | 243,5 |
| Native Basis | `927b8b4` | 4,982 | 14,316 | 236,1 |
| Nativ mit gelerntem Wissen | `6bf44b7` | 9,492 | 26,695 | 252,7 |
| Nativ mit gelerntem Wissen | `927b8b4` | 7,490 | 20,043 | 260,8 |
| SymPy-Portfolio | `6bf44b7` | 1,530 | 1,525 | 110,8 |
| SymPy-Portfolio | `927b8b4` | 2,077 | 2,078 | 111,0 |

Alle 675 Zeilen, die ausgewählten Ausgaben und die drei Modellarchive wurden
erneut geprüft. Zwischen diesen beiden Ständen unterscheiden sich auf den
aufgezeichneten Suchpfaden die Arbeitszähler und Zeitmessungen. Die nativen
Qualitätsvektoren stimmen exakt überein. Die native Basis spart 30 % Gesamt-CPU
und das Lernprofil 25 % gegenüber dem neu gemessenen Ausgangsstand.

| Historisches Profil, 72 Anfragen | Stand | Gelöst | Wandzeit (s) | CPU (s) | Spitze (MiB) |
| --- | --- | ---: | ---: | ---: | ---: |
| Native Basis | `6bf44b7` | 44 | 2,902 | 8,228 | 214,4 |
| Native Basis | `927b8b4` | 44 | 1,866 | 4,961 | 197,9 |
| Nativ mit gelerntem Wissen | `6bf44b7` | 60 | 4,510 | 8,822 | 186,8 |
| Nativ mit gelerntem Wissen | `927b8b4` | 60 | 2,914 | 6,791 | 167,2 |
| SymPy | `6bf44b7` | 60 | 1,866 | 1,630 | 74,0 |
| SymPy | `927b8b4` | 60 | 2,007 | 2,003 | 79,5 |

Alle 819 Zeilen und 27 Lebenszyklen wurden erneut geprüft. Die Lösungsstatus,
Ausgaben und Beweisschritte stimmen überein. Die Modelle unterscheiden sich in
den abgerechneten Erwerbskosten und damit im Hash der Vorgängergeneration.
Schemata, Rekurrenzgesetze und Routing bleiben gleich. Der Wissenserwerb sinkt
von 153.443 auf 99.093 logische Einheiten. Die Wiederherstellung kostet in
beiden Ständen 5.148 Einheiten. Auf `927b8b4` liegt der Median für einen frischen
Lebenszyklus mit einer Anfrage bei 0,704 s für die native Basis, 2,402 s für
das Lernprofil und 0,443 s für SymPy. Bei gleicher Lösungsqualität bleibt der
vollständige Lebenszyklus des Lernprofils auch bei 72 Anfragen länger.

## Direktes Polynomprofil

Die zusätzliche Entwicklungsbeobachtung vergleicht das opt-in-Profil
`POLYNOMIAL_DIRECT_V1` mit nativer Basis und SymPy-Portfolio. Sie verwendet
dieselben 75 Fall-IDs und dieselbe unabhängige Kosten- und Identitätsprüfung.
Die direkte Variante hat 16.384 logische Einheiten je Anfrage. Replays und
die Auswahl werden mitgezählt. Drei rotierte Wiederholungen ergeben 675
Zeilen. Die vollständigen Laufzeiten umfassen Start, Anfragen, Prüfung und
Prozessende.

Beim ersten Lauf auf `c69b631` gab das direkte Profil für `variant-05-4` in
allen drei Wiederholungen den Faktor `0.625` aus. Der Prüfer akzeptiert in
diesem Protokoll ganzzahlige Literale und explizite Brüche. Dadurch ist der
erste Lauf in drei Zeilen unvollständig. In den übrigen 213 unterstützten
Zeilen sind die Ausgaben der direkten Variante geprüft und erreichen die
Operationskosten der nativen Basis.

`7b02c35` erzeugt für rationale Koeffizienten eine explizite Bruchsyntax im
getypten Ausdruck. Der unabhängige Prüfer akzeptiert alle 648 unterstützten
Zeilen, die 27 Kontrollen außerhalb der gemeinsamen Domäne behalten ihren
Status. In jeder Wiederholung erreicht das direkte Profil auf allen 72
unterstützten Fall-IDs dieselben Operationskosten wie die native Basis.
Gegenüber SymPy benötigen drei Ausgaben weniger Operationen und 69 gleich viele.

| Stand | Profil | Wandzeit (s) | CPU (s) | Spitze (MiB) |
| --- | --- | ---: | ---: | ---: |
| `7b02c35` | Native Basis | 5,246 | 15,137 | 248,3 |
| `7b02c35` | Direktes Polynomprofil | 2,160 | 4,706 | 166,3 |
| `7b02c35` | SymPy-Portfolio | 1,920 | 1,888 | 47,3 |
| `42ec3e6` | Native Basis | 4,880 | 13,456 | 234,1 |
| `42ec3e6` | Direktes Polynomprofil | 1,795 | 4,156 | 175,2 |
| `42ec3e6` | SymPy-Portfolio | 1,911 | 1,879 | 47,3 |
| `22d252a` | Native Basis | 5,882 | 17,400 | 235,6 |
| `22d252a` | Direktes Polynomprofil | 2,323 | 5,108 | 176,9 |
| `22d252a` | SymPy-Portfolio | 2,395 | 2,389 | 47,2 |

Auf `42ec3e6` enden alle 675 Prüfungen mit denselben Qualitätsstatus wie auf
`7b02c35`. Der Abbruch bei erreichter Kostenuntergrenze senkt die erfasste
Anfragearbeit des direkten Profils von 739.810 auf 553.141 logische Einheiten
je Sequenz. Die native Basis benötigt 13.581.699 Einheiten. In zwei von drei
gepaarten Wiederholungen ist das direkte Profil bei der Wandzeit schneller als
das SymPy-Portfolio (1,795 gegen 1,911 s, 2,079 gegen 1,714 s und 1,445 gegen
2,166 s). CPU und Spitzenspeicher liegen in jeder Wiederholung höher. Die drei
Wandzeitpaare zeigen die Streuung dieses öffentlichen Vergleichs.

Auf `22d252a` liefen die drei Profile erneut in rotierter Reihenfolge mit
frischen Prozessen. Alle 675 Status- und Kostenergebnisse stimmen mit
`42ec3e6` überein. Pro Folge fallen im direkten Profil erneut 553.141
logische Arbeitseinheiten an. Die gepaarten Wandzeiten für das direkte
Profil und SymPy betragen 1,268 und 2,125 s, 2,323 und 2,780 s sowie
2,472 und 2,395 s. Damit erreicht Java in beiden Messreihen einen leicht
niedrigeren Median und in jeweils zwei von drei Paaren die kürzere Wandzeit.
SymPy benötigt in beiden Reihen weniger CPU-Zeit und Spitzenspeicher.

## Reproduktion

Die Tabelle nennt die tatsächlich gemessenen Entwicklungscommits und ihre
Git-Bäume. Die veröffentlichten PR-Commits fassen mehrere Entwicklungsschritte
zusammen. Für die exakte Wiederholung enthält das [Git-Objektpaket](source-checkpoints.pack)
die Zwischenstände. Es ergänzt die versionierten Dateien dieses PR in einem
vollständigen Klon. So lässt sich beispielsweise die erste Messreihe mit Kostenuntergrenze
mit seinem ursprünglichen Git-Baum auschecken.

```sh
git index-pack --stdin < docs/research/polynomial-runtime-2026-11-24/source-checkpoints.pack
git rev-parse '42ec3e6^{tree}'
git worktree add --detach ../regelsuche-direct-repro 42ec3e6
```

Der zurückgegebene Baum für diesen Stand ist
`c0c176f5fbb30a9f108cbd2c077bdf1581a14dad`. Die folgenden Befehle
laufen im Arbeitsbaum des gewünschten Quellstands. Maven, Java 25 und eine
Python-Umgebung mit den angegebenen Paketversionen verwenden. Die Läufe
verlangen neue Ausgabepfade. Unter Linux bindet der Präfix `taskset -c 5-8`
die Python-Kommandos und ihre Kindprozesse an dieselben vier CPUs der Messung.

```sh
mvn -B -ntp -pl regelsuche-learning -am -DskipTests clean package \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
  -DincludeScope=runtime -Dmdep.outputFile=target/comparison-runtime-classpath.txt
mkdir -p build
printf '%s:%s\n' "$PWD/regelsuche-learning/target/classes" \
  "$(cat regelsuche-learning/target/comparison-runtime-classpath.txt)" \
  > build/polynomial-comparison-classpath.txt
export PYTHONPATH="$PWD/scripts"
python3 -B -m external_polynomial_comparison.run_native \
  --classpath build/polynomial-comparison-classpath.txt --output build/native-runtime-observation
python3 -B -m external_polynomial_comparison.run_native \
  --verify --output build/native-runtime-observation
python3 -B -m external_polynomial_comparison.run_historical \
  --classpath build/polynomial-comparison-classpath.txt --output build/historical-runtime-observation
python3 -B -m external_polynomial_comparison.run_historical \
  --verify --output build/historical-runtime-observation
python3 -B -m external_polynomial_comparison.run_direct \
  --classpath build/polynomial-comparison-classpath.txt --output build/direct-runtime-observation
python3 -B -m external_polynomial_comparison.run_direct \
  --verify --output build/direct-runtime-observation
```
