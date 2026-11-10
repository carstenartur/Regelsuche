# Java SDK: vom Release zur ersten Regel

Voraussetzungen: JDK 25 und Maven 3.9.9 oder neuer aus der 3.x-Linie. Die frühere
15-Minuten-Angabe ist ein Lernziel; eine gemessene Human-DX-Erprobung steht aus.
Download- und Installationszeiten werden separat erfasst.

## 1. Bevorzugter Weg: das veröffentlichte SDK-Repository

[Release v0.5.2](https://github.com/carstenartur/Regelsuche/releases/tag/v0.5.2)
ist seit **7. November 2026** veröffentlicht. Verwende
[regelsuche-sdk-0.5.2.zip](https://github.com/carstenartur/Regelsuche/releases/download/v0.5.2/regelsuche-sdk-0.5.2.zip)
für Maven-Koordinaten und eigene Regeln beziehungsweise Discovery-Domänen.
Maven Central oder GitHub Packages werden nicht als Veröffentlichungsort behauptet.

```bash
curl -fL -o regelsuche-sdk-0.5.2.zip \
  https://github.com/carstenartur/Regelsuche/releases/download/v0.5.2/regelsuche-sdk-0.5.2.zip
# Erwarteter SHA-256 des vollständigen veröffentlichten ZIPs:
echo "0746b8c6088539c6db3d228131952b30e8066ac8569215b6a508d08df601d9cb  regelsuche-sdk-0.5.2.zip" | sha256sum -c -
unzip regelsuche-sdk-0.5.2.zip
cd regelsuche-sdk-0.5.2
sha256sum -c SHA256SUMS.txt
java -version
```

Das entpackte Wurzelverzeichnis enthält:

| Pfad | Inhalt |
| --- | --- |
| `repository/de/regelsuche/` | 11 SDK-Module, BOM und benötigtes Parent-POM unter Version `0.5.2` |
| `sdk-compatibility.json` | exakte Koordinaten, API-Revisionen und Artefakthashes |
| `javadoc/<modul>/index.html` | lokal im Browser lesbare API-Dokumentation |
| `examples/external-consumers/` | eigenständige Gradle-Beispiele der veröffentlichten Version |
| `SHA256SUMS.txt` | Prüfsummen der enthaltenen Dateien |

Die zweite veröffentlichte Datei
[regelsuche-optimization-sdk-0.5.2.zip](https://github.com/carstenartur/Regelsuche/releases/download/v0.5.2/regelsuche-optimization-sdk-0.5.2.zip)
ist die Standalone-Distribution für numerische Rechenpläne; ihre Benutzung steht
im [Optimierungs-SDK](https://github.com/carstenartur/Regelsuche/blob/v0.5.2/regelsuche-optimization-sdk/README.md).
Sie ersetzt nicht das Maven-Repository für den folgenden Consumer.
Das tatsächlich veröffentlichte Standalone-ZIP hat **kein zusätzliches
Wurzelverzeichnis**; es enthält `consumer/`, `README.md`, eine Qualification-JSON
und `repository/de/regelsuche/regelsuche-optimization-sdk/0.5.2-issue1657-ca38d64cca3e/`
mit dem gleichnamigen `-all.jar`, Sources, Javadoc und POM. Sein internes
Versionsfeld lautet `0.5.2-issue1657-ca38d64cca3e`, sein Quellstand
`ca38d64cca3e415e7c7823e39cadbf762b908ef6`. Der eingefrorene Qualification-Record
enthält noch `publiclyPublished=false`; die tatsächliche spätere Veröffentlichung
wird durch den GitHub-Releaseeintrag belegt. Das Archiv wird hier weder in
`:0.5.2` umbenannt noch als Qualifikation des heutigen `main` behandelt.
SHA-256 des vollständigen Standalone-ZIPs:
`0cba94576ca8a60f79ccd469a853ae0be10f0fa3f6aa20e07f8e98cc4b36f8f8`.

## 2. Einen unabhängigen Maven-Consumer ausführen

Kopiere das aktuelle vollständige Verzeichnis
[hello-rule-java25](../examples/external-consumers/hello-rule-java25/) an einen
beliebigen Ort. Es enthält jetzt einen eigenständigen `pom.xml`, den Java-Code,
zwei JUnit-Tests und die ServiceLoader-Registrierung. Dieses neue POM entstand
**nach 0.5.2** und ist nicht rückwirkend im veröffentlichten ZIP enthalten.
Die verwendeten SDK-Binärdateien sind unverändert die veröffentlichten 0.5.2-Artefakte.

Im kopierten Beispielverzeichnis:

```bash
mvn --batch-mode --no-transfer-progress \
  -Dregelsuche.repository=file:///absoluter/pfad/regelsuche-sdk-0.5.2/repository \
  clean package
java -cp "target/classes:target/dependency/*" example.HelloRule
```

Unter Windows trennt `;` die beiden Classpath-Einträge. Die Maven-Plugins,
JUnit und Fremdabhängigkeiten benötigen beim ersten Lauf Zugriff auf Central.
Das SDK-ZIP ist kein vollständiger Offline-Maven-Cache. Für Regelsuche wird
`de.regelsuche:regelsuche-bom:0.5.2` importiert und
`de.regelsuche:regelsuche-plugin-api:0.5.2` verwendet; das Consumer-POM erbt
nicht vom Regelsuche-Parent und bindet weder Web noch Persistenz ein.

Die Regel `a + 0 -> a` liefert für `x + 0` das Ergebnis `x`, verweigert `x + 1`
und wird für dieselbe Eingabe erneut ausgeführt. Die Ausgabe enthält
`replay=VERIFIED`, `negative=REJECTED` und `proof=AUTHOR_SUPPLIED_IDENTITY`.
Deterministischer Replay beweist nicht beliebigen Plugin-Code. Die algebraische
Identität wird hier vom Autor verantwortet, ohne externen Beweisbackend-Claim.

### Eigene typisierte Erweiterung: SPI v2 statt Regel-API v1

Das Beispiel oben nutzt die ältere Anwendungs-Regel-API **v1**
(`de.regelsuche.plugin.RegelsuchePlugin`). Für eigene typisierte Beiträge ohne
Webanwendung verwende den vollständigen
[SPI-v2-Consumer extension-runtime-java25](../examples/external-consumers/extension-runtime-java25/README.md).
Er bindet `de.regelsuche:regelsuche-extension-runtime:0.5.2` über dieselbe BOM ein
und verwendet `de.regelsuche.extension.RegelsuchePlugin` mit eigenem
ServiceLoader-Deskriptor. Der Einstieg enthält Maven-, SDK-Wrapper- und direkte
Java-Aufrufe sowie die tatsächlich aufgezeichnete Ausgabe `greeting=Hello Ada`
und die Katalogidentität. Sein neues Maven-POM ist noch kein Bestandteil des
unveränderten 0.5.2-ZIPs; Java-/Gradle-Beispiel und v2-Klassen sind dort enthalten.

Der Consumer lädt vertrauenswürdigen Classpath-Code. Er demonstriert keine
Admission eines fremden Plugin-JARs und keine automatische Installation im
Workbench-Laufzeitkatalog. [Plugin-Index](plugin-index.md) und
[generische Erweiterungen](generic-extensions.md) trennen diese Grenzen.

## 3. Eine Discovery-Domäne untersuchen

Die veröffentlichten Gradle-Beispiele bleiben aus dem SDK-Wurzelverzeichnis
benutzbar; sie werden nicht durch den neuen Maven-Einstieg entfernt:

```bash
./gradlew --project-dir examples/external-consumers/geometric-sequence-domain-java25 \
  clean test run \
  -PregelsucheRepository=/absoluter/pfad/regelsuche-sdk-0.5.2/repository \
  -PregelsucheVersion=0.5.2
```

Die Tests führen `CONFIRMED`, `REFUTED` und `BUDGET_EXHAUSTED` aus. Ein bestätigter
Lauf besitzt Kandidat und Zertifikat **für den definierten Prüfbereich**. Ein
widerlegter Lauf besitzt einen Gegenbefund. Ein erschöpftes Budget liefert keine
mathematische Entscheidung; `NONE_FOUND` bei der Gegenbeispielsuche ersetzt den
Evaluator nicht. [Domain-Tutorial](java-sdk-domain-tutorial.md),
[Discovery SDK](java-discovery-sdk.md) und [API-Vertrag](java-sdk-api-policy.md)
beschreiben Builder, Provider-SPI und Statusgrenzen.

## Den Maven-Einstieg unabhängig qualifizieren

Der zusätzliche [Release-Maven-Prüfer](released-sdk-maven-qualification.md) lädt
das tatsächliche 0.5.2-ZIP, prüft dessen Hash und alle 4.034 internen Prüfsummen
und baut die aktuellen vollständigen v1-/v2-Consumer außerhalb des Checkouts in
jeweils leerem Maven-Cache. Er verlangt echte JUnit-Berichte, die genaue
Laufzeit-Modulmenge und die tatsächliche CLI-Ausgabe. Der vorhandene Maven-CI-Job
führt diese Prüfung vor seinem unveränderten Produkt-Verify aus und bewahrt auch
Fehlerausgaben auf. Das geht über die bisher aufgezeichnete direkte
`javac`-/`java`-Ausführung hinaus; ein erfolgreicher Maven-Lauf wird erst durch
ein vollständiges positives Qualification-Receipt belegt.

## Release und aktueller Entwicklungsstand

Dieses Dokument beschreibt den heutigen Einstieg. Die unveränderlichen
[0.5.2-Quellen](https://github.com/carstenartur/Regelsuche/tree/v0.5.2) und ihre
[Release-Hinweise](releases/0.5.2.md) beschreiben die ausgelieferten Funktionen.
`main` ist derzeit `0.5.3-SNAPSHOT`: spätere API-Ergänzungen und neue Beispiele
sind nicht automatisch mit 0.5.2 kompatibel. Der exakte `NumberExpr`-AST ist
allerdings bereits in den tatsächlichen 0.5.2-Core-Binärdateien enthalten und
wird hier nicht als unveröffentlicht dargestellt. Für eine Integration immer Version, API-Revision und tatsächliche
JAR-Bytes gemeinsam festlegen. Das neue hello-rule-Maven-Beispiel verwendet nur
APIs, die in 0.5.2 vorhanden sind.

## Erster realer Zahlentheorie-Verbraucher

`examples/external-consumers/number-theory-plan-java25` ist ein unveränderter,
MIT-lizenzierter Snapshot des unabhängig gepflegten Primachsenraum-Consumers
(Commit `9917424453fd4382e8ce088c969902cbced64302`). `SOURCE.json` enthält die
Original-Git-Blob-Hashes. Seine eigenen Tests bestätigen die Basisliste `[2,3]`,
behalten das Gegenbeispiel `2047` gegen Basis 2 und prüfen alle Entscheidungen bis
100000 unabhängig. Das begründet kein unbeschränktes Primzahlkriterium und
ersetzt keine spätere Abnahme der veröffentlichten Version im aktuellen
Primachsenraum-Repository.
