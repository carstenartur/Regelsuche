# Release-Betrieb

Diese Seite beschreibt den operativen Ablauf für einen GitHub-Release. Sie ist
nicht mit der fachlichen [Release Readiness](release-readiness.md) zu
verwechseln: `release-readiness.md` begrenzt wissenschaftliche Claims und
Evidence Profiles; diese Seite beschreibt Versionierung, Prüfung, Paketierung,
Publikation und Nachkontrolle.

## Maßgebliche Quellen

Der Release-Ablauf besitzt vier zusammengehörige, maschinell geprüfte Quellen:

- `release.properties` legt die aktuelle Entwicklungs- oder Release-Version
  fest;
- der Maven-Reaktor in `pom.xml` und allen Modul-POMs trägt exakt dieselbe
  Projektversion;
- `docs/releases/X.Y.Z.md` enthält die kuratierten, fachlich geprüften
  Release Notes der zu veröffentlichenden Version;
- `.github/workflows/release.yml` implementiert Auflösung, Prüfung,
  Maven-Paketierung und GitHub-spezifische Mutationen.

Die Versionsangaben in `CITATION.cff`, `CITATION.md`, `.zenodo.json` und
`codemeta.json` müssen ebenfalls übereinstimmen. Der Befehl

```bash
python3 .github/scripts/update-release-metadata.py 0.3.0-SNAPSHOT --check
```

prüft diese Invariante einschließlich sämtlicher POMs, ohne Dateien zu ändern.
Die gleiche Hilfsroutine stellt beim Release und beim Wechsel auf die nächste
Entwicklungsversion alle Quellen gemeinsam um.

Bei `version=0.3.0-SNAPSHOT` ist die zu veröffentlichende Version `0.3.0` und
der Tag `v0.3.0`. Die Angabe der nächsten Version verändert nicht die zu
veröffentlichende Version; sie bestimmt ausschließlich die anschließende
Entwicklungslinie. Für den nach 0.3.0 vorgesehenen Funktionszweig lautet sie
`0.4.0-SNAPSHOT`.

## Build-Vertrag

Die vollständige Produktqualifikation erfolgt autoritativ durch den
Maven-Vollprofil-Reaktor:

```bash
mvn --batch-mode --no-transfer-progress -Pfull verify
```

Er umfasst den gewöhnlichen Reaktor sowie die expliziten Produkt-,
PostgreSQL-, Testcontainers- und Docker-Verträge. Die Release-Pakete werden
anschließend aus den bereits geprüften Quellen mit der endgültigen Version
erzeugt:

```bash
mvn --batch-mode --no-transfer-progress \
  -DreleaseVersion=0.3.0 \
  -DskipTests \
  package
```

Das zweite Kommando erzeugt unter `app/target/` genau diese drei
Release-Kandidaten:

- `regelsuche-0.3.0.jar`;
- `regelsuche-0.3.0.zip`;
- `regelsuche-0.3.0.tar`.

Der Release-Workflow kontrolliert zusätzlich die `Implementation-Version` im
JAR, das gemeinsame Wurzelverzeichnis der Archive und die vollständige,
vorhersehbare Asset-Liste.

Der bestehende Gradle-Aufruf

```bash
./gradlew --no-daemon --no-configuration-cache ciCheck
```

bleibt während der Maven-Migration eine umfassende Kompatibilitäts- und
Evidence-Prüfung. Er ist nicht die Quelle der veröffentlichten Produktpakete.
Der Release-Workflow führt deshalb sowohl `ciCheck` als auch `mvn -Pfull verify`
aus, bevor irgendeine Publikationsmutation zulässig ist.

## Voraussetzungen

Vor einem Release müssen folgende Bedingungen erfüllt sein:

1. `main` ist grün.
2. `ciCheck` und `mvn -Pfull verify` sind erfolgreich.
3. Beide Befehle hinterlassen den Checkout unverändert.
4. `release.properties`, sämtliche POMs und die Zitiermetadaten enthalten
   dieselbe gültige `X.Y.Z-SNAPSHOT`-Version.
5. `docs/releases/X.Y.Z.md` ist nicht leer, beginnt exakt mit
   `# Regelsuche X.Y.Z` und enthält den geprüften Claim-Rahmen.
6. Es gibt keinen bekannten release-blockierenden Pull Request oder
   ungeklärten Fehler auf `main`.
