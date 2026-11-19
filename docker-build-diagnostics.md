# Docker-Buildfehler diagnostizieren

Diese Diagnose gehört zur Container-Testschicht aus [Testing](testing.md).
Sie ergänzt den in #514 dokumentierten sporadischen Fehler beim Kopieren aus
Docker-Build-Zwischenstufen. Sie ist noch kein Fix für dessen ungeklärte Ursache.

## Verhalten

Die gemeinsamen Testimages in `RegelsucheDockerImages` bleiben lazy und werden
weiterhin durch `ImageFromDockerfile` gebaut. Erfolgreiche Builds starten keine
zusätzliche Docker-Abfrage. Nach einer Build-Exception liest der Beobachter ein
zeitlich begrenztes Fenster von Image- und Containerereignissen über den bereits
verwendeten Docker-Java-Client. Es wird kein Docker-CLI-Prozess benötigt.

Die Ausgabe erfolgt im Test-Standardfehlerkanal mit den Markern
`REGELSUCHE_DOCKER_BUILD_FAILURE`, `REGELSUCHE_DOCKER_EVENT` und
`REGELSUCHE_DOCKER_EVENTS`. Der Failure-Marker enthält zusätzlich eine
auf **sechs Ursachenklassen** begrenzte `causeTypes`-Kette und einen
`failureHint` aus einer festen technischen Kategorie (etwa
`CACHE_OR_IMAGE_REFERENCE`, `BUILD_INPUT_REFERENCE`, `DISK_SPACE`,
`REGISTRY_RATE_LIMIT`, `TIMEOUT` oder `UNCLASSIFIED`). Die Kategorie ist
eine **Heuristik**, kein Nachweis der eigentlichen Ursache. Insbesondere
werden keine frei formulierten Exception-Meldungen ausgegeben: Sie könnten
private Registry-Adressen, Build-Argumente oder Zugangsdaten enthalten. Sie bleibt damit in den bestehenden JUnit-XML-Dateien
und CI-Testlogs erhalten; es gibt weder einen zusätzlichen Workflow noch einen
separaten Veröffentlichungsweg.

Aufbewahrt werden höchstens die letzten 256 empfangenen Ereignisse. Die
Ereigniswartezeit beträgt höchstens fünf Sekunden; Clientinitialisierung und
Transportabschluss unterliegen weiterhin dem bestehenden Docker-Client.
Eine spät eintreffende Antwort wird auch nach Ablauf der Wartezeit geschlossen.
Die Ausgabe unterscheidet `WINDOW_COMPLETE`, `TIMEOUT`, `INTERRUPTED`,
API-Fehler und technische Nichtverfügbarkeit. Bei einem Interrupt bleibt das
Interruptflag erhalten. Zeilen und Felder sind begrenzt; zusätzliche
Ereignisausgaben enthalten Zeit, Typ, Aktion und Objekt-ID. Nur bei
Containerereignissen mit Aktion `die` wird ein streng numerischer
`exitCode` im Bereich 0–255 aus den Docker-Actor-Attributen ergänzt.
Beliebige andere Attribute, Containerumgebungen, Namen, Labels und
Quellimagenamen bleiben ausgeschlossen. Ein Exitcode 137 ist insbesondere
**kein eigenständiger Nachweis für einen OOM-Kill**.

Die ursprüngliche Build-Exception bleibt maßgeblich. Die Diagnose fügt keinen
Buildversuch hinzu, schaltet keine Bereinigung ab und ändert weder Dockerfiles,
Testauswahl, mathematische Budgets noch Qualitätsgrenzen. Sie verändert nicht die
vorhandene Behandlung fehlgeschlagener Auflösungen durch Testcontainers.

## Interpretation

Das Fenster verwendet die Client-Uhr, auf volle Sekunden nach außen gerundet.
`WINDOW_COMPLETE` bedeutet nur, dass die Ereignisabfrage regulär endete. Docker
bewahrt selbst nur eine begrenzte Ereignishistorie auf. Fehlende Ereignisse
beweisen daher weder das Ausbleiben einer Löschung noch die Fehlerfreiheit eines
Cleanup-Prozesses. Auch eine gefundene Löschung beweist noch nicht, welcher
Prozess sie veranlasste. Objekt-IDs, Zeitfenster und die unveränderte ursprüngliche
Fehlermeldung dienen gemeinsam der weiteren Ursachenanalyse.

Die Ausgabe ist technische Diagnose, keine kanonische mathematische Evidence.

## Prüfung

```bash
./gradlew :app:dockerE2eTest \
  --tests de.regelsuche.dockere2e.DockerBuildDiagnosticsTest
```

Die Testklasse prüft Memoisierung, ursprüngliche Exception, fehlende Zusatz-Retries,
begrenzte unveränderliche Ereigniszeilen, API-Fehler, Streamabschluss und Interrupts.
Sie prüft außerdem die begrenzte Ursachenklassenkette, erlaubte Signal-Kategorien
das Nichtausgeben privater Exception-Payloads sowie ausschließlich gültige
Exitcodes bei `die`-Ereignissen mit fehlenden, manipulierten oder
irrelevanten Actor-Attributen.
Ein echter negativer Docker-Build aus `FROM scratch` mit einer absichtlich
fehlenden COPY-Quelle prüft zusätzlich den vollständigen Fehler- und Abfragepfad.
Er benötigt keine herunterzuladende Fixture-Basis. Docker bleibt eine Pflicht der
bestehenden Container-Testschicht; dieser Test wird bei fehlender Infrastruktur
nicht stillschweigend übersprungen.

Der lokale Testbefehl und der vollständige `ciCheck` qualifizieren die Umsetzung.
Ein grüner Diagnosetest ist keine nachgewiesene Reparatur des ursprünglichen
sporadischen Zwischenimagefehlers.
