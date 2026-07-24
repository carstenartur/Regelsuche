# Release-Prozess

Dieses Dokument beschreibt die technische Freigabe eines Regelsuche-Releases. Wissenschaftliche Claims werden weiterhin ausschließlich durch die getrennten, versionierten Evidence-Profile in [Release Readiness](release-readiness.md) autorisiert.

## Grundsätze

- Ausgangspunkt ist ausschließlich ein eindeutig benannter und grüner Commit aus `main`; der Release-Metadaten-Commit wird deterministisch direkt daraus erzeugt.
- Der Commit muss die vollständige checkout-lokale QA mit `ciCheck` bestanden haben.
- Ein Release wird nicht mit übersprungenen Tests erzeugt.
- Die Release-Metadaten, Binärartefakte und der Tag müssen denselben durch einen Git-Tree-Hash gebundenen Release-Stand repräsentieren.
- Ein erfolgreicher Software-Release erweitert keine mathematischen Claims über die gebundenen Evidence-Profile hinaus.

## Release Candidate

Vor der Veröffentlichung wird der Candidate funktional eingefroren. Zulässig sind danach nur noch Änderungen, die einen belegten Release-Blocker beheben. Jede Änderung erzeugt einen neuen Candidate und erfordert die vollständige QA erneut.

Der maßgebliche Prüfpfad lautet:

```bash
./gradlew --no-daemon --no-configuration-cache ciCheck
```

`ciCheck` umfasst den vollständigen Test- und Vertragsgraphen, die Release-Readiness-Evidence, Solver-Prüfungen, Benchmarks sowie die Docker-Reproduktion. Details stehen in [Testing](testing.md).

## Freigabekriterien

Ein Candidate ist freigabefähig, wenn:

1. keine bekannten Fehler in mathematischer Semantik, Datenintegrität oder Release-Erzeugung offen sind;
2. alle verpflichtenden Checks auf exakt dem zu veröffentlichenden Commit erfolgreich sind;
3. offene Forschungs-, Benchmark- und Ökosystem-Issues ausdrücklich außerhalb des Release-Scopes liegen;
4. Versionen in `release.properties`, `CITATION.cff`, `CITATION.md`, `.zenodo.json` und `codemeta.json` konsistent sind;
5. die erzeugten Distributionen unabhängig vom Entwickler-Workspace starten;
6. `SHA256SUMS.txt` sämtliche veröffentlichten Artefakte abdeckt;
7. bekannte Einschränkungen und Claim-Grenzen in der Dokumentation sichtbar bleiben.

## GitHub-Workflow

Der Workflow `.github/workflows/release.yml` wird zunächst als Dry Run und anschließend für die Veröffentlichung ausgeführt.

### Dry Run

- Workflow: `Release`
- Branch: `main`
- `release_version`: Basisversion aus `release.properties` ohne `-SNAPSHOT`
- `next_development_version`: optional; andernfalls wird die Patch-Version erhöht
- `dry_run`: `true`

Der Dry Run muss die vollständige QA ausführen, Release-Metadaten in der Arbeitskopie auf die Zielversion umstellen, Distributionen erzeugen und Checksummen bereitstellen. Er darf weder Tag noch GitHub Release noch Folge-PR erzeugen.

### Veröffentlichung

Nach Prüfung des Dry-Run-Artefakts wird derselbe Workflow auf demselben Candidate mit `dry_run: false` ausgeführt. Der Workflow:

1. prüft Version und Metadaten;
2. führt dieselbe vollständige QA aus;
3. erzeugt Distributionen und `SHA256SUMS.txt`;
4. bindet den geprüften Release-Baum über seinen Git-Tree-Hash;
5. erstellt den Release-Metadaten-Commit und den annotierten Tag nur für exakt diesen Baum;
6. veröffentlicht oder repariert den GitHub Release idempotent;
7. eröffnet einen Folge-PR für die nächste `-SNAPSHOT`-Version.

## Nachkontrolle

Nach der Veröffentlichung werden mindestens geprüft:

- Tag und GitHub Release zeigen dieselbe Version;
- alle erwarteten ZIP-, TAR-, JAR- und Checksum-Artefakte sind vorhanden;
- die Checksummen stimmen nach erneutem Download;
- Quickstart und CLI starten aus dem Release-Artefakt;
- der Folge-PR setzt die nächste Entwicklungsversion konsistent;
- Dokumentation und Capability-Matrix behaupten keine stärkeren Claims als die gebundene Evidence.

Ein abgebrochener Workflow darf wiederholt werden. Ein vorhandener Tag oder Release wird dabei nur wiederverwendet, wenn sein Git-Baum exakt dem zuvor geprüften QA-Baum entspricht; Asset-Aktualisierungen werden sichtbar protokolliert. Ändert sich der Inhalt, ist ein neuer Release Candidate und gegebenenfalls eine neue Patch-Version erforderlich.
