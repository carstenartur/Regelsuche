# Audit eines veröffentlichten Releases

Der Veröffentlichungsworkflow prüft Assets und Release-Body bereits unmittelbar
nach dem Upload. Die nachgelagerte Prüfung auf dieser Seite ist davon getrennt:
Sie lädt einen bereits öffentlichen Release erneut herunter, rekonstruiert seine
Herkunft aus Tag und Manifest und führt minimale Produktpfade ausschließlich aus
den veröffentlichten Dateien aus.

Damit wird nicht nur geprüft, was der Veröffentlichungsjob hochladen wollte,
sondern was ein unabhängiger Nutzer tatsächlich vom öffentlichen GitHub-Release
und vom zugehörigen Zenodo-Konzept erhält.

## Ausführung

Ein Audit ist absichtlich opt-in. Ein Pull Request mit einem Head-Branch im
Format

```text
release/audit-vX.Y.Z
```

aktiviert `PublishedReleaseAuditTest` innerhalb des normalen Maven- und
CI-Vertrags. Für Regelsuche 0.3.0 lautet der Branch:

```text
release/audit-v0.3.0
```

Lokal kann dieselbe Prüfung aus einem sauberen Checkout gestartet werden:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl maven-build-contract -am \
  -DreleaseAuditVersion=0.3.0 \
  -Dtest=PublishedReleaseAuditTest \
  test
