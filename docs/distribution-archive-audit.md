# Paketinhalt vor der Veröffentlichung prüfen

Der Fehler aus #919 zeigte, dass ein erfolgreicher Build und richtige
Download-Prüfsummen allein keine korrekte Distribution garantieren. Ein Archiv
kann gleichzeitig die aktuellen Release-JARs und veraltete SNAPSHOT-JARs
enthalten. #920 beseitigt die Ursache durch eine Assembly aus dem aktuellen
Laufzeitgraphen. Der hier beschriebene zusätzliche Vertrag überprüft unabhängig
von der Assembly-Implementierung die tatsächlich erzeugten Produktarchive.

## Zwei unterschiedliche Nachweise

`MavenDistributionAssemblyContractTest` prüft die produktive Assembly in einer
kleinen Fixture: zwei Maven-Paketierungen ohne `clean`, Versionswechsel,
entfernte Bibliothek und absichtlich verschmutztes altes Staging-Verzeichnis.

`DistributionArchiveVerifier` prüft dagegen die **wirklichen** Dateien unter
`app/target`. Er verwendet keine festgeschriebene Liste von zwölf Produktmodulen
und leitet seine erwarteten Bibliotheken auch nicht aus dem fertigen Archiv ab.
Der bereits vorhandene Maven Dependency Plugin schreibt in `prepare-package`
den aufgelösten Compile-/Runtime-Classpath einschließlich transitiver
Abhängigkeiten nach `app/target/runtime-classpath.txt`. `regenerateFile=true`
erzeugt diese Datei bei jeder Paketierung neu. Die Datei enthält lokale absolute
Pfade und gehört nicht zu den veröffentlichten Assets.

Der Java-Verifier bildet aus den referenzierten Dateien die erwartete Menge
aller Archivdateien samt Größe, SHA-256 und CRC32. Dazu kommen das gesonderte
Anwendungs-JAR, beide Launcher und die fünf Dokumentations-/Zitierdateien des
Distributionsvertrags. **Beide Archive müssen vollständig dieser Referenzmenge
entsprechen.** Ein übereinstimmendes, aber gleichermaßen falsches ZIP-/TAR-Paar
reicht nicht aus.

Geprüft werden zusätzlich eindeutige Produktmodul-Identitäten und Versionen in
`pom.properties`, die Anwendungsidentität, `Main-Class`, `Implementation-Version`
und die Bytegleichheit der eingebetteten OpenAPI-Datei mit ihrer Quelle. Ein
zweites Anwendungsartefakt unter `lib` oder eine bereits veraltete
Produktbibliothek im Referenz-Classpath wird ebenfalls abgewiesen.

## Bestandteil von Maven package

Der letzte Produktreaktor-Baustein `regelsuche-quality-aggregate` führt
`MavenDistributionArchiveAuditIT` durch eine eigene Surefire-Ausführung in der
Phase `package` aus. Die bestehende Testabhängigkeit auf `regelsuche-app`
ordnet diesen Schritt hinter der tatsächlichen Paketierung ein, auch bei einem
parallelen Maven-Reaktor.

Die Ausführung wählt ausschließlich diesen Integritätsvertrag aus und setzt
`skip=false`, `skipTests=false` sowie beide Fail-if-no-tests-Schalter explizit.
Damit ist die Paketprüfung nicht nur Teil von

```bash
mvn --batch-mode --no-transfer-progress -Pfull verify
```

sondern bleibt auch für die abschließende Paketierung des bereits vollständig
geprüften Release-Checkouts vorgesehen:

```bash
mvn --batch-mode --no-transfer-progress -DskipTests package
```

**`-DskipTests` überspringt weiterhin die gewöhnlichen Tests, nicht diesen
Paketintegritätsvertrag.** Es ersetzt nach wie vor keine vollständige
Releasequalifikation. `maven.test.skip`, beliebige Modul-Teilauswahlen und direkte
Aufrufe einzelner Plugin-Ziele sind nicht der verwaltete Releasepfad. Insbesondere
umfasst `-pl app -am` nicht automatisch den nachgelagerten Aggregatbaustein.

Es gibt dafür keinen neuen Workflow, keinen Shell-/Python-Test, keinen
Archivierungs-Subprozess und keine zusätzliche Bibliothek oder Plugin-Version.
Der unveränderte Release-Workflow führt bereits das vollständige Maven-
`package` aus und erreicht die Publikationsschritte erst nach dessen Erfolg.

## Manueller, lesender Aufruf

Nach einer Produktpaketierung kann dieselbe Java-Prüfung separat ausgeführt
werden. Die Version muss zum tatsächlichen Build passen:

```bash
java -cp regelsuche-quality/target/classes \
  de.regelsuche.quality.release.DistributionArchiveVerifier \
  . 0.4.0-SNAPSHOT
```

