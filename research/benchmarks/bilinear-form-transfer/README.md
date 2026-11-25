# Laufzeit geprüfter bilinearer Rechenpläne mit BigInteger

Die öffentliche Java-SDK-Suche erhält nur die Koeffizienten der Abbildung und die Obergrenze von drei variablen Produkten. Ein Versuchsprogramm erzeugt aus dem zurückgegebenen und geprüften Zeugen einfachen Java-Code, kompiliert ihn vor der Messung und führt ihn in JMH aus. Die beiden JMH-Methoden geben alle Ausgaben zurück. Der [Lernvergleich](product-reuse.md) untersucht getrennt, wie viel Arbeit Erwerb und Wiederverwendung der Produktformen kosten.

## Eingaben und Kontrollen

- **Faltung.** Aus zwei zweistelligen Koeffizientenvektoren werden die drei Koeffizienten ihres Produkts berechnet. Die direkte Fassung verwendet vier variable Multiplikationen und eine Addition. Die gewählte Produktbasis benötigt drei Multiplikationen und vier Additionen. Die geprüfte Ranguntergrenze beträgt drei.
- **Quadratischer Erweiterungskörper.** Die beiden ganzzahligen Teilausdrücke von `Fp2Element.mul` aus Bouncy Castle 1.85.2 sind eine zweite Abbildung. Der entdeckte Plan verwendet drei statt vier variable Produkte. Sein Rangzertifikat belegt die Untergrenze zwei. Beide Vergleichsarme reduzieren anschließend beide Ausgaben modulo der 381-Bit-Feldcharakteristik. Vor der Zeitmessung werden alle 64 Belegungen zusätzlich mit dem tatsächlichen `Fp2Element.mul` des veröffentlichten Artefakts verglichen. JMH misst die zwei Rechenkerne mit gleicher Array-Schnittstelle. Die vollständige Bibliotheksoperation mit ihren Objekten und Aufrufern hätte eigene Kosten.

Der Quellvergleich bezieht sich auf `org.bouncycastle:bcprov-jdk18on:1.85.2`. Die SHA-256 der Binärdatei ist `986b0fb92ec10e0c66b43e036ce0077e6150cfaecd1db9fb92b56672e157afe5`, die des Sources-JAR `b37ac84b1d5435ab7b8d166c16ab9f75e09f68f8ec50479bae433939b241b03f`. [Koordinaten und Referenz im Sandbox-Projekt](https://github.com/carstenartur/sandbox/blob/fb8e1a82ffa80774d8c6b91db4abaecdaf5e1892/qa/math-optimization/pom.xml)

## Messung

JMH 1.37, OpenJDK 25.0.5, AMD EPYC 9V74, gemeinsam genutzte Linux-Umgebung. Jeder Arm rotiert über dieselben 64 deterministisch erzeugten Eingaben. Die Faltung verwendet vorzeichenbehaftete Ganzzahlen der angegebenen Bitbreite, die Feldrechnung gleichverteilt gezogene kanonische Feldwerte. Zwei Forks mit je drei Aufwärm- und fünf Messintervallen von 500 ms, ein Messthread, Heap 128–256 MiB. Die Zeiten erfassen wiederholte Aufrufe nach der Suche und Kompilierung.

Der [erste Lauf](pilot-results.json) umfasste 256 und 2048 Bit sowie die Feldrechnung. Der [erweiterte Lauf](results.json) wiederholt alle Fälle mit der zusätzlichen Korrektheitskontrolle am Bibliotheksartefakt und ergänzt nach Sichtung des ersten Laufs die Faltung bei 8192 Bit. Die Rohdaten enthalten Einzelmessungen pro Fork. Die Tabelle gibt die Mittelwerte und die von JMH berechnete Fehlerhalbbreite (99,9 %) des erweiterten Laufs in ns/Operation an.

| Abbildung | Direkt | Gefundener Plan |
|---|---:|---:|
| Faltung, 256 Bit | 194 ± 8 | 296 ± 78 |
| Faltung, 2048 Bit | 5189 ± 278 | 5014 ± 1597 |
| Faltung, 8192 Bit | 117566 ± 37025 | 70251 ± 13866 |
| Felderweiterung, 381 Bit | 1274 ± 94 | 1525 ± 335 |

Bei der kurzen Faltung mit 8192-Bit-Koeffizienten lag der Mittelwert des gefundenen Plans rund 40 % niedriger. Die breiten Konfidenzintervalle überschneiden sich. Bei 256 Bit und bei der realistischen Feldbreite war der Mittelwert der direkten Implementierung niedriger. Diese Messungen reichen für eine automatische, größenunabhängige Umschreibung in Sandbox nicht aus.

## Reproduzieren

Mit JDK 25 und Maven aus dem Wurzelverzeichnis eines vollständigen Checkouts:

```bash
mvn --batch-mode --no-transfer-progress -pl regelsuche-optimization-sdk -am -DskipTests install
bash research/benchmarks/bilinear-form-transfer/run.sh -rf json -rff /tmp/bilinear-jmh.json
```

Für ein vorbereitetes Offline-System akzeptiert das Skript einen vollständigen Klassenpfad in `BILINEAR_BENCH_CLASSPATH`, einschließlich des SDK dieses Checkouts, seiner Abhängigkeiten, des JMH-Annotationsprozessors und des qualifizierten bcprov-JARs.
