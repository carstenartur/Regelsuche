# Exakte affine Reduktionen im Vergleich mit Alpha

## Hypothese und Verfahren

Typisierte affine Indexbereiche reichen aus, um für intakte fremde Programme
denselben Wert wie der originale Alpha-Compiler zu berechnen und arithmetische
Wiederholungen aus der Eingabe abzuleiten. Der Java-Kern verarbeitet
eindimensionale, nullbasierte Eingaben und ganzzahlige Ausgabedomänen mit bis
zu zwei Koordinaten. Er bildet Summen durch Präfixdifferenzen, Maxima durch
Präfixwerte, eine monotone Queue oder einen Bereichsbaum. Bei einer Summe über
zwei gebundene Indizes kann er einen Index mit jeweils einer ganzzahligen
oberen und unteren Schranke eliminieren. Die Anzahl der eliminierten Punkte
ergibt eine affine Gewichtung, die über ein zusätzliches Präfixmoment
ausgewertet wird. Das SDK bietet denselben geprüften Plan über
`AffineReductionSdk.prepare` und `AffineReductionSdk.evaluate` an.

Der Alpha-Adapter liest die vollständige Deklaration und den Summanden
aus dem Quelltext. Die Auswertungsstrategie hängt von den typisierten
Bedingungen ab. Die exakte Punkt-für-Punkt-Referenz wertet die ursprünglichen
Binderdomänen ohne Präfix- oder Gewichtungsplan aus. Referenz und optimierter
Java-Pfad verwenden dieselbe geparste Quelle und Ausgabedomäne. Der Vergleich
führt zusätzlich die tatsächlich erzeugten C-Kandidaten
mit AddressSanitizer und UndefinedBehaviorSanitizer aus. Undefinierte leere
Reduktionen, Zugriffe außerhalb der Eingabedomäne und Grenzen für
Koeffizienten, Arbeit und Speicher führen zum Abbruch.

## Befund

Das unveränderte Originalinventar aus dem
[V1-Protokoll](../../config/benchmarks/external-polyhedral-v1.json) umfasst
sieben Quellen. Jedes qualifizierte Programm wurde auf den vier Größen 2, 3, 8 und 20
und jeweils drei ganzzahligen Eingaben ausgeführt. Die drei Projektkontrollen
stehen in der vollständigen 20-Zeilen-Matrix.

| Originalprogramm | Gemeinsam geprüfte Werte | Ergebnis |
| --- | ---: | --- |
| `example_3d_max` | 2.010 | Exakte Übereinstimmung |
| `example_3d_sum` | 2.010 | Exakte Übereinstimmung |
| `example_4d` | 0 | Zweidimensionale Eingabe außerhalb des nativen Fragments, Alpha-Zeitlimit erreicht |
| `increasing_max_filter` | 99 | Exakte Übereinstimmung |
| `prefix_max` | 99 | Exakte Übereinstimmung |
| `prefix_sum` | 99 | Exakte Übereinstimmung |
| `working_example` | 99 | Exakte Übereinstimmung |
| **Gesamt** | **4.416** | **6 von 7 Quellen** |

Jeder der sechs Alpha-Kandidaten stammt aus der vereinfachten Ausgabe des
Originalcompilers. Die native Auswertung und die C-Ausführung stimmen bei
allen 4.416 Werten aus 72 instrumentierten C-Ausführungen mit der exakten
Referenz überein. Die am Originalinventar
gemeinsam geprüfte Abdeckung steigt von einem auf sechs Programme. Als
zusätzliche Kontrollen prüfen die Java-Tests umbenannte Deklarationen,
nicht monotone Fenster, negative Maxima, alternative Binderreihenfolgen,
den exakten Gitterbereich bei nicht ganzzahligen Intervallgrenzen,
Arbeitsgrenzen, Abbruch und Ablehnung undefinierter Ergebnisse.

Die polynomiale Präfixkontrolle liefert weiterhin 99 geprüfte native Werte.
Der Originalcompiler meldet für diese Kontrolle einen Frontendfehler. Das
Paarbeispiel enthält eine undefinierte leere Reduktion. Die Division durch
Eingabewerte liegt außerhalb des exakten gemeinsamen Fragments.

