# Release-Notes- und Issue-Audit

Dieses Dokument ergänzt den operativen [Release-Betrieb](release-operations.md).
Ein grüner Build und ein vollständiges Archiv ersetzen nicht die fachliche
Prüfung, welche Issues wirklich abgeschlossen sind.

## Autoritatives Release-Intervall

Jede Auswertung verwendet genau zwei semantische Git-Tags:

- `previousTag`: unmittelbar vorhergehender veröffentlichter SemVer-Tag;
- `releaseTag`: neu zu veröffentlichender SemVer-Tag.

Das Intervall ist `previousTag..releaseTag`. Es hängt weder von vorhandenen
GitHub-Releaseobjekten noch von Datumsfiltern ab. Ist der vorherige Tag nicht
eindeutig bestimmbar, wird die automatische Veröffentlichung abgebrochen.

## Verpflichtender Issue-Audit

Vor dem echten Release werden alle im Intervall berührten Issues geprüft. Dazu
gehören Closing-Referenzen sowie in PRs, Commits oder Reviews genannte Issues
und übergeordnete Sammel-Issues der ausgelieferten Arbeit.

### Vollständig erfüllt

Ein Issue darf nur als abgeschlossen veröffentlicht werden, wenn

- alle verbindlichen Akzeptanz- und Abschlusskriterien erfüllt sind;
- Implementierung, Tests, Dokumentation und erforderliche Evidenz vorhanden
  und weiterhin gültig sind;
- keine Pflichtarbeit hinter einem offenen Folgepunkt verborgen bleibt;
- ein Abschlusskommentar die maßgeblichen PRs und Nachweise nennt;
- GitHub es mit dem Schließungsgrund `completed` führt.

Nur solche Issues gehören in **Abgeschlossene Issues**.

### Teilweise erfüllt

Ein teilweise erledigtes Sammel-Issue bleibt offen. Vor dem Release erhält es
einen Status- oder Audit-Kommentar mit

- den ausgelieferten Teilpaketen;
- den weiterhin offenen Akzeptanzkriterien;
- der stärksten derzeit zulässigen Aussage;
- dem Grund, weshalb es nicht geschlossen wird.

Es darf optional unter **Teilfortschritte offener Sammel-Issues** erscheinen,
aber niemals als vollständig abgeschlossen.

### Nicht abgeschlossen

Ein mit `not planned` geschlossenes Issue ist kein ausgeliefertes Merkmal.
Ebenso sind als Duplikat oder ersetzt klassifizierte Issues nur
Verwaltungsentscheidungen; `duplicate` und `superseded` sind keine eigenen
GitHub-Schließungsgründe. Solche Einträge gehören höchstens in einen getrennten
Abschnitt **Issue-Verwaltung**.

## Aufbau der Release Notes

Die öffentliche Reihenfolge ist:

1. **Abgeschlossene Issues** — ausschließlich `completed` im geprüften
   Release-Intervall;
2. **Teilfortschritte offener Sammel-Issues** — optional und klar als
   unvollständig markiert;
3. **Technische Änderungen** — PRs aus demselben Tag-Intervall;
4. **Bekannte Einschränkungen** — für den Release-Claim relevante Grenzen;
5. **Reproduktion und Artefakte** — Buildvertrag, Manifest und Prüfsummen.

GitHub-generierte Hinweise sind nur eine technische PR-Übersicht. Bei ihrer
Verwendung muss die Grenze ausdrücklich angegeben werden:

```bash
gh release create "$RELEASE_TAG" \
  --generate-notes \
  --notes-start-tag "$PREVIOUS_TAG"
```

Das ersetzt den Issue-Audit nicht. Bevorzugt wird eine vorab erzeugte und
geprüfte Release-Notes-Datei.

## Audit-Nachweis

Vor der Veröffentlichung werden mindestens festgehalten:

- beide Tags und Commit-SHAs;
- alle im Intervall geschlossenen Issues samt Schließungsgrund;
- die daraus als abgeschlossen veröffentlichten Issues;
- ausgeschlossene `not planned`-, Duplikat- oder Ersetzt-Klassifikationen;
- alle berührten, weiterhin offenen Issues mit aktualisiertem Status;
- die PR-Liste aus demselben Intervall;
- die geprüfte Release-Notes-Fassung und der geprüfte Repository-Stand.

Der echte Release darf erst nach diesem Nachweis starten. Nach GitHub- und
Zenodo-Publikation wird kontrolliert, dass keine offenen Sammel-Issues als
vollständig erledigt und keine PRs außerhalb des Intervalls dargestellt werden.

## Erkenntnis aus `v0.2.0`

Für `v0.2.0` wurde `--generate-notes` ohne expliziten Start-Tag verwendet. Die
PR-Liste reichte dadurch über das beabsichtigte Intervall
`v0.1.4..v0.2.0` hinaus. Die anschließende Prüfung zeigte außerdem mehrere
offene Issues mit bereits gemergten, aber nicht aktuell dokumentierten
Teilpaketen. Künftige Releases benötigen deshalb sowohl die technische
Taggrenze als auch den fachlichen Issue-Audit.