7. Ziel-Tag und gleichnamiger GitHub Release stehen nicht in
   widersprüchlichem Zustand.

Offene Forschungs- und Roadmap-Issues blockieren einen Release nicht allein
aufgrund ihres offenen Zustands. Entscheidend sind die dokumentierte
Claim-Grenze, ein grüner Checkout und ein vollständig erfolgreicher
Release-Lauf.

## Release-Notes- und Issue-Audit

Jeder Release verwendet das eindeutige semantische Intervall
`previousTag..releaseTag`. `previousTag` ist der unmittelbar vorhergehende
veröffentlichte SemVer-Tag. Ist er nicht eindeutig bestimmbar, wird die
Veröffentlichung nicht freigegeben.

Vor der Veröffentlichung werden alle Issues geprüft, die im Intervall durch
Closing-Referenzen oder Erwähnungen in PRs, Commits und Reviews berührt wurden.
Übergeordnete Sammel-Issues der ausgelieferten Arbeit gehören ebenfalls dazu.

Ein Issue erscheint nur dann unter **Abgeschlossene Issues**, wenn alle
verbindlichen Akzeptanzkriterien erfüllt sind, Implementierung und Nachweise
vorliegen, ein Abschlusskommentar die maßgeblichen PRs nennt und der
GitHub-Schließungsgrund `completed` lautet. Teilweise erfüllte Sammel-Issues
bleiben offen; ein Audit-Kommentar nennt ausgelieferte Teilpakete und echte
Restarbeiten. `not planned`, Duplikat- und Ersetzt-Klassifikationen sind keine
ausgelieferten Merkmale.

Die versionierte Datei `docs/releases/X.Y.Z.md` trennt mindestens:

- benutzersichtbare Fähigkeiten;
- abgeschlossene Issues;
- Teilfortschritte offener Sammel-Issues;
- technische Änderungen aus demselben Tag-Intervall;
- Kompatibilität und bekannte Einschränkungen;
- Reproduktionsbefehle und Artefakte;
- wissenschaftliche Nicht-Claims.

Automatisch erzeugte GitHub-Hinweise ersetzen diesen Audit nicht und sind für
den verwalteten Release-Pfad nicht die öffentliche Release-Beschreibung. Der
Workflow veröffentlicht die kuratierte Datei mit `--notes-file`, bindet ihren
SHA-256 in `RELEASE-MANIFEST.txt` und vergleicht den danach von GitHub
zurückgelieferten Body mit der Quelldatei.

Vor dem echten Release werden beide Tags und Commit-SHAs, die im Intervall als
`completed` geschlossenen Issues, ausgeschlossene Verwaltungsentscheidungen,
berührte offene Issues samt aktualisiertem Status, die PR-Liste und die
geprüfte Release-Notes-Fassung festgehalten. Nach GitHub- und
Zenodo-Publikation wird kontrolliert, dass keine offenen Sammel-Issues als
vollständig erledigt und keine PRs außerhalb des Intervalls dargestellt werden.

## Verpflichtender Trockenlauf

Der normale Release beginnt immer mit einem Trockenlauf. Bevorzugt wird der
auditierbare Branch-Auslöser, weil er Versionen und Quell-SHA explizit im
Repositoryzustand bindet. Alternativ kann der Workflow auf `main` per
`workflow_dispatch` mit denselben Werten gestartet werden.

Für 0.3.0 und die nächste Entwicklungslinie 0.4.0 lautet der Branch:

```text
release/dry-run-v0.3.0-next-v0.4.0-SNAPSHOT
```

Der einzige zusätzliche Nicht-Merge-Commit darf ausschließlich
`.github/release-request` mit genau diesen drei Zeilen hinzufügen:

```text
mode=dry-run
release=0.3.0
next=0.4.0-SNAPSHOT
```

Der Trockenlauf führt dieselbe Versionsauflösung, Notes-Prüfung,
Metadatenprüfung, Gradle-Verifikation, Maven-Vollprofilqualifikation, lokale
Release-Metadatenumstellung, Maven-Paketierung, Manifest-Erzeugung und
SHA-256-Bildung wie ein echter Release aus. Er erzeugt jedoch keinen Tag,
keinen GitHub Release, keinen Maintenance-Branch und keinen Folge-PR.

`skip_tests=true` ist kein normaler Release-Pfad. Diese Option reduziert die
Vorprüfung auf eine Maven-Paketierung und darf nur für eine begründete
Wiederherstellung eines bereits anderweitig vollständig geprüften Zustands
verwendet werden.