Ein optionales drittes Argument bezeichnet ein anderes Verzeichnis mit JAR, ZIP
und TAR **aus demselben Build**. Die lokale Referenz bleibt der qualifizierte
Build samt seinem Runtime-Classpath:

```bash
java -cp regelsuche-quality/target/classes \
  de.regelsuche.quality.release.DistributionArchiveVerifier \
  . 0.4.0 /path/to/retained-release-assets
```

Das ist kein Versprechen byteidentischer Neu-Builds zu einem anderen Zeitpunkt.
Ein GitHub-Actions-Artefakt-ZIP ist außerdem ein äußerer Transportcontainer und
muss zunächst getrennt auf Identität und Prüfsummen geprüft werden. Der Verifier
behauptet weder einen erfolgten Download noch einen geprüften GitHub-/Zenodo-
Datensatz.

Erst nach vollständigem Erfolg wird eine Zeile mit
`distributionArchiveAudit=VERIFIED`, Version, Datei-/Bibliotheks-/Modulzahlen und
dem deterministischen Hash des Payload-Inventars ausgegeben. Die Maven-
Ausführung legt dieselbe Zeile unter
`regelsuche-quality-aggregate/target/discovery-artifacts/distribution-archive-audit.txt`
ab. Vor einem neuen Audit wird ein alter Erfolgsbeleg an dieser Stelle entfernt;
ein fehlgeschlagener inkrementeller Lauf darf ihn nicht erneut ausliefern.
Die vorhandene CI-Artefaktsammlung übernimmt den JUnit-Bericht und diesen Beleg.
Der Inventarhash ist ausdrücklich kein Hash des komprimierten Transportcontainers.

## Grenzen und negative Kontrollen

Die Prüfung entpackt nichts auf das Dateisystem. Sie verwirft zusätzliche,
fehlende und doppelte Dateien, nichtkanonische Pfade, veränderte Payloads,
falsche ZIP-CRC-Werte und fehlende Laufzeitdateien. Die TAR-Prüfung verlangt
korrekte Header-Prüfsummen, Größen, Padding und vollständige Endblöcke. Die
Launcher-Berechtigung wird **im TAR** geprüft; ZIP-Dateimodi werden durch diesen
JDK-basierten Verifier nicht ausgewertet.

Der TAR-Leser ist absichtlich kein allgemeines Archivierungswerkzeug. Er
akzeptiert das von der aktuellen Assembly verwendete POSIX-ustar-Format mit
regulären Dateien und Verzeichnissen. Links, Geräte, PAX-/GNU-Erweiterungen und
angehängte weitere Archive werden nicht stillschweigend interpretiert. Ein
künftiger Formatwechsel muss den Vertrag ausdrücklich erweitern und testen.

Das Modusfeld darf entweder nur die erwarteten Rechte enthalten oder zusätzlich
die zum Header-Typ passenden Unix-Dateityp-Bits, wie sie Plexus beim Schreiben
beibehält. Konkret sind reguläre Dateien `0644` oder `0100644`, der Unix-Launcher
`0755` oder `0100755` und Verzeichnisse `0755` oder `040755` erlaubt. Der Vergleich
ist exakt: Setuid-, Setgid-, Sticky- und unbekannte Bits sowie widersprüchliche
Dateitypen werden nicht durch eine Bitmaske versteckt. Die Fixture schreibt
standardmäßig die Plexus-Modi; zusätzliche Tests prüfen beide Darstellungen,
beide zulässigen Regular-File-Typmarker sowie absichtlich falsche Rechte und
Dateityp-Bits. Eine Modusfehlermeldung enthält Ist- und Sollwerte in Oktal.

Die endlichen Grenzen betragen ein GiB pro gelesener Payload-Datei, vier GiB
für die gesamte erwartete Payload, zwei MiB pro Metadateneingabe und höchstens
8192 Archiveinträge. Ein Überschreiten ist ein Fehler, kein Teil-Erfolg und kein
Anlass, die mathematischen Suchbudgets zu verändern.

Die JUnit-Negativkontrollen erzeugen kleine reale ZIP-/TAR-Dateien mit alten
SNAPSHOT-Namen, entfernten und Test-Bibliotheken, doppelter Anwendung, fehlenden
Dateien, geänderten Bytes, falschen CRC-/Headerwerten, doppelten Einträgen,
Pfadausbrüchen, Links, ungültigen Endmarkern und Versions-/OpenAPI-Drift. Die
gewöhnliche Maven-Paketprüfung verwendet zusätzlich die echten, nicht von der
Test-Fixture geschriebenen Produktarchive.

Dies ist ein Software-Paketintegritätsnachweis, keine mathematische Validierung,
Sicherheitszertifizierung oder unabhängige Veröffentlichungskontrolle. Die
Nachkontrolle im [Release-Betrieb](release-operations.md) bleibt erforderlich;
insbesondere ersetzt ein neuer grüner PR keinen auditierten Trockenlauf des
anschließend tatsächlich freigegebenen Hauptstands.
