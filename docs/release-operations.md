# Release-Betrieb

Diese Seite beschreibt den operativen Ablauf für einen GitHub-Release. Sie ist
nicht mit der fachlichen [Release Readiness](release-readiness.md) zu
verwechseln: `release-readiness.md` begrenzt wissenschaftliche Claims und
Evidence Profiles; diese Seite beschreibt Versionierung, Prüfung, Paketierung,
Publikation und Nachkontrolle.

## Maßgebliche Quellen

Der Release-Ablauf besitzt drei zusammengehörige, maschinell geprüfte Quellen:

- `release.properties` legt die aktuelle Entwicklungs- oder Release-Version
  fest;
- der Maven-Reaktor in `pom.xml` und allen Modul-POMs trägt exakt dieselbe
  Projektversion;
- `.github/workflows/release.yml` implementiert Auflösung, Prüfung,
  Maven-Paketierung und GitHub-spezifische Mutationen.

Die Versionsangaben in `CITATION.cff`, `CITATION.md`, `.zenodo.json` und
`codemeta.json` müssen ebenfalls übereinstimmen. Der Befehl

```bash
python3 .github/scripts/update-release-metadata.py 0.2.0-SNAPSHOT --check
```

prüft diese Invariante einschließlich sämtlicher POMs, ohne Dateien zu ändern.
Die gleiche Hilfsroutine stellt beim Release und beim Wechsel auf die nächste
Entwicklungsversion alle Quellen gemeinsam um.

Bei `version=0.2.0-SNAPSHOT` ist die zu veröffentlichende Version `0.2.0` und
der Tag `v0.2.0`. Die Angabe der nächsten Version verändert nicht die zu
veröffentlichende Version; sie bestimmt ausschließlich die anschließende
Entwicklungslinie.

## Build-Vertrag

Die Produktartefakte werden autoritativ durch Maven gebaut:

```bash
mvn --batch-mode --no-transfer-progress test
mvn --batch-mode --no-transfer-progress -DreleaseVersion=0.2.0 -DskipTests package
```

Das zweite Kommando erzeugt unter `app/target/` genau diese drei
Release-Kandidaten:

- `regelsuche-0.2.0.jar`;
- `regelsuche-0.2.0.zip`;
- `regelsuche-0.2.0.tar`.

Der Release-Workflow kontrolliert zusätzlich die `Implementation-Version` im
JAR, das gemeinsame Wurzelverzeichnis der Archive und die vollständige,
vorhersehbare Asset-Liste.

Der bestehende Gradle-Aufruf

```bash
./gradlew --no-daemon --no-configuration-cache ciCheck
```

bleibt während der Maven-Migration eine umfassende Kompatibilitäts- und
Evidence-Prüfung. Er ist nicht mehr die Quelle der veröffentlichten
Produktpakete. Damit sind fachliche Vollprüfung und reproduzierbare
Produktverpackung klar getrennt, ohne eine der beiden Prüfungen zu schwächen.

## Voraussetzungen

Vor einem Release müssen folgende Bedingungen erfüllt sein:

1. `main` ist grün.
2. `ciCheck` und `mvn test` sind erfolgreich.
3. Beide Befehle hinterlassen den Checkout unverändert.
4. `release.properties`, sämtliche POMs und die Zitiermetadaten enthalten
   dieselbe gültige `X.Y.Z-SNAPSHOT`-Version.
5. Es gibt keinen bekannten release-blockierenden Pull Request oder
   ungeklärten Fehler auf `main`.
6. Ziel-Tag und gleichnamiger GitHub Release stehen nicht in
   widersprüchlichem Zustand.

Offene Forschungs- und Roadmap-Issues blockieren einen Release nicht allein
aufgrund ihres offenen Zustands. Entscheidend sind die dokumentierte
Claim-Grenze, ein grüner Checkout und ein vollständig erfolgreicher
Release-Lauf.

## Release-Notes- und Issue-Audit

Jeder Release verwendet das eindeutige semantische Intervall
`previousTag..releaseTag`. `previousTag` ist der unmittelbar vorhergehende
veröffentlichte SemVer-Tag. Ist er nicht eindeutig bestimmbar, wird die
automatische Veröffentlichung abgebrochen.

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

