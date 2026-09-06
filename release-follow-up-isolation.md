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

Die vorhandene Versionsentscheidung bleibt erhalten. Nur die passende frühere
SNAPSHOT-Version wird auf die angeforderte nächste Version geändert. Steht
`main` bereits auf der nächsten oder einer anderen Entwicklungslinie, erzeugt
der Schritt keine Änderung. Der vorhandene Metadatenhelfer sowie die explizite
`add-paths`-Liste des Folge-PRs bleiben die Eigentümer des Versionsdiffs.

Das Build-Verzeichnis wird für diesen Übergang weder gelöscht noch umbenannt,
rekursiv umberechtigt oder privilegiert verändert. Dadurch können seine Dateien
und Protokolle im ursprünglichen Workspace erhalten bleiben. Diese Änderung
macht Docker-Ausgaben nicht allgemein benutzereigen und verlängert auch nicht
ihre Aufbewahrung über das Ende eines gehosteten Runner-Jobs hinaus.

## Reproduzierbare Regression

Die bestehenden Tests in `.github/scripts/test_release_metadata.py` enthalten
zusätzlich die folgenden Verträge:

- Checkout-Revision, `main`-Ref, Arbeitsverzeichnisse und PR-Pfad passen zusammen;
  Trockenlauf- und verwaltete-Release-Bedingungen sowie die Dateipositivliste
  bleiben erhalten. Sechs absichtliche Pfad-/Guard-Mutationen müssen scheitern.
- Ein lokales Git-Repository mit eigenem Bare-Remote modelliert das veröffentlichte
  Quellrepository und den separaten Checkout. Die tatsächliche Shell des
  Metadatenschritts wird aus dem Workflow gelesen und mit dem unveränderten
  Metadatenhelfer ausgeführt. Es gibt keinen GitHub-Aufruf und keinen Push.
- Ein ignoriertes Evidence-Verzeichnis mit POSIX-Modus `0555` bleibt einschließlich
  Inhalt und Rechten erhalten. Unter einem unprivilegierten Benutzer wird auch
  der tatsächliche fehlgeschlagene Löschversuch geprüft. Root kann POSIX-DAC
  umgehen; dort werden weiterhin Erhaltung und Berechtigungen geprüft, aber
  kein beobachtetes `Permission denied` behauptet.
- Der Versionsdiff umfasst ausschließlich die Metadatendateien und deklarierten
  Fixture-POMs. Release-Commit, Tag und sämtliche ursprünglichen versionierten
  Dateien bleiben unverändert. Bereits weitergeschaltetes `main`, eine andere
  Entwicklungslinie, schmutzige Zielcheckouts und fehlende Git-Wurzeln sind
  eigene Prüffälle.

Aus einem normalen Checkout mit Python 3, Git und Bash:

```bash
python3 -B -m unittest discover -s .github/scripts -p test_release_metadata.py -v
./gradlew --no-daemon verifyReleaseMetadata
```

Der bereits vorhandene Task `verifyReleaseMetadata` führt diese Tests ohne
wiederverwendetes Erfolgsartefakt als Bestandteil von `check` aus. Es gibt keinen
zusätzlichen Workflow und keine neue Bibliotheksabhängigkeit. Der lokale
Git-Test prüft den Checkout-Vertrag und die tatsächliche Metadaten-Shell, nicht
die Implementierung des gehosteten Checkout- oder PR-Actions-Dienstes. Deren
Zusammenspiel wird beim nächsten regulären verwalteten Release erneut geprüft;
ein alter Release wird dafür nicht neu veröffentlicht.