## Phasendiagnose

Der Lauf vom 24. November 2026 endete um 19:19:05 Uhr MEZ. Gemessen wurde
der saubere Regelsuche-Commit `9b862e3169a120f17265475777c13b132975bdd8`
mit Dateibaum `d78d5aafd5ba6fa5239a08a0298877ade274d2b2` nach der Korrektur
der Eingabebudgetierung. Der Lauf nutzte Java 25.0.5, GCC 14.2 und drei
zugewiesene CPUs unter Linux amd64. Arbeitswerte, berechnete Ausgaben und
die 20 Klassifikationen stimmen mit dem vorherigen Entwicklungsdurchlauf
überein. Alle Zeiten sind in Sekunden.

| Originalprogramm | Native Anfrage | Alpha-Codeerzeugung | C-Kompilierung | 12 C-Ausführungen |
| --- | ---: | ---: | ---: | ---: |
| `example_3d_max` | 0,525 | 10,962 | 1,664 | 0,080 |
| `example_3d_sum` | 0,353 | 8,545 | 0,972 | 0,072 |
| `increasing_max_filter` | 0,355 | 9,952 | 0,170 | 0,060 |
| `prefix_max` | 0,253 | 8,388 | 0,572 | 0,256 |
| `prefix_sum` | 0,374 | 8,775 | 0,205 | 0,076 |
| `working_example` | 0,560 | 12,435 | 0,589 | 0,226 |

Die einmalige Prüfung von Quellen und Originalwerkzeug dauerte 1,064 Sekunden. Die gemessenen Phasen beginnen nach Download und Installation.

## Reproduktion und Einordnung

Unter Linux mit Java 25, Maven, Git, GCC und GNU `timeout` ausführen. Das V1-Protokoll
bindet das Originalartefakt, beide JARs und sämtliche Eingaben an ihre Hashes.
Der neue Lauf verwendet ein freies Ausgabeverzeichnis.

```sh
git clone https://github.com/lnarmour/max-simplification-artifact.git /tmp/alpha-peer
git -C /tmp/alpha-peer checkout --detach d91815edbfb0463c1b195173dbff46a4a2e52f39
mvn -pl regelsuche-experiments -am test \
  -Dtest=AlphaAffineComparisonTest,AlphaAffineReductionParserTest,AlphaCOutputDriverTest,AffineReductionPlanTest,AffineReductionSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DexternalAffinePolyhedralHome=/tmp/alpha-peer \
  -DexternalAffinePolyhedralOutput=/tmp/regelsuche-affine-v2
```

Die vollständige Ergebnismatrix enthält die Phasenmessungen und alle
unterstützten sowie abgelehnten Fälle. Das Roharchiv enthält die 292
Dateien aus dem Manifest, einschließlich der Quellen, erzeugten C-Dateien,
Prozessprotokolle und Originalausgaben. Die Archivprüfung berechnet die
Referenzwerte aus den eingefrorenen Quellen erneut.
[Ergebnismatrix](external-polyhedral-affine-v2/results.json.gz) ·
[Roharchiv](external-polyhedral-affine-v2/evidence.tar.gz) ·
[Archivprüfung ohne Originalcompiler](external-polyhedral-affine-v2/README.md#archiv-ohne-originalcompiler-prüfen) ·
[V1-Bericht und Archiv](external-polyhedral-v1/README.md)

Dieser Durchlauf ist eine öffentliche Funktions- und Abdeckungsmessung.
Die native Zeit enthält den JVM-Start und die zwölf Auswertungen. Die
Alpha-Phasen messen eigene C-Erzeugung, GCC-Kompilierung und Instrumentierung
sowie zwölf separate Prozessstarts. Die Adressprüfung und die Prüfungen auf
undefiniertes C-Verhalten sind aktiv. Die separate Leckprüfung ist für die
instrumentierte Ausführung deaktiviert. Ein belastbarer Laufzeitvergleich
benötigt weitere Wiederholungen, größere Eingaben und ein gemeinsames
Ausgabemodell. Zweidimensionale Eingabearrays, weitere Reduktionsoperatoren
und breitere Alpha-Programme bleiben offene Erweiterungen.