```

Benötigt werden JDK 25, Maven, Bash, Python 3, GitHub CLI, `jq`, `curl`,
`sha256sum`, `unzip` und `tar`. Die GitHub CLI muss angemeldet sein oder ein
lesendes `GH_TOKEN`/`GITHUB_TOKEN` erhalten. In GitHub Actions verwendet die
Prüfung das vorhandene read-only `GITHUB_TOKEN`; für öffentliche Downloads sind
keine Schreibrechte nötig.

## Geprüfte GitHub-Grenze

Der Audit akzeptiert nur einen veröffentlichten, nicht als Entwurf oder
Prerelease markierten Release mit exakt diesen fünf Assets:

```text
RELEASE-MANIFEST.txt
SHA256SUMS.txt
regelsuche-X.Y.Z.jar
regelsuche-X.Y.Z.tar
regelsuche-X.Y.Z.zip
```

Er prüft unter anderem:

- Release-Titel und Tag sowie den Body bytegenau gegen die getaggte
  `docs/releases/X.Y.Z.md`, einschließlich abschließender Zeilenumbrüche;
- GitHub-Assetstatus, deklarierte Größe und einen verpflichtenden, formal
  gültigen SHA-256-Digest für jedes Asset;
- alle lokal neu berechneten SHA-256-Werte sowie die exakte Mitgliedschaft von
  `SHA256SUMS.txt`;
- den vollständigen, eindeutigen Schlüsselsatz von `RELEASE-MANIFEST.txt`;
- den annotierten Tag und den dadurch bezeichneten Release-Commit;
- den einzigen Eltern-Commit als im Manifest gebundenen eingefrorenen
  `main`-Stand;
- `maintenance/X.Y.x` gegen denselben Release-Commit;
- die Hashes der getaggten Release Notes, `release.properties`, OpenAPI und des
  Root-POM;
- `Implementation-Version` im JAR sowie gemeinsame ZIP- und TAR-Wurzeln;
- die im JAR tatsächlich verpackte Cytoscape-Version.

Der Audit ist tagbezogen. Er kann deshalb auch nach einem späteren Release noch
eine ältere Version prüfen und fordert nicht, dass der geprüfte Tag weiterhin
GitHubs global jüngster Release ist.

### Sichere Archivgrenze

Dateinamen allein genügen nicht für eine sichere Archivprüfung. Vor dem
Entpacken werden ZIP- und TAR-Einträge deshalb zusätzlich auf Typ und Ziel
geprüft. Verboten sind insbesondere:

- absolute Pfade, `..`, Backslashes, NUL-Zeichen und doppelte Einträge;
- Einträge außerhalb der erwarteten Wurzel `regelsuche-X.Y.Z/`;
- symbolische und harte Links;
- Geräte, FIFOs und andere Spezialdateien.

Die ZIP-Datei wird anschließend mit einer eigenen no-follow-Extraktion in ein
neues temporäres Verzeichnis entpackt. Jeder aufgelöste Zielpfad muss weiterhin
innerhalb dieses Verzeichnisses liegen.

## Produkt-Smoketests

Nach der strukturellen Prüfung werden keine Klassen oder Build-Ausgaben des
aktuellen Checkouts verwendet. Beide Pfade laufen aus der heruntergeladenen
Distribution:

1. Das feste lineare Gleichungssystem wird als `A*x=b` erkannt. Der Audit prüft
   nicht nur die Bezeichnung, sondern die exakte Ausgabe
   `RREF(A|b) = [[1, 0 | 2], [0, 1 | 1]]` und die Lösung `[x=2, y=1]`.
2. Für `x^4 + 4*y^4` wird eine quartische Zerlegung synthetisiert. Der Audit
   faltet die beiden ausgegebenen Koeffizientenvektoren selbst wieder zusammen
   und akzeptiert nur einen Kandidaten, der exakt die Zielkoeffizienten
   `[1, 0, 0, 0, 4]` rekonstruiert und ein formal gültiges Zertifikat trägt.

Diese Smoketests belegen die Ausführbarkeit genau dieser zwei veröffentlichten
Pfade. Sie ersetzen weder den vollständigen Checkout-Testvertrag noch eine
allgemeine mathematische oder funktionale Vollständigkeitsbehauptung.

## Zenodo

Die erste Zenodo-DOI im getaggten README dient ausschließlich als
**Seed-Datensatz**. Historische Stände können dort eine konkrete Versions-DOI
statt der Konzept-DOI enthalten. Der Audit lädt den Seed-Datensatz und verwendet
danach ausschließlich dessen von Zenodo selbst ausgegebene Links:

- `links.versions` für die vollständige Versionslinie;
- `links.latest` für den autoritativen jüngsten Datensatz;
- `links.parent_doi` für die tatsächliche Konzept-DOI;
- `links.parent` für die Parent-Ressource.

Damit hängt der Audit weder vom veralteten Suchfeld `conceptrecid` noch von
einem geratenen internen Indexfeld ab. Die Versions-API wird mit höchstens 25
Treffern pro Seite vollständig paginiert. Der Abruf ist auf zehn Seiten
begrenzt und verwirft unvollständige oder doppelte Folgen. Überschreitet eine
Konzeptlinie diese explizite Grenze von 250 Versionen, wird sie nicht
stillschweigend abgeschnitten, sondern als außerhalb des aktuellen
Auditvertrags abgelehnt.

Für die vollständig geladene Konzeptlinie verlangt der Audit:

- genau einen Regelsuche-Datensatz mit der geprüften Version und dem getaggten
  Titel;
- denselben Datensatz über `links.latest` als jüngste Version des Konzepts;
- unveränderte Existenz mindestens einer früheren Version;
- Übereinstimmung von Version, Veröffentlichungsdatum und der aus
  `links.parent_doi` abgeleiteten Konzept-DOI;
- exakte Übereinstimmung der Creator-Namen und normalisierten ORCID-Werte mit
  den getaggten Metadaten, statt einen bestimmten Namen im Audit zu codieren;
- mindestens eine eindeutig versionsgebundene Regelsuche-Softwaredatei ohne
  eingemischte veraltete SemVer-Angabe.

Fehlt die erwartete Version, nennt die Fehlermeldung die Konzept-DOI und alle
tatsächlich veröffentlichten Versionen. Ein noch nicht von Zenodo erzeugter
Datensatz wird dadurch als realer Publikationsmangel sichtbar und nicht durch
eine breitere Titelsuche oder einen lokalen Ersatzdatensatz verdeckt.

Die GitHub-Zenodo-Integration darf statt der fünf GitHub-Binärassets einen
getaggten Quell-Snapshot archivieren. Deshalb wird keine künstliche
Dateinamenidentität zwischen den beiden Diensten behauptet. Entscheidend sind
die Versions-, Metadaten-, Konzept- und Softwaredateigrenzen.

## Ergebnisartefakt

Ein erfolgreicher Lauf schreibt kanonisches JSON nach

```text
build/reports/release-audit/X.Y.Z.json
```

Das normale CI-Artefakt nimmt `build/reports/**` bereits auf. Der Bericht bindet
Release- und Quell-Commit, Tagobjekt, Maintenance-Commit, Assetgrößen,
GitHub-Digests und lokal berechnete SHA-256-Werte, Manifest, Paketwurzel,
Cytoscape-Version, beide Smoketests sowie Zenodo-Seed-DOI, Versions-DOI,
Konzept-DOI und Dateiliste.

## Aussagegrenze

Ein grüner Audit belegt, dass die öffentliche Distribution und ihre gebundene
Herkunft für die geprüfte Version konsistent und in den zwei definierten
Smokepfaden ausführbar sind. Er belegt keine externe mathematische Neuheit,
keine allgemeine Solver-Vollständigkeit, keine Produktionssicherheit und keine
Überlegenheit gegenüber anderen Systemen.
