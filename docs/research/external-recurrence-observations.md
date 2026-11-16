# Exakte Beobachtungspläne auf veröffentlichten Modellen

Stand 16. November 2026. Modellquellen, Eingaben, Reihenfolge, Budget und
Vergleichsverfahren wurden vor dem ersten Lauf im lokalen Commit
`f1ed620eb6c1e630f4bf9176f1f6d5ffa28d26e1` festgeschrieben. Die später
publizierte Protokollkopie hat denselben Git-Baum
`6319c09927e1df7f2b94dffb13e93dcffdf276e5`. Die Versuchsanordnung fragt, ob
sich ein geprüfter Beobachtungsplan beim Wechsel des Anfangszustands
amortisiert. [Festgeschriebenes Protokoll](../../config/benchmarks/external-recurrence-observations-v1.json) · [Publizierte Protokollkopie](https://github.com/carstenartur/Regelsuche/blob/b27c207042d200c1c221421dfbee3857bf12355b/config/benchmarks/external-recurrence-observations-v1.json)

## Modelle und Aufträge

- **Land of Oz.** Grinstead und Snell geben eine dreizuständige Markov-Kette
  für Regen, schönes Wetter und Schnee an. Wir transponieren ihre Matrix für
  Spaltenvektoren. Die drei Zustandswahrscheinlichkeiten sind die beobachteten
  Größen. Die gleichverteilte Ausgangslage aus Beispiel 11.3 eröffnet den
  Strom. Es folgen die drei in Satz 11.2 beschriebenen Einheitsstarts. Die
  Wahrscheinlichkeiten werden aus der Originalmatrix exakt berechnet. Das
  Buch druckt die Werte nach drei Schritten gerundet. [Grinstead und Snell, Kapitel 11, Beispiele 11.1–11.3](https://math.dartmouth.edu/~prob/prob/prob.pdf)
- **Leslie-Projektionen.** Alle drei Zahlenbeispiele aus Weidls Abschnitt 5.3
  verwenden je drei Altersklassen. Wir übernehmen die Matrizen und beobachten
  die vier Größen aus den Abbildungen 5.4, 5.6 und 5.8, also die drei
  Altersklassen und ihre Summe. Auf den publizierten Start mit 100 pro Klasse
  folgen drei zusätzliche Starts mit jeweils 300 in genau einer Klasse.
  Bruchteile von Individuen bleiben Werte des Projektionsmodells. Die Rechnung
  führt die Matrixiteration auch unterhalb einer Person weiter. [Weidl 2012, Kapitel 5, Seiten 56–60](https://repositum.tuwien.at/handle/20.500.12708/13203)

Jede der 15 Beobachtungen liefert einen Skalar pro Index. Es gibt je einen
separaten Strom mit den Indizes 0 bis 6 und einen mit 16 verstreuten Indizes
bis 257. Bei jedem der vier aufeinanderfolgenden Starts werden dieselben
Indizes in der festgeschriebenen Reihenfolge angefragt. Die Horizonte sind
Versuchseingaben. Pro Strom beginnen alle drei Verfahren ohne aufbereiteten
Zustand und mit demselben Arbeitsbudget von 1.000.000 Einheiten. Die exakte
Kontrollrechnung propagiert den gesamten Anfangszustand schrittweise mit
rationalen Zahlen. Sie prüft auch die festgeschriebenen Werte, die sich aus
den publizierten Beispielen ergeben.

## Vergleichsverfahren und Kosten

Die **direkte Matrixrechnung** skaliert die rationalen Eingaben auf ganze
Zähler und gemeinsame Nenner. Sie bildet die Zeile $c A^n$ schrittweise aus
$c A^{n-1}$, wenn diese vorliegt, oder mit gemeinsam genutzten binären
Matrixpotenzen. Die projizierte Zeile bleibt für weitere Anfangszustände
abrufbar. Pro Antwort bildet sie das Skalarprodukt und normalisiert den
Bruch. Diese Rechnung verwendet die lineare Beobachtung und die feste
Matrix, die beiden Planverfahren ebenfalls vorliegen.

**Frische Herleitung** ruft für jede Startanfrage die exakte
Krylov-Herleitung samt unabhängiger Identitätsprüfung auf. Die
**Wiederverwendung** bezahlt die gleiche Herleitung und Prüfung für die
erste Anfrage und hält den geprüften Plan im Prozess. Beide Planverfahren
binden pro Anfrage den Anfangszustand und beantworten den Stapel über die
gleiche API. Die binären Potenzen der skalaren Rekurrenz werden in diesem
Aufruf innerhalb eines Stapels geteilt.

Der Arbeitsbeleg zählt Aufbereitung und Zugriff, Herleitung und Prüfung,
Bindung und Auswertung getrennt. Jede Teiloperation erhält das verbleibende
Budget. Die logischen Einheiten stammen aus dem vorhandenen Regelsuche-Zähler
und analog instrumentierten Ganzzahloperationen im direkten Vergleich. Sie
zählen Operationen unabhängig von der Bitlänge der Operanden. Die drei Pfade
starten mit gleichartig bereitgestellten typisierten Eingaben. Das Einlesen des
Versuchsprotokolls, die externe Ergebniskontrolle und das Schreiben der Belege
gehören zum Auswerter. [Phasen, Gesamtarbeit und Ergebnisdigests](external-recurrence-observations-v1/work.tsv)

## Ergebnisse über vier Starts

Alle drei Verfahren liefern je 1.380 identische rationale Antworten für
30 Ströme unterhalb des Arbeitsbudgets. Die Krylov-Ordnung ist zwei
für die Schönwetterwahrscheinlichkeit und drei für die 14 übrigen
Beobachtungen.

| Modell | Ströme | Direkte Arbeit | Frische Herleitung | Wiederverwendung |
| --- | ---: | ---: | ---: | ---: |
| Land of Oz | 6 | 9.018 | 40.308 | 21.636 |
| Leslie 1 | 8 | 11.182 | 54.912 | 31.422 |
| Leslie 2 | 8 | 11.182 | 55.456 | 31.558 |
| Leslie 3 | 8 | 11.182 | 55.592 | 31.592 |
| **Gesamt** | **30** | **42.564** | **206.268** | **116.208** |

Gegenüber frischer Herleitung entfallen durch Wiederverwendung 43,66 % der
Gesamtarbeit. Die Einsparung entsteht vollständig durch drei entfallende
Herleitungen samt Prüfung pro Strom. Sie liegt in den kurzen Strömen bei
59,47 % und in den verstreuten bei 34,49 %, jeweils als Anteil der addierten
Arbeit. Die direkte Rechnung braucht in allen 30 vollständigen Strömen
weniger Arbeit. Die wiederverwendete Variante benötigt das 1,60- bis
3,91-Fache ihrer Arbeit.

Bei der ersten Anfrage verbrauchen beide Planverfahren gleich viel. Für
Schönwetter mit verstreuten Indizes liefert die Herleitung der Ordnung zwei
die erste Antwortliste in 1.467 Einheiten. Die direkte Rechnung benötigt
1.677. Über alle vier Starts dreht sich dieser Vergleich, weil die direkte
Rechnung ihre vorbereiteten Zeilen erneut nutzt. Sie benötigt 2.336 Einheiten
und der wiederverwendete Beobachtungsplan 3.744. In den übrigen 29 ersten
Anfragen liegt die direkte Rechnung bereits nach dem ersten Stapel vorn.

## Laufzeit und Speicherallokation

Ein separater Lauf misst je Strom und Verfahren den vollständigen Ablauf mit
vier Starts. Die drei Verfahren wechseln die Ausführungsreihenfolge. Je Zelle
folgen auf sechs Aufwärmwiederholungen 13 Messwiederholungen mit neuem
Verfahrenszustand. Die Messung lief am 16. November 2026 unter Linux/amd64 mit
HotSpot 25.0.5+8, Serial GC und 256 MiB konfigurierter Heapgröße. Die JVM
meldete neun verfügbare Prozessoren. Der Beleg enthält Median, Minimum und
Maximum je Zelle. [Messbeleg](external-recurrence-observations-v1/jvm-metrics.tsv)

**Laufzeit.** Die Mediane der 30 Ströme liegen direkt bei 0,013–0,549 ms, mit
frischer Herleitung bei 0,720–14,309 ms und bei Wiederverwendung bei
0,225–10,183 ms. In allen gepaarten Strömen liegt der Median der
Wiederverwendung unter dem der frischen Herleitung und über dem der direkten
Rechnung. Insgesamt sind dies deskriptive Messungen in einer JVM. JVM-Start,
Protokolllesen und Ergebniskontrolle liegen außerhalb des gemessenen
Zeitraums.

**Speicherallokation.** Die Mediane der Heap-Allokation im ausführenden
Thread liegen direkt bei 19.080–139.720 Byte, mit frischer Herleitung bei
848.320–8.127.752 Byte und bei Wiederverwendung bei 320.200–5.936.576 Byte.
Die Wiederverwendung liegt in allen gepaarten Strömen zwischen den beiden
anderen Verfahren. Die JVM-Schnittstelle schätzt das kumulierte
Allokationsvolumen. Die Größe des dauerhaft gehaltenen Plans, der maximale
Speicherbedarf und der Speicherverbrauch des gesamten Prozesses bleiben
offen. [Definition der JVM-Messgröße](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.management/com/sun/management/ThreadMXBean.html#getCurrentThreadAllocatedBytes())

## Geltungsbereich

Die Modelle und Beobachtungsgrößen stammen aus Publikationen außerhalb des
Projekts. Die Übertragung bleibt innerhalb derselben fest vorgegebenen
Matrix und Beobachtung. Die drei späteren Starts werden erst beim Binden
verwendet. Die Wirkung auf zurückgehaltenen Modellfamilien und beim Wechsel
des Übergangs oder der Beobachtung bleibt offen, ebenso die Kosten einer
Speicherung zwischen Sitzungen. Die Ergebnisse betreffen dieses Panel aus
zwei Modellfamilien mit je drei Zustandskoordinaten und linearen
Beobachtungen.

## Reproduktion

Im Checkout mit Java 25 führt der erste Befehl die exakte Kontrolle aus und
vergleicht den Arbeitsbeleg bytegenau mit der Versionsdatei. Zum erneuten
Schreiben des Belegs kann `-DexternalObservationWorkOutput=/tmp/external-work.tsv`
angehängt werden.

```bash
mvn -B -pl regelsuche-math-algorithms -am \
  -Dtest=ExternalRecurrenceObservationStudyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Der optionale Messlauf schreibt die neuen Zeiten und Allokationszähler an den
angegebenen Pfad. Bei einer JVM ohne Thread-Allokationszähler erscheinen
`NA`-Werte. Die Laufzeiten und Allokationen werden als neue Stichprobe
berichtet.

```bash
mvn -B -pl regelsuche-math-algorithms -am \
  '-Dtest=ExternalRecurrenceObservationStudyTest#measuresStreamWallTimeAndThreadAllocation' \
  -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true \
  '-DargLine=-Xms256m -Xmx256m -XX:+UseSerialGC' \
  -DexternalObservationMetricsOutput=/tmp/external-jvm-metrics.tsv test
```
