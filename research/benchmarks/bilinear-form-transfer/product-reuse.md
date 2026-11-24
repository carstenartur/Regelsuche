# Was spart die Wiederverwendung bilinearer Produkte?

Die Suche erhält als Trainingsaufgabe die drei Koeffizienten einer Zweiterm-Faltung über den rationalen Zahlen. Sie konstruiert einen Plan und behält ausschließlich dessen Paare von Linearformen. Für sechs neue Ausgabeabbildungen berechnet sie die Ausgabegewichte erneut. Zwei weitere Eingaben prüfen einen Einzelterm außerhalb des trainierten Unterraums und eine rechteckige Koeffizientenmatrix. Alle acht Fälle verwenden höchstens drei Produkte, 20 Millionen logische Arbeitseinheiten, 200.000 logische Zellen und 60 Sekunden je Aufruf.

| Verfahren | Einmaliger Erwerb | Gesamtarbeit einschließlich Erwerb | Gelöste Eingaben |
| --- | ---: | ---: | ---: |
| Frische automatische Formensuche für jede Eingabe | 0 | 435.484 | 8/8 |
| Automatisch erworbene Produktpaare | 12.022 | 18.358 | 8/8 |
| Vorab von Hand festgelegte Karatsuba-Produkte | 0 | 6.336 | 8/8 |

Alle drei Verfahren erreichen für jede Eingabe dieselbe nachgewiesene minimale Produktanzahl. Bei allen sechs geänderten Ausgabeabbildungen nutzt die Suche die im Training erworbenen Produkte. In den beiden Kontrollen wird die frische Suche aktiv. Die ausgewiesene Gesamtarbeit umfasst auch diese erfolglosen Übertragungsversuche. Die Vergleichsbasis mit fest vorgegebenen Produkten verwendet genau dieselbe Prüfung und denselben Rückfall. Die Zahl für den Erwerb ergibt sich aus dem Aufruf von `train`; sie wird genau einmal zur warmen Auswertung addiert.

Das Panel untersucht den Wechsel der Ausgaben bei gleichem algebraischem Unterraum. Die sechs Fälle wurden konstruiert, um diesen Mechanismus zu prüfen. Der Befund zeigt, dass Erwerb und Wiederverwendung gegenüber wiederholter Konstruktion Arbeit einsparen können. Die klassische vorgegebene Basis ist auf diesem Panel günstiger. Für Aussagen über neue Anwendungsfamilien braucht es unabhängig ausgewählte Eingaben. Die [JMH-Messung](README.md) beurteilt separat die Ausführungskosten der erzeugten Java-Programme.

## Reproduktion

Mit JDK 25, Maven und installiertem SDK des untersuchten Checkouts:

```sh
mvn -pl regelsuche-optimization-sdk -am -DskipTests install
cd research/benchmarks/bilinear-form-transfer
cp_file=$(mktemp)
mvn -q dependency:build-classpath "-Dmdep.outputFile=$cp_file"
java --class-path "$(cat "$cp_file")" ProductReuseStudy.java
```

Der [Versuchscode](ProductReuseStudy.java) enthält die vollständigen Koeffizienten und vergleicht die Ergebnisqualität der drei Läufe. Die [Messwerte je Eingabe](learning-results.txt) enthalten die vollständige Programmausgabe.
