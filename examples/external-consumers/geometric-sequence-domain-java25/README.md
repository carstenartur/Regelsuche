# Geometric sequence discovery domain

Dieses eigenständige Java-25-Projekt verwendet
`io.github.carstenartur:regelsuche-discovery-sdk:0.5.3` aus dem veröffentlichten SDK.
Kopiere das Verzeichnis für einen eigenen Build an einen beliebigen Ort.

Mit JDK 25 und Maven 3.9.9 oder neuer aus der 3.x-Linie:

```bash
mvn --batch-mode --no-transfer-progress clean package
java -cp "target/classes:target/dependency/*" example.GeometricSequenceExample
```

Der POM-Default verwendet Maven Central. In einem neu erzeugten SDK-Archiv
bindet der Packager den POM an die dort mitgelieferte Version. Unter Windows
trennt `;` die Classpath-Einträge.

Der Provider sucht einen ganzzahligen Multiplikator für eine geometrische
Folge. Die Tests decken ab:

- bestätigter Kandidat `2` einschließlich zuvor gefundenem Gegenbeispiel für
  Kandidat `1`;
- falscher Holdout mit Ergebnis `REFUTED`;
- zu kleines Budget mit Ergebnis `BUDGET_EXHAUSTED`;
- Auffindbarkeit des Providers über `ServiceLoader`.

Die Tests verwenden die JUnit-unabhängige SDK-Assertionsschicht:

```java
assertThat(run)
    .isConfirmed()
    .hasCounterexampleContaining("multiplier 1")
    .candidateSatisfies(candidate ->
        assertEquals(2, candidate.multiplier()))
    .hasContentAddressedEvidence();
```

Dadurch bleiben Status-, Zertifikats- und Evidence-Semantik sichtbar, ohne dass
ein externer Autor die internen Evidence-Records einzeln prüfen muss.
Domänenspezifische Aussagen bleiben normale Java-Assertions.

## Historische Reproduktion mit 0.5.2

Mit JDK 25, Maven 3.9.9 oder neuer aus der 3.x-Linie und dem entpackten
[SDK 0.5.2](https://github.com/carstenartur/Regelsuche/releases/tag/v0.5.2):

```bash
mvn --batch-mode --no-transfer-progress \
  -Dregelsuche.version=0.5.2 -Dregelsuche.groupId=de.regelsuche \
  -Dregelsuche.repository=file:///pfad/regelsuche-sdk-0.5.2/repository \
  clean package
java -cp "target/classes:target/dependency/*" example.GeometricSequenceExample
```

Unter Windows trennt `;` die Classpath-Einträge. Die Ausgabe enthält den
bestätigten Multiplikator, das Gegenbeispiel für den ersten Kandidaten und
den Hash der Evidenz.

Für Gradle mit SDK 0.5.2 führe den Befehl im Beispielverzeichnis des entpackten
ZIPs aus. Der Checkout verwendet für seinen Gradle-Build die API-Artefakte des
entsprechenden Quellstands:

```bash
gradle clean test run \
  -PregelsucheRepository=/pfad/regelsuche-sdk-0.5.2/repository \
  -PregelsucheVersion=0.5.2
```

Die [SDK-Anleitung](https://github.com/carstenartur/Regelsuche/blob/main/docs/java-sdk-quickstart.md)
beschreibt den Aufbau des ZIPs und die Verifikation der Prüfsummen.