## Auditierbarer Automationsauslöser

Für Werkzeuge mit GitHub-Schreibzugriff, aber ohne Zugriff auf
`workflow_dispatch`, existiert ein bewusst enger Auslöser. Eine
Anforderungs-Branch muss unmittelbar auf dem aktuellen `main` basieren und
exakt einen zusätzlichen Nicht-Merge-Commit besitzen. Dieser Commit darf nur
`.github/release-request` ändern.

Trockenlauf:

```text
release/dry-run-v0.3.0-next-v0.4.0-SNAPSHOT
```

Echter Lauf:

```text
release/run-v0.3.0-next-v0.4.0-SNAPSHOT
```

Die Datei muss beim echten Lauf entsprechend enthalten:

```text
mode=run
release=0.3.0
next=0.4.0-SNAPSHOT
```

Der Workflow vergleicht Branchname, Payload, Trigger-SHA, einzigen
Eltern-Commit, aktuellen `main` und die in `release.properties` deklarierte
Version. Jede zusätzliche Änderung, ein veralteter Eltern-Commit oder eine
abweichende Versionsangabe beendet den Lauf vor allen Mutationen. Dieser Pfad
ist kein Umgehen der Release-Prüfung, sondern lediglich eine andere,
vollständig protokollierte Auslösung desselben Jobs.

## Trockenlauf auswerten

Ein Trockenlauf gilt nur dann als erfolgreich, wenn:

- der Job **Verify, package and publish** vollständig grün ist;
- `ciCheck` und `mvn -Pfull verify` erfolgreich sind;
- der Checkout nach den Prüfungen unverändert ist;
- `docs/releases/0.3.0.md` geprüft und im Manifest per SHA-256 gebunden ist;
- das Workflow-Artefakt `regelsuche-0.3.0-artifacts` vorhanden ist;
- ZIP, TAR und JAR sowie `RELEASE-MANIFEST.txt` und `SHA256SUMS.txt` enthalten
  sind;
- Manifest-Version, Tag, Quell-Commit, Release-Commit und Notes-Hash dem
  beabsichtigten Kandidaten entsprechen;
- keine Warnung eine übersprungene oder abgeschwächte Prüfung anzeigt;
- weder Tag, GitHub Release, Maintenance-Branch noch Folge-PR angelegt wurden.

Die im Trockenlauf erzeugten Binärartefakte dienen als Nachweis des
Release-Kandidaten. Der echte Lauf baut sie erneut aus demselben autoritativen
`main`; die Artefakte des Trockenlaufs werden nicht nachträglich publiziert.

## Echten Release ausführen

Nach einem vollständig auditierten Trockenlauf wird der eine Commit umfassende
Branch `release/run-v0.3.0-next-v0.4.0-SNAPSHOT` aus demselben eingefrorenen
`main` erzeugt. Die Request-Datei unterscheidet sich nur durch `mode=run`.

Der Workflow:

1. prüft Notes, Metadaten und Checkout erneut vollständig;
2. führt `ciCheck` und `mvn -Pfull verify` aus;
3. erzeugt einen lokalen Commit, in dem Release-Metadaten und sämtliche POMs
   gemeinsam auf `0.3.0` stehen;
4. baut JAR, ZIP und TAR mit Maven;
5. erzeugt ein Herkunftsmanifest einschließlich Notes-Hash und die
   SHA-256-Summen der fünf Assets;
6. erstellt und pusht den annotierten Tag `v0.3.0`;
7. aktualisiert `maintenance/0.3.x` auf den getaggten Release-Commit;
8. erstellt den GitHub Release mit `docs/releases/0.3.0.md` als Body und lädt
   genau die fünf vereinbarten Assets hoch;
9. prüft Body, Asset-Liste, Draft- und Pre-Release-Status;
10. stellt den autoritativen Stand von `main` wieder her;
11. öffnet bei Bedarf einen Pull Request für `0.4.0-SNAPSHOT` einschließlich
    aller POMs und Metadaten.

Die nächste Entwicklungsversion muss numerisch größer als die veröffentlichte
Version sein. Freie Eingaben werden getrimmt und strikt gegen das Format
`X.Y.Z-SNAPSHOT` geprüft.

## Release-Assets

Der veröffentlichte GitHub Release enthält exakt fünf Dateien:

