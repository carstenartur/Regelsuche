# Regelsuche 0.5.4 für Sandbox freigeben und qualifizieren

Der geprüfte Integrationsstand ist `5288033dd77e0e32d2c9af8c8d6eb22c90de702b`.
Der vorherige Release-Tag `v0.5.3` zeigt auf den separaten Metadaten-Commit
`3ffbfb2d1ed63b73926e7c06471116714068cf1b`. Gemeinsame Basis mit dem
Main-Stand `f10e4c0875e1e76288b2332191bba5b78b2f53dc` ist
`9539c2314a5a80cadb28f643e9fde078ebad6d61`. Der Vergleich bis zu diesem Main-Stand umfasst 48 Commits. Die fünf darauf integrierten
Mathematik-Commits sind im Lieferumfang berücksichtigt. Vor dem Dispatch
den endgültigen grünen Main-Stand, das Tag-Intervall und etwaige seit der
Vorbereitung hinzugekommene Änderungen einschließlich Issue-Abschlüssen
abgleichen. [Vergleich ab 0.5.3](https://github.com/carstenartur/Regelsuche/compare/v0.5.3...f10e4c0875e1e76288b2332191bba5b78b2f53dc) ·
[Kuratiertes Lieferintervall](../releases/0.5.4.md)

Vor der Aufteilung des Kernzertifikat-Prüfers zählte der betroffene
Maven-Reaktor am Commit `a170154` insgesamt 3.109 Tests. Davon bestanden
3.108, eine optionale Zeitstudie wurde übersprungen. Am endgültigen
Integrationsstand `5288033` bestanden alle 59 fokussierten Tests.

## 1. Trockenlauf auditieren und Regelsuche veröffentlichen

Nach Freigabe und Integration der Produktänderungen und Release Notes auf
`main` mit `version=0.5.4-SNAPSHOT` den manuellen Workflow **Release**
(`.github/workflows/release.yml`) dort ausführen. Zuerst einen Trockenlauf mit
`dry_run=true` und `skip_tests=false` durchführen. Am Workflow-Artefakt
`regelsuche-0.5.4-artifacts` die sieben Dateien, ihre Prüfsummen, den
Notes-Hash und die Quell-/Release-Zuordnung im Manifest kontrollieren.
Nach erfolgreichem Audit den echten Lauf auf demselben eingefrorenen
Main-Commit mit folgenden Eingaben starten. Bei geändertem Main-Stand
den neuen Kandidaten erneut qualifizieren.

| Eingabe | Wert |
| --- | --- |
| `next_development_version` | leer |
| `next_version_increment` | `patch` (ergibt `0.5.5-SNAPSHOT`) |
| `skip_tests` | `false` |
| `dry_run` | `false` für die freigegebene Veröffentlichung |
| `publish_maven_central` | `true` |

1. Der Workflow prüft Versionsmetadaten, kuratierte Notes und Central-Zugang,
   führt `ciCheck` sowie `mvn -Pfull,sdk-release verify` mit Produkt- und
   Dockerprüfungen aus und kontrolliert den sauberen Checkout.
2. Er erstellt und prüft den Release-Metadaten-Commit für 0.5.4, paketiert
   Produkt und SDKs und kontrolliert Manifest, Prüfsummen und Archivinhalt.
3. Er setzt den annotierten Tag `v0.5.4` auf diesen Commit und veröffentlicht
   die signierten Bibliotheken über `-Psdk-release,maven-central`. Der
   Deploy-Schritt wartet mit `central.waitUntil=published` auf Central.
4. Er aktualisiert `maintenance/0.5.x`, erstellt und prüft den GitHub Release
   mit den sieben vorgesehenen Assets und eröffnet den Folge-PR für
   `0.5.5-SNAPSHOT`. Workflow-URL, tatsächlichen Tag-Commit und
   `SHA256SUMS.txt` für die Abnahme festhalten. [Workflow](../../.github/workflows/release.yml) ·
   [Release-Betrieb](../release-operations.md)

## 2. Sandbox an das tatsächlich veröffentlichte Central-JAR binden

Nach dem erfolgreichen Release das reguläre Optimierungs-SDK direkt von
Central beziehen. Die folgenden Befehle ermitteln die zwei Werte für den
Verbrauchernachweis in einem Regelsuche-Checkout mit dem veröffentlichten Tag.

```sh
release_jar=regelsuche-optimization-sdk-0.5.4.jar
central=https://repo.maven.apache.org/maven2/io/github/carstenartur
curl --fail --location --output "$release_jar" "$central/regelsuche-optimization-sdk/0.5.4/$release_jar"
sha256sum "$release_jar"
git rev-parse 'v0.5.4^{commit}'
```

Im Sandbox-PR `regelsuche.version` in **beiden** Dateien auf `0.5.4`
setzen: `pom.xml` und `qa/math-optimization/pom.xml`. In
`sandbox_math_cleanup/lib/qualification.json` die GAV
`io.github.carstenartur:regelsuche-optimization-sdk:0.5.4`, den gemessenen
`jarSha256` und den aufgelösten Tag-Commit als `sourceRevision` eintragen.
Die aktuelle Beschreibung in `sandbox_math_cleanup/lib/README.md` nachziehen.
Die Prüfsummen des GitHub-SDK-ZIP und der Entwicklungs-JARs bezeichnen
andere Artefakte. Den Verbraucher mit einem frischen Maven-Repository und
den eingetragenen POM-Versionen auflösen.

## 3. Help-Aufnahmen erneuern, Headless-Prüfung und Produktprüfung ausführen

Im Sandbox-Checkout mit den aktualisierten Release-Pins und dem geprüften
Adapter zuerst die fünf Help-Aufnahmen und ihre Herkunftsnachweise mit SWTBot
neu erzeugen. Die bisherigen Nachweise verweisen noch auf 0.5.3.

```sh
xvfb-run --auto-servernum --server-args="-screen 0 1600x1200x24" \
  ./mvnw -Dtycho.localArtifacts=ignore -f sandbox_help_build/pom.xml -Phelp-screenshots \
  -Dhelp.screenshot.testClass=org.sandbox.jdt.ui.helper.views.MathematicsHelpScreenshotsSWTBotTest clean verify
```

Der SWTBot-Test muss im laufenden Eclipse für alle fünf Fixtures den
Originaltext, die konkrete Vorschau, Apply und bytegenaues Undo prüfen.
Die erzeugten PNGs und `.provenance.json` unter
`sandbox_math_cleanup_help/images/` gemeinsam prüfen und übernehmen.
Ihre Basisnamen sind `01-bouncy-castle-choice`, `02-bouncy-castle-majority`,
`03-bouncy-castle-long`, `04-bouncy-castle-swapped-choice` und
`05-bouncy-castle-modular-square`. Jede Provenienz muss den qualifizierten
SDK-Hash und Quellcommit sowie die real geladenen Adapterklassen ausweisen.

Nach Übernahme aller fünf geprüften Bild-/Provenienzpaare die vollständige
Headless-Qualifikation ausführen. Sie gleicht die Galerie mit dem aktuellen
SDK-Nachweis ab und prüft am Bouncy-Castle-Stand
`bb81bfa75c9fa81c7fde467a6314374d22715472` beide Originalmethoden als
Analysefälle ohne freigegebenen Java-Edit. Für die BC-Kontrolle ist mit
`-Dsandbox.math.bc.montgomery.repository=<Git-URI>` ein Mirror möglich.

```sh
./mvnw -f qa/math-optimization/pom.xml -Dsandbox.math.bc.montgomery.upstream=true clean verify
```

Nach erfolgreichem Headless-Lauf die ausgeführten Testfälle im
Surefire-Bericht kontrollieren und den Sandbox-Workflow
`distribution-smoke.yml` am selben Verbraucherstand prüfen. Er baut Produkt, CLI und Update-Site mit
`-Pdistribution,cli-dist,swtbot -Dtycho.localArtifacts=ignore -Dsandbox.math.retainHeadlessProbe=true clean verify`,
prüft native Verbraucher und testet das frisch installierte Mathematik-Plug-in
ohne Display mit `exec:java@verify-installed-mathematics`. Für die Abnahme
gehören die Headless- und SWTBot-Testberichte, die fünf Bild-/Provenienzpaare
sowie der validierte Produktnachweis unter `target/distribution-verification/`
zum selben Release-SDK und Sandbox-Stand.
