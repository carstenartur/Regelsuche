# Java in 15 Minuten: erste Regel und erste Evidence

Voraussetzung ist ein JDK 25. Die Zeitangabe ist ein Lernziel; eine gemessene
Studierenden-Erprobung steht noch aus. Downloadzeiten zählen separat.

## 1. Ein passendes SDK beziehen

Der Release-Workflow erzeugt ab dem nächsten SDK-fähigen Release ein zusätzliches
`regelsuche-sdk-VERSION.zip` unter [GitHub Releases](https://github.com/carstenartur/Regelsuche/releases).
Der erste öffentliche Upload dieses neuen Formats steht noch aus. Für einen
Entwicklungsstand kann der Maintainer `./gradlew packageStudentJavaSdk` ausführen
und genau dieses geprüfte ZIP bereitstellen. Version 0.4.0 besitzt dieses Asset
noch nicht. Maven Central wird nicht als verfügbar behauptet.

Das ZIP enthält ein vollständiges Maven-Repository für die sieben SDK-Module und
die BOM, Sources, Javadoc, Beispiele, Wrapper und den Domain-Generator. Entpacken
und im Wurzelverzeichnis die Prüfsummen kontrollieren:

```bash
sha256sum -c SHA256SUMS.txt
java -version
```

Die Produktversion steht in `sdk-compatibility.json`. Ersetze `VERSION` in den
folgenden Aufrufen durch diesen Wert und verwende einen absoluten Repositorypfad.

## 2. Eine Regel ausführen

```bash
./gradlew --project-dir examples/external-consumers/hello-rule-java25 \
  clean test run \
  -PregelsucheRepository=/absoluter/pfad/regelsuche-sdk-VERSION/repository \
  -PregelsucheVersion=VERSION
```

`HelloRule.java` registriert genau `a + 0 -> a` über `RegelsuchePlugin`. Der
ServiceLoader findet das Plugin, der positive Test prüft die konkrete Umformung,
der negative Test verweigert `x + 1`. Die Ausgabe enthält Regel-ID, Eingabe,
Ergebnis und `replay=VERIFIED`. Ein deterministischer Replay ist noch kein Beweis
für beliebigen Plugin-Code; die Identität hier wird vom Autor verantwortet.

## 3. Drei mathematische Status sehen

```bash
./gradlew --project-dir examples/external-consumers/geometric-sequence-domain-java25 \
  clean test run \
  -PregelsucheRepository=/absoluter/pfad/regelsuche-sdk-VERSION/repository \
  -PregelsucheVersion=VERSION
```

Die Tests führen `CONFIRMED`, `REFUTED` und `BUDGET_EXHAUSTED` aus. Ein bestätigter
Lauf besitzt Kandidat und Zertifikat. Ein widerlegter Lauf besitzt einen
Gegenbefund. Ein erschöpftes Budget liefert keine mathematische Entscheidung.
`NONE_FOUND` bei der Gegenbeispielsuche führt immer noch durch den Evaluator.

## 4. Ein eigenes Projekt erzeugen

```bash
python3 scripts/create-student-discovery-domain.py \
  --output ../meine-domaene --package org.example.discovery \
  --project-name meine-domaene --domain-id meine-domaene --provider-id mein-provider
cd ../meine-domaene
./gradlew clean test run \
  -PregelsucheRepository=/absoluter/pfad/regelsuche-sdk-VERSION/repository \
  -PregelsucheVersion=VERSION
```

Es wird kein Regelsuche-Checkout verändert. Der Generator arbeitet auch aus dem
SDK-ZIP und überschreibt kein vorhandenes Ziel. Unter Windows `gradlew.bat`
verwenden. Für eine vollständig eigene Domäne folgt das
[60-Minuten-Tutorial](java-sdk-domain-tutorial.md).

## Gradle und Maven

Gradle: Repository für `de.regelsuche` exklusiv konfigurieren, wie in den
Beispielen, dann eine Version über die BOM festlegen:

```gradle
dependencies {
    implementation platform("de.regelsuche:regelsuche-bom:VERSION")
    implementation "de.regelsuche:regelsuche-discovery-sdk"
    // Nur für Regel-/Plugin-Erweiterungen:
    implementation "de.regelsuche:regelsuche-plugin-api"
}
```

Maven: Ein eigener Consumer-POM braucht weder den Regelsuche-Parent noch `app`.

```xml
<repositories>
  <repository>
    <id>regelsuche-sdk</id>
    <url>file:///absoluter/pfad/regelsuche-sdk-VERSION/repository</url>
  </repository>
</repositories>
<dependencyManagement><dependencies>
  <dependency>
    <groupId>de.regelsuche</groupId><artifactId>regelsuche-bom</artifactId>
    <version>VERSION</version><type>pom</type><scope>import</scope>
  </dependency>
</dependencies></dependencyManagement>
<dependencies>
  <dependency>
    <groupId>de.regelsuche</groupId><artifactId>regelsuche-discovery-sdk</artifactId>
  </dependency>
</dependencies>
```

Maven 3.9.9+ und `<maven.compiler.release>25</maven.compiler.release>` verwenden.
Für Versionszuordnung, öffentliche Pakete und Migration siehe
[API-Vertrag](java-sdk-api-policy.md).

## Erster realer Zahlentheorie-Verbraucher

`examples/external-consumers/number-theory-plan-java25` ist ein unveränderter,
MIT-lizenzierter Snapshot des unabhängig gepflegten Primachsenraum-Consumers
(Commit `9917424453fd4382e8ce088c969902cbced64302`). `SOURCE.json` enthält die
Original-Git-Blob-Hashes. Die Consumer-CI kontrolliert diese Bytes vor dem Build.
Seine eigenen Tests bestätigen die Basisliste `[2,3]`, behalten das
Gegenbeispiel `2047` gegen Basis 2 und prüfen unabhängig alle Entscheidungen bis
100000. Das begründet kein unbeschränktes Primzahlkriterium. Der Snapshot ist
kein Ersatz für eine spätere Abnahme einer veröffentlichten SDK-Version im
aktuellen Primachsenraum-Repository.
