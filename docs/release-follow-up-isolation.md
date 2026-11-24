# Isolierte Nachbereitung eines Releases

Diese Seite ergänzt den [Release-Betrieb](release-operations.md). Sie beschreibt
nur den Übergang von einer bereits geprüften Veröffentlichung zum Pull Request
für die nächste Entwicklungsversion. Produktbuild, Archivprüfung, Tag,
Maintenance-Branch und öffentliche Release-Dateien bleiben davon getrennt.

## Befund bei 0.4.0

Release-Lauf [#30](https://github.com/carstenartur/Regelsuche/actions/runs/33980729998)
hatte im ersten Versuch bereits alle Prüfungen, die Paketierung, Veröffentlichung
und öffentliche Nachkontrolle erfolgreich abgeschlossen. Erst die anschließende
Nachbereitung scheiterte: `git checkout` und `git reset` funktionierten,
`git clean -fdx` dagegen konnte verschiedene von Docker erzeugte, ignorierte
Ausgabedateien unter `build/` wegen fehlender Rechte nicht entfernen.

Der dokumentierte Fehler ist `Permission denied`; die Eigentümer der konkreten
Dateien wurden nicht zusätzlich mit `stat` vermessen. Root-Eigentümerschaft ist
deshalb keine eigenständig belegte Diagnose. Der zweite Versuch auf einem
frischen Runner verwendete den vorhandenen Wiederherstellungspfad und erzeugte
den fehlenden Versionswechsel-PR ohne Neubau, neue Tags oder erneuten Upload.
Der Fehler und seine Nacharbeit werden in [#514](https://github.com/carstenartur/Regelsuche/issues/514#issuecomment-5554741286)
verfolgt. Ein erfolgreicher Wiederholungslauf repariert nicht die ursprüngliche
Abhängigkeit von einem löschbaren Build-Verzeichnis.

## Getrennte Arbeitsverzeichnisse

Der Schritt `Restore authoritative main for follow-up` verwendet jetzt dieselbe
festgelegte `actions/checkout`-Revision wie der erste Checkout, aber mit
`ref: main` und dem eigenen Pfad `.release-follow-up`. Das ist ein separates
Git-Repository im Arbeitsbereich, kein Reset oder Clean des Produktcheckouts.
Der zusätzliche Checkout wird nur nach einer erfolgreichen Veröffentlichung
beziehungsweise Nachkontrolle im verwalteten Nicht-Trockenlauf erreicht.

Sowohl `Prepare next development metadata` als auch
`Open next development metadata PR` arbeiten ausdrücklich in diesem separaten
Repository. Der Metadatenschritt verlangt vor dem ersten Schreibzugriff eine
eigene `.git`-Directory, den passenden Git-Wurzelpfad und einen sauberen Status
auch bezüglich neuer Dateien. Ein versehentlich fehlender Checkout darf nicht
über Gits Suche in Elternverzeichnissen zum Produktrepository zurückfallen.

Nur die zum Release gehörende frühere SNAPSHOT-Version wird auf die
angeforderte nächste Version geändert. Bei einer anderen Entwicklungslinie
oder einem bereits erfolgten Versionswechsel meldet der Maven-Schritt
`required=false`. Bei einer Änderung prüft `ReleaseMetadataTransition` die
POMs aller deklarierten Module und Profile sowie die sechs Metadatendateien
gemeinsam. Die explizite `add-paths`-Liste begrenzt den Folge-PR.

Das Build-Verzeichnis wird für diesen Übergang weder gelöscht noch umbenannt,
rekursiv umberechtigt oder privilegiert verändert. Dadurch können seine Dateien
und Protokolle im ursprünglichen Workspace erhalten bleiben. Diese Änderung
macht Docker-Ausgaben nicht allgemein benutzereigen und verlängert auch nicht
ihre Aufbewahrung über das Ende eines gehosteten Runner-Jobs hinaus.

## Reproduzierbare Regression

Die Maven-Tests prüfen die tatsächliche Versionsumstellung in einem
lokalen Klon mit eigenem Bare-Remote. Sie vergleichen Originaldateien,
Release-Commit, Tag, Zugriffsrechte und geschützte Evidenz mit dem
Ausgangszustand. Sie prüfen die unveränderte Entwicklungslinie, den
bereits erfolgten Wechsel, fremde Entwicklungslinien, schmutzige Checkouts
und den fehlenden eigenen Repository-Wurzelpfad. Mutationen im Workflow
prüfen die Bindung an Checkout, Bedingungen, Maven-Aufruf und Dateiliste.

```sh
mvn -pl maven-build-contract -am   -Dtest=MavenReleaseMetadataAlignmentTest,MavenReleaseMetadataTransitionTest,MavenReleaseFollowUpTest   -Dsurefire.failIfNoSpecifiedTests=false test
```

GitHub Actions übernimmt den Checkout und das Öffnen des Folge-PRs beim
regulären verwalteten Release. Die lokalen Regressionstests verwenden
ausschließlich temporäre Repositories.
