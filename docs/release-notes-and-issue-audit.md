# Release-Notes- und Issue-Audit

Dieses Dokument ergänzt den [Release-Betrieb](release-operations.md). Ein grüner
Build ersetzt nicht die fachliche Prüfung, welche Issues wirklich abgeschlossen
sind.

## Release-Intervall

Jeder Release verwendet das eindeutige semantische Intervall
`previousTag..releaseTag`. `previousTag` ist der unmittelbar vorhergehende
veröffentlichte SemVer-Tag. Ist er nicht eindeutig bestimmbar, wird die
automatische Veröffentlichung abgebrochen.

## Prüfung vor dem Release

Vor der Veröffentlichung werden alle Issues geprüft, die im Intervall durch
Closing-Referenzen oder Erwähnungen in PRs, Commits und Reviews berührt wurden.
Übergeordnete Sammel-Issues der ausgelieferten Arbeit gehören ebenfalls dazu.

Ein Issue erscheint nur dann unter **Abgeschlossene Issues**, wenn

- alle verbindlichen Akzeptanz- und Abschlusskriterien erfüllt sind;
- Implementierung, Tests, Dokumentation und erforderliche Evidenz vorliegen;
- ein Abschlusskommentar die maßgeblichen PRs und Nachweise nennt;
- der GitHub-Schließungsgrund `completed` lautet.

Teilweise erfüllte Sammel-Issues bleiben offen. Ein Audit-Kommentar nennt die
ausgelieferten Teilpakete, die verbleibenden Kriterien und die derzeit
zulässige Aussage. Sie dürfen nur unter **Teilfortschritte offener Issues**
erscheinen.

`not planned`, Duplikat- und Ersetzt-Klassifikationen sind keine ausgelieferten
Merkmale. Sie gehören höchstens in einen getrennten Verwaltungsabschnitt.

## Release Notes

Die fachlichen Abschnitte lauten:

1. abgeschlossene Issues;
2. optionale Teilfortschritte offener Issues;
3. technische PR-Änderungen aus demselben Tag-Intervall;
4. bekannte Einschränkungen;
5. Reproduktion und Artefakte.

Automatisch erzeugte GitHub-Hinweise sind nur die technische PR-Übersicht und
müssen mindestens dieselbe Taggrenze verwenden:

```bash
gh release create "$RELEASE_TAG" \
  --generate-notes \
  --notes-start-tag "$PREVIOUS_TAG"
```

Sie ersetzen den Issue-Audit nicht.

## Audit-Nachweis

Vor dem echten Release werden festgehalten:

- beide Tags und Commit-SHAs;
- die im Intervall als `completed` geschlossenen Issues;
- ausgeschlossene Verwaltungsentscheidungen;
- berührte, weiterhin offene Issues samt aktualisiertem Status;
- die PR-Liste aus demselben Intervall;
- die geprüfte Release-Notes-Fassung und der geprüfte Repository-Stand.

Nach GitHub- und Zenodo-Publikation wird kontrolliert, dass keine offenen
Sammel-Issues als vollständig erledigt und keine PRs außerhalb des Intervalls
dargestellt werden.