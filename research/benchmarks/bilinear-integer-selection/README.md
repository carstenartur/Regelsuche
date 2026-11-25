# Gemessene Kosten der ganzzahligen Planauswahl

Der Versuch vergleicht den direkten Java-Rechenweg, den [früheren ersten gefundenen Plan](../bilinear-form-transfer/README.md) und die Auswahl des ganzzahligen SDKs. Die vier vorab festgelegten Aufgaben sind Zweiterm-Faltungen mit 256, 2048 und 8192 Bit sowie die beiden Ausgaben von `Fp2Element.mul` bei der Feldbreite von 381 Bit. Diese Aufgaben waren bereits aus der Entwicklung bekannt. Die Zeitmessung bewertet die ausgeführten Pläne. Erwerb und Wiederverwendung von Wissen erfordern einen [separaten Versuch](../bilinear-form-transfer/product-reuse.md).

## Protokoll

Der SDK-Aufruf erhält vier als Obergrenze für Produkte, höchstens 5.000 untersuchte Teilmengen, 100 Millionen Arbeitseinheiten, 100.000 Zellen und 60 s. Die Zahlen wurden vor Sichtung der neuen Messwerte festgelegt. Das Bitprofil verwendet die jeweilige Eingabebreite für alle vier Koeffizienten. Die Auswahl erfolgt vor der Messung. Der Renderer schreibt die vom SDK gewählten Operationsschritte und Ausgabeslots in ihrer vorgegebenen Reihenfolge in Java-Quelltext. Der ursprüngliche Ersttreffer wird mit demselben früheren Renderer reproduziert und sein generierter Code per SHA-256 an das aufgezeichnete frühere Ergebnis gebunden.

Alle drei Rechenkerne liefern ein Array mit exakten Ganzzahlen. Dieselbe Funktion reduziert beim Feldfall anschließend beide Ausgaben. Pro Fork vergleicht eine ungemessene Kontrolle alle Rohwerte der drei Varianten auf denselben 64 rotierenden Operandenpaaren und 81 Randfällen. Im Feldfall prüft sie zusätzlich alle reduzierten Werte gegen das veröffentlichte Bouncy-Castle-Artefakt `bcprov-jdk18on:1.85.2`. Der Zeitvergleich betrifft den arithmetischen Kern in derselben Arrayschnittstelle.

JMH 1.37, ein Messthread, drei JVM-Forks je Arm und Aufgabe. Pro Fork drei Aufwärm- und fünf Messintervalle von 500 ms. Heap 128–256 MiB. Der JMH-GC-Profiler erfasst normalisierte Allokation pro Aufruf. JIT, Speicherverwaltung und Auslastung der gemeinsam genutzten Maschine beeinflussen die Zeiten.

## Reproduktion

Mit JDK 25 und Maven in einem vollständigen Checkout:

```sh
mvn --batch-mode --no-transfer-progress -pl regelsuche-optimization-sdk -am -DskipTests install
bash research/benchmarks/bilinear-integer-selection/run.sh /tmp/bilinear-selection-results
```

Für vorbereitete Offline-Systeme kann `BILINEAR_BENCH_CLASSPATH` die gleichen Abhängigkeiten aus dem [Benchmark-POM](../bilinear-form-transfer/pom.xml) bereitstellen. Das Skript legt den erzeugten Java-Code, die Auswahlprotokolle und die JMH-Rohdaten im gewählten Ausgabeverzeichnis ab.

## Auswahl

Die [protokollierte Auswahl](selection.txt) schöpfte beide endlichen Formenkataloge aus. Bei der Faltung untersuchte sie 2.380 Teilmengen je Breite, bei der Feldrechnung 2.500. Der [erzeugte Quelltext](selected-source.txt) zeigt bei 256 Bit die vier direkten Produkte. Bei den beiden größeren Faltungen teilt der gewählte Plan drei Produkte und berechnet den Mittelkoeffizienten als `p2.subtract(p0.add(p1))`. Im Feldfall bildet er `u=a*(c+d)`, `v=b*(c-d)` und `w=(a+b)*d`. Daraus folgen die beiden Ausgaben `u-w` und `v+w`.

Die Messspalte »Auswahl« führt `selected()` aus, also den Plan mit dem niedrigsten geschätzten Rechenaufwand bei der gegebenen Breite. Die zusätzliche SDK-Auswahl `preferredWithoutAllocationIncrease()` ergab für alle vier Fälle exakt dieselben Schritte und Ausgabeslots wie die direkt berechnete SDK-Ausgangslösung. Die Spalte »Direkt« misst die handgeschriebene direkte Formel. Die logischen Speichergrößen des Modells und die vom JVM-Profiler gemessenen Bytezahlen verwenden unterschiedliche Einheiten.

## Ergebnisse

Die Tabelle zeigt die JMH-Zeit pro Aufruf in ns (Mittelwert ± 99,9-%-Fehlerhalbbreite) und die normalisierte Allokation in B/Aufruf. Die [JMH-Rohdaten](results.json.gz) enthalten die Einzelmessungen aller Forks.

| Aufgabe | Direkt | Früherer Ersttreffer | Auswahl |
|---|---:|---:|---:|
| Faltung 256 Bit | 199 ± 21 ns · 658 B | 260 ± 43 ns · 971 B | 205 ± 18 ns · 658 B |
| Faltung 2048 Bit | 5646 ± 568 ns · 3065 B | 4749 ± 509 ns · 4353 B | 4742 ± 641 ns · 4353 B |
| Faltung 8192 Bit | 82961 ± 7957 ns · 258543 B | 68586 ± 8493 ns · 200246 B | 64682 ± 5063 ns · 200206 B |
| Fp2 381 Bit | 1403 ± 166 ns · 1953 B | 1457 ± 249 ns · 2201 B | 1516 ± 238 ns · 2113 B |

Bei der Faltung mit 256 Bit stellt die Auswahl die niedrige Allokation des direkten Verfahrens wieder her. Mit 8192 Bit lag ihr Zeitmittel 22 % unter der direkten Formel. Alle drei ausgewählten Fork-Mittel (64.743, 64.151 und 65.153 ns) lagen unter den direkten (88.057, 80.033 und 80.792 ns). Die Fehlerintervalle der ausgewählten und der früheren Drei-Produkt-Fassung überschneiden sich. Die große Faltung zeigt zudem, dass interne temporäre Daten einer JVM-Multiplikation die gemessene Allokation erheblich beeinflussen können.

Für die Feldrechnung wählt das Rechenkostenmodell drei Produkte. Die Wahl spart 88 B/Aufruf gegenüber dem früheren Ersttreffer und verwendet 160 B mehr als die direkte Formel. Ein Laufzeitvorteil gegenüber den vier direkten Produkten ist bei 381 Bit nicht belegt. Eine Änderung an einem Bibliotheksaufrufer würde eine separate End-to-End-Messung erfordern.