Die Release Notes trennen abgeschlossene Issues, optionale Teilfortschritte
offener Issues, technische PR-Änderungen aus demselben Tag-Intervall, bekannte
Einschränkungen sowie Reproduktion und Artefakte. Automatisch erzeugte
GitHub-Hinweise sind nur die technische PR-Übersicht und müssen mindestens mit
`--notes-start-tag "$PREVIOUS_TAG"` dieselbe Grenze verwenden; sie ersetzen den
Issue-Audit nicht.

Vor dem echten Release werden beide Tags und Commit-SHAs, die im Intervall als
`completed` geschlossenen Issues, ausgeschlossene Verwaltungsentscheidungen,
berührte offene Issues samt aktualisiertem Status, die PR-Liste und die
geprüfte Release-Notes-Fassung festgehalten. Nach GitHub- und
Zenodo-Publikation wird kontrolliert, dass keine offenen Sammel-Issues als
vollständig erledigt und keine PRs außerhalb des Intervalls dargestellt werden.

## Verpflichtender Trockenlauf

Der normale Release beginnt immer mit einem Trockenlauf:

1. GitHub Actions öffnen und den Workflow **Release** auswählen.
2. **Run workflow** auf `main` starten.
3. `dry_run` auf `true` setzen.
4. `skip_tests` auf `false` belassen.
5. Für die nächste Entwicklungslinie `patch`, `minor` oder `major` wählen.
   Eine exakte Version wie `0.3.0-SNAPSHOT` nur eintragen, wenn die
   konventionelle Auswahl den beabsichtigten Wert nicht ausdrückt.

Der Trockenlauf führt dieselbe Versionsauflösung, Metadatenprüfung,
Checkout-Verifikation, lokale Release-Metadatenumstellung, Maven-Paketierung,
Manifest-Erzeugung und SHA-256-Bildung wie ein echter Release aus. Er erzeugt
jedoch keinen Tag, keinen GitHub Release, keinen Maintenance-Branch und keinen
Folge-PR.

`skip_tests=true` ist kein normaler Release-Pfad. Diese Option reduziert die
Vorprüfung auf eine Maven-Paketierung und darf nur für eine begründete
Wiederherstellung eines bereits anderweitig vollständig geprüften Zustands
verwendet werden.

## Auditierbarer Automationsauslöser

Für Werkzeuge mit GitHub-Schreibzugriff, aber ohne Zugriff auf
`workflow_dispatch`, existiert ein bewusst enger, auditierbarer Auslöser. Eine
Anforderungs-Branch muss unmittelbar auf dem aktuellen `main` basieren und
exakt einen zusätzlichen Nicht-Merge-Commit besitzen. Dieser Commit darf nur
`.github/release-request` ändern.

Trockenlauf:

```text
release/dry-run-v0.2.0-next-v0.3.0-SNAPSHOT
```

Echter Lauf:

```text
release/run-v0.2.0-next-v0.3.0-SNAPSHOT
```

Die Datei muss genau drei Zeilen enthalten:

```text
mode=dry-run
release=0.2.0
next=0.3.0-SNAPSHOT
```

beziehungsweise für die Veröffentlichung `mode=run`. Der Workflow vergleicht
Branchname, Payload, Trigger-SHA, einzigen Eltern-Commit, aktuellen `main` und
die in `release.properties` deklarierte Version. Jede zusätzliche Änderung,
ein veralteter Eltern-Commit oder eine abweichende Versionsangabe beendet den
Lauf vor allen Mutationen. Dieser Pfad ist damit kein Umgehen der
Release-Prüfung, sondern lediglich eine andere, vollständig protokollierte
Auslösung desselben Jobs.

## Trockenlauf auswerten

Ein Trockenlauf gilt nur dann als erfolgreich, wenn:

- der Job **Verify, package and publish** vollständig grün ist;
- `ciCheck` und der vollständige Maven-Produktreaktor erfolgreich sind;
- der Checkout nach den Prüfungen unverändert ist;
- das Workflow-Artefakt `regelsuche-<version>-artifacts` vorhanden ist;
- ZIP, TAR und JAR sowie `RELEASE-MANIFEST.txt` und `SHA256SUMS.txt` enthalten
  sind;
