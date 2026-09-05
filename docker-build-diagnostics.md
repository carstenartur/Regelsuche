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
`REGELSUCHE_DOCKER_EVENTS`. Sie bleibt damit in den bestehenden JUnit-XML-Dateien
und CI-Testlogs erhalten; es gibt weder einen zusätzlichen Workflow noch einen
separaten Veröffentlichungsweg.

Aufbewahrt werden höchstens die letzten 256 empfangenen Ereignisse. Die
Ereigniswartezeit beträgt höchstens fünf Sekunden; Clientinitialisierung und
Transportabschluss unterliegen weiterhin dem bestehenden Docker-Client.
Eine spät eintreffende Antwort wird auch nach Ablauf der Wartezeit geschlossen.
Die Ausgabe unterscheidet `WINDOW_COMPLETE`, `TIMEOUT`, `INTERRUPTED`,
API-Fehler und technische Nichtverfügbarkeit. Bei einem Interrupt bleibt das
Interruptflag erhalten. Zeilen und Felder sind begrenzt; zusätzliche
Ereignisausgaben enthalten nur Zeit, Typ, Aktion und Objekt-ID, keine
Containerumgebungen, beliebigen Attribute oder Quellimagenamen.

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
Ein echter negativer Docker-Build aus `FROM scratch` mit einer absichtlich
fehlenden COPY-Quelle prüft zusätzlich den vollständigen Fehler- und Abfragepfad.
Er benötigt keine herunterzuladende Fixture-Basis. Docker bleibt eine Pflicht der
bestehenden Container-Testschicht; dieser Test wird bei fehlender Infrastruktur
nicht stillschweigend übersprungen.

Der lokale Testbefehl und der vollständige `ciCheck` qualifizieren die Umsetzung.
Ein grüner Diagnosetest ist keine nachgewiesene Reparatur des ursprünglichen
sporadischen Zwischenimagefehlers.
