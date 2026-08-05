# Release-Betrieb

Diese Seite beschreibt den operativen Ablauf für einen GitHub-Release. Sie ist
nicht mit der fachlichen [Release Readiness](release-readiness.md) zu
verwechseln: `release-readiness.md` begrenzt wissenschaftliche Claims und
Evidence Profiles; diese Seite beschreibt Versionierung, Trockenlauf,
Publikation und Nachkontrolle.

## Maßgebliche Quellen

Der Release-Ablauf besitzt genau zwei maßgebliche Quellen:

- `release.properties` legt die aktuelle Entwicklungsversion fest;
- `.github/workflows/release.yml` implementiert Auflösung, Prüfung,
  Paketierung und GitHub-spezifische Mutationen.

Die Versionsangaben in `CITATION.cff`, `CITATION.md`, `.zenodo.json` und
`codemeta.json` müssen zur Entwicklungsversion passen. Der Workflow prüft diese
Invariante vor jeder neuen Veröffentlichung.

Bei `version=0.2.0-SNAPSHOT` ist die zu veröffentlichende Version `0.2.0` und
der Tag `v0.2.0`. Die Eingabe für die nächste Version verändert nicht die zu
veröffentlichende Version; sie bestimmt ausschließlich die anschließende
Entwicklungslinie.

## Voraussetzungen

Vor einem Release müssen folgende Bedingungen erfüllt sein:

1. `main` ist grün und der maßgebliche Checkout-Aufruf
   `./gradlew --no-daemon --no-configuration-cache ciCheck` ist erfolgreich.
2. Es gibt keinen bekannten release-blockierenden Pull Request oder
   ungeklärten Fehler auf `main`.
3. `release.properties` enthält genau eine gültige `X.Y.Z-SNAPSHOT`-Version.
4. Die Zitier- und Metadatendateien enthalten dieselbe SNAPSHOT-Version.
5. Der Ziel-Tag und ein gleichnamiger GitHub Release stehen nicht in
   widersprüchlichem Zustand.

Offene Forschungs- und Roadmap-Issues blockieren einen Release nicht allein
aufgrund ihres offenen Zustands. Entscheidend sind die dokumentierte
Claim-Grenze, ein grüner Checkout und ein erfolgreich geprüfter Release-Lauf.

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
Checkout-Verifikation, Release-Metadatenumstellung, Paketierung,
Manifest-Erzeugung und SHA-256-Bildung wie ein echter Release aus. Er erzeugt
jedoch keinen Tag, keinen GitHub Release, keinen Maintenance-Branch und keinen
Folge-PR.

`skip_tests=true` ist kein normaler Release-Pfad. Diese Option reduziert die
Prüfung auf Assembly und darf nur für eine begründete Wiederherstellung eines
bereits anderweitig vollständig geprüften Zustands verwendet werden.

## Trockenlauf auswerten

Ein Trockenlauf gilt nur dann als erfolgreich, wenn:

- der Job **Verify, package and publish** vollständig grün ist;
- `ciCheck` den Checkout unverändert hinterlässt;
- das Artefakt `regelsuche-<version>-artifacts` vorhanden ist;
- ZIP, TAR und JAR sowie `RELEASE-MANIFEST.txt` und `SHA256SUMS.txt` enthalten
  sind;
- Manifest-Version, Tag und Source Commit dem beabsichtigten Release
  entsprechen;
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
2. erzeugt einen lokalen Commit mit Release-Metadaten;
3. baut ZIP, TAR und JAR und erzeugt Manifest sowie SHA-256-Summen;
4. erstellt und pusht den annotierten Tag `vX.Y.Z`;
5. aktualisiert `maintenance/X.Y.x` auf den getaggten Release-Commit;
6. erstellt den GitHub Release und lädt die Artefakte hoch;
7. stellt den autoritativen Stand von `main` wieder her;
8. öffnet bei Bedarf einen Pull Request für die nächste
   `X.Y.Z-SNAPSHOT`-Entwicklungsversion.

Die nächste Entwicklungsversion muss numerisch größer als die veröffentlichte
Version sein. Freie Eingaben werden getrimmt und strikt gegen das Format
`X.Y.Z-SNAPSHOT` geprüft.

## Nachkontrolle

Nach dem echten Lauf sind folgende Punkte zu prüfen:

- Tag `vX.Y.Z` existiert und zeigt auf den Release-Metadaten-Commit;
- der GitHub Release enthält alle erwarteten Artefakte und Prüfsummen;
- `maintenance/X.Y.x` zeigt auf denselben getaggten Commit;
- der Folge-PR enthält ausschließlich die nächste Entwicklungsversion in
  `release.properties`, `CITATION.cff`, `CITATION.md`, `.zenodo.json` und
  `codemeta.json`;
- nach Merge des Folge-PRs ist `main` erneut grün;
- die veröffentlichte Version ist in Zitiermetadaten und Release-Artefakten
  konsistent.

Ein Release-Issue oder eine Checkliste darf erst geschlossen werden, wenn diese
Nachkontrolle abgeschlossen ist.

## Fehler- und Wiederholungssemantik

Der Workflow erkennt vorhandene Tags und GitHub Releases getrennt. Ein
vorhandener Tag wird nur akzeptiert, wenn dessen `release.properties` exakt die
erwartete Release-Version enthält. Maintenance-Branches werden nur ersetzt,
wenn der vorherige Stand bereits durch einen passenden Release-Tag erhalten
ist. Diese Prüfungen verhindern, dass ein Wiederholungslauf widersprüchliche
Historie überschreibt.

Bei einem fehlgeschlagenen Trockenlauf wird nichts veröffentlicht. Bei einem
fehlgeschlagenen echten Lauf ist vor einer Wiederholung zuerst festzustellen,
welche externen Zustände bereits existieren: Tag, GitHub Release,
Maintenance-Branch und Folge-PR. Einzelne Zustände dürfen nicht manuell
"zurechtgebogen" werden, ohne die Versions- und Provenienzprüfungen des
Workflows erneut zu erfüllen.

## Lokale Vorprüfung

Die GitHub-spezifischen Mutationen sind nur im Workflow verfügbar. Die
maßgebliche lokale Vorprüfung bleibt:

```bash
./gradlew --no-daemon --no-configuration-cache ciCheck
```

Sie muss ohne unversionierte oder veränderte Dateien enden:

```bash
git status --short
```

Eine leere Ausgabe ist Teil der Release-Invariante.