- `regelsuche-0.3.0.jar`;
- `regelsuche-0.3.0.zip`;
- `regelsuche-0.3.0.tar`;
- `RELEASE-MANIFEST.txt`;
- `SHA256SUMS.txt`.

Die kuratierten Release Notes sind keine sechste Binärdatei. Sie liegen im
getaggten Quellbaum, sind als GitHub-Release-Body veröffentlicht und werden
über `release_notes_file` sowie `release_notes_sha256` im Manifest gebunden.

## Zenodo

Zenodo ist ein nachgelagerter Publikationsschritt der GitHub-Integration. Erst
ein erfolgreich angelegter, nicht als Entwurf markierter GitHub Release kann
eine neue Zenodo-Version auslösen. Deshalb wird Zenodo niemals als Beleg für
einen nur lokal gebauten oder lediglich getaggten Kandidaten verwendet.

Nach dem GitHub Release ist zu prüfen:

- Zenodo hat eine neue Version für `v0.3.0` angelegt;
- Version, Titel, Autoren, Veröffentlichungsdatum und Dateien stimmen mit den
  Release-Metadaten überein;
- die Konzept-DOI verweist auf die neue jüngste Version;
- die vorherige Zenodo-Version bleibt unverändert und zitierfähig.

## Nachkontrolle

Nach dem echten Lauf sind folgende Punkte zu prüfen:

- Tag `v0.3.0` existiert und zeigt auf den Release-Metadaten-Commit;
- der öffentliche Body entspricht `docs/releases/0.3.0.md`;
- der GitHub Release enthält JAR, ZIP, TAR, Manifest und Prüfsummen;
- die fünf Asset-Hashes und der im Manifest angegebene Notes-Hash stimmen;
- `maintenance/0.3.x` zeigt auf denselben getaggten Commit;
- der Folge-PR enthält ausschließlich `0.4.0-SNAPSHOT` in
  `release.properties`, `CITATION.cff`, `CITATION.md`, `.zenodo.json`,
  `codemeta.json`, dem Root-POM und allen Modul-POMs;
- nach Merge des Folge-PRs ist `main` erneut grün;
- GitHub und Zenodo zeigen die tatsächlich veröffentlichte Version.

Ein Release-Issue oder eine Checkliste darf erst geschlossen werden, wenn diese
Nachkontrolle abgeschlossen ist.

## Fehler- und Wiederholungssemantik

Der Workflow erkennt vorhandene Tags und GitHub Releases getrennt. Ein GitHub
Release ohne den zugehörigen Tag wird als inkonsistenter Zustand abgelehnt. Ein
vorhandener Tag wird nur akzeptiert, wenn dessen `release.properties` und alle
weiteren Metadaten exakt die erwartete Release-Version enthalten.
Maintenance-Branches werden nur ersetzt, wenn der vorherige Stand bereits
durch einen passenden Release-Tag erhalten ist.

Ein Tag-Push startet außerdem denselben Workflow als Wiederherstellungspfad.
Die globale Release-Serialisierung verhindert konkurrierende Publikationen.
Existiert der GitHub Release bereits vollständig, überspringt der
Wiederholungslauf Build und Upload und kontrolliert den veröffentlichten
Zustand einschließlich Body und Asset-Liste.

Bei einem fehlgeschlagenen Trockenlauf wird nichts veröffentlicht. Bei einem
fehlgeschlagenen echten Lauf ist vor einer Wiederholung zuerst festzustellen,
welche externen Zustände bereits existieren: Tag, GitHub Release,
Maintenance-Branch und Folge-PR. Einzelne Zustände dürfen nicht manuell
„zurechtgebogen“ werden, ohne die Versions- und Provenienzprüfungen des
Workflows erneut zu erfüllen.

## Lokale Vorprüfung für 0.3.0

```bash
python3 .github/scripts/update-release-metadata.py 0.3.0-SNAPSHOT --check
./gradlew --no-daemon --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
mvn --batch-mode --no-transfer-progress \
  -DreleaseVersion=0.3.0 \
  -DskipTests \
  package
git status --short
```

Eine leere Ausgabe des letzten Befehls ist Teil der Release-Invariante. Vor dem
Trockenlauf ist außerdem zu prüfen, dass `docs/releases/0.3.0.md` die final
auditierten Notes enthält und keine offene Release-Blocker-PR mehr existiert.
