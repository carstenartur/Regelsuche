# Regelsuche Discovery SDK

Headless Java-25-Fassade für eigene begrenzte mathematische
`DiscoveryDomain<State, Candidate, Certificate>`-Implementierungen.

Die wichtigsten Einstiegstypen sind `DiscoveryDomainBuilder`,
`RegelsucheDiscovery`, `DiscoveryRun`, `DiscoveryRunAssertions`,
`DiscoveryDomainProvider` und `DiscoveryDomainCatalog`. Vereinfachte
Verdrahtung ändert keine mathematische Aussage: `CONFIRMED` erfordert weiterhin
einen expliziten Evaluator und ein Zertifikat; ein leerer Gegenbeispielfund ist
kein Beweis.

Ein eigenständiges Starterprojekt lässt sich aus dem Checkout erzeugen:

```bash
python3 scripts/create-student-discovery-domain.py \
  --output ../my-first-regelsuche-domain
```

Das erzeugte Projekt enthält seinen eigenen, versions- und
prüfsummengepinnten Gradle Wrapper. Java 25 und das bereitgestellte
SDK-Repository bleiben erforderlich, eine separate Gradle-Installation nicht:

```bash
cd ../my-first-regelsuche-domain
./gradlew clean test run \
  -PregelsucheRepository=/pfad/zum/repository \
  -PregelsucheVersion=0.4.0-SNAPSHOT
```

Unter Windows wird `gradlew.bat` verwendet. Beim ersten Build werden Gradle
und Fremdabhängigkeiten heruntergeladen.

Die Consumer-CI baut sowohl das gepflegte externe Beispiel als auch einen
frisch erzeugten, nicht nachbearbeiteten Starter gegen die isoliert
veröffentlichten SDK-Artefakte. Die zusätzlichen Generator-Dateisystemtests
sind über `verifyStudentDiscoveryStarter` in dieselbe Prüfung eingebunden;
sie ersetzen nicht den echten Java-Build.

Quickstart, Assertions, ServiceLoader-SPI, Generator und externer Consumer:
[`docs/java-discovery-sdk.md`](../docs/java-discovery-sdk.md).