- Manifest-Version, Tag, Quell-Commit und Release-Commit dem beabsichtigten
  Kandidaten entsprechen;
- keine Warnung eine übersprungene oder abgeschwächte Prüfung anzeigt.

Die im Trockenlauf erzeugten Binärartefakte dienen als Nachweis des
Release-Kandidaten. Der echte Lauf baut sie erneut aus demselben autoritativen
`main`; die Artefakte des Trockenlaufs werden nicht nachträglich publiziert.

## Echten Release ausführen

Nach einem erfolgreichen Trockenlauf wird derselbe Workflow erneut auf `main`
mit denselben Versionsangaben gestartet, diesmal mit:

- `dry_run=false`;
- `skip_tests=false`.

Der Workflow:

1. prüft den Checkout erneut vollständig;
2. erzeugt einen lokalen Commit, in dem Release-Metadaten und sämtliche POMs
   gemeinsam auf `X.Y.Z` stehen;
3. baut JAR, ZIP und TAR mit Maven;
4. erzeugt ein Herkunftsmanifest und SHA-256-Summen;
5. erstellt und pusht den annotierten Tag `vX.Y.Z`;
6. aktualisiert `maintenance/X.Y.x` auf den getaggten Release-Commit;
7. erstellt den GitHub Release und lädt genau die fünf vereinbarten Assets
   hoch;
8. prüft, dass der Release weder Entwurf noch Pre-Release ist und dass die
   Asset-Liste exakt stimmt;
9. stellt den autoritativen Stand von `main` wieder her;
10. öffnet bei Bedarf einen Pull Request für die nächste
    `X.Y.Z-SNAPSHOT`-Entwicklungsversion einschließlich aller POMs.

Die nächste Entwicklungsversion muss numerisch größer als die veröffentlichte
Version sein. Freie Eingaben werden getrimmt und strikt gegen das Format
`X.Y.Z-SNAPSHOT` geprüft.

## Zenodo

Zenodo ist ein nachgelagerter Publikationsschritt der GitHub-Integration. Erst
ein erfolgreich angelegter, nicht als Entwurf markierter GitHub Release kann
eine neue Zenodo-Version auslösen. Deshalb wird Zenodo niemals als Beleg für
einen nur lokal gebauten oder lediglich getaggten Kandidaten verwendet.

Nach dem GitHub Release ist zu prüfen:

- Zenodo hat eine neue Version für `vX.Y.Z` angelegt;
- Version, Titel, Autoren, Veröffentlichungsdatum und Dateien stimmen mit den
  Release-Metadaten überein;
- die Konzept-DOI verweist auf die neue jüngste Version;
- die vorherige Zenodo-Version bleibt unverändert und zitierfähig.

## Nachkontrolle

Nach dem echten Lauf sind folgende Punkte zu prüfen:

- Tag `vX.Y.Z` existiert und zeigt auf den Release-Metadaten-Commit;
- der GitHub Release enthält JAR, ZIP, TAR, Manifest und Prüfsummen;
- `maintenance/X.Y.x` zeigt auf denselben getaggten Commit;
- der Folge-PR enthält ausschließlich die nächste Entwicklungsversion in
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
Wiederholungslauf Build und Upload und kontrolliert nur den veröffentlichten
Zustand.

Bei einem fehlgeschlagenen Trockenlauf wird nichts veröffentlicht. Bei einem
fehlgeschlagenen echten Lauf ist vor einer Wiederholung zuerst festzustellen,
welche externen Zustände bereits existieren: Tag, GitHub Release,
Maintenance-Branch und Folge-PR. Einzelne Zustände dürfen nicht manuell
"zurechtgebogen" werden, ohne die Versions- und Provenienzprüfungen des
Workflows erneut zu erfüllen.

## Lokale Vorprüfung

Für `0.2.0-SNAPSHOT` lautet die vollständige lokale Vorprüfung:

```bash
python3 .github/scripts/update-release-metadata.py 0.2.0-SNAPSHOT --check
./gradlew --no-daemon --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress test
mvn --batch-mode --no-transfer-progress \
  -DreleaseVersion=0.2.0 \
  -DskipTests \
  package
git status --short
```

Eine leere Ausgabe des letzten Befehls ist Teil der Release-Invariante.
