# Release-Notes- und Issue-Audit

Dieses Dokument ergänzt den operativen [Release-Betrieb](release-operations.md).
Es definiert die fachliche Grenze der Release Notes und die verpflichtende
Prüfung offener Issues vor jeder Veröffentlichung.

Die technische Veröffentlichung eines Tags und vollständiger Binärartefakte
ersetzt diese Prüfung nicht. Ebenso ist die Anzahl offener Issues kein Maß für
den noch nicht ausgelieferten Umfang: Ein offenes Sammel-Issue kann zahlreiche
abgeschlossene Teilpakete und zugleich noch unerfüllte Abschlusskriterien
enthalten.

## Maßgebliches Release-Intervall

Das Release-Intervall wird ausschließlich durch zwei semantische Git-Tags
bestimmt:

- `previousTag`: der unmittelbar vorhergehende veröffentlichte SemVer-Tag;
- `releaseTag`: der neu zu veröffentlichende SemVer-Tag.

Die Auswahl darf nicht davon abhängen, ob für ältere Tags zusätzlich ein
GitHub-Releaseobjekt existiert. Datumsfilter sind nur eine Plausibilitätsprüfung
und keine autoritative Grenze. Gibt es keinen eindeutigen vorherigen SemVer-Tag,
wird die automatische Erzeugung beendet und die Grenze muss ausdrücklich
geprüft und dokumentiert werden.

Alle PR-, Commit-, Issue- und Änderungslisten beziehen sich auf genau
`previousTag..releaseTag`.

## Verpflichtende Prüfung der Issues

Vor dem echten Release sind alle im Release-Intervall berührten Issues zu
prüfen. Dazu gehören mindestens Issues, die

- durch Closing Keywords eines PRs oder Commits referenziert werden;
- in PR-Titel, PR-Beschreibung, Commit-Nachrichten oder Review-Kommentaren als
  fachlicher Arbeitsgegenstand genannt werden;
- noch offen sind, obwohl im Intervall Teilimplementierungen für sie gemergt
  wurden;
- als Abhängigkeit, Blocker oder übergeordnetes Sammel-Issue den ausgelieferten
  Umfang beschreiben.

Für jedes betroffene Issue gilt genau eine der folgenden Entscheidungen.

### Vollständig erfüllt

Ein Issue darf nur geschlossen und im Abschnitt **Abgeschlossene Issues**
aufgeführt werden, wenn

- alle verbindlichen Akzeptanz- und Abschlusskriterien erfüllt sind;
- die erforderlichen Implementierungen, Tests, Dokumentation und
  Reproduktionsnachweise im Release-Intervall oder bereits zuvor vorhanden und
  weiterhin gültig sind;
- verbleibende Punkte keine versteckten Pflichtbestandteile des ursprünglichen
  Claims sind;
- ein Abschlusskommentar die maßgeblichen PRs, Artefakte und gegebenenfalls
  bewusst verworfenen Nicht-Ziele nennt;
- der GitHub-Schließungsgrund fachlich `completed` und nicht `duplicate` oder
  `not planned` ist.

### Teilweise erfüllt

Sind nur Teilpakete abgeschlossen, bleibt das Issue offen. Vor dem Release wird
sein Status aktualisiert oder durch einen klaren Audit-Kommentar ergänzt. Dieser
muss getrennt aufführen:

- bereits gemergte und im Release enthaltene Teilpakete;
- noch offene Akzeptanzkriterien;
- die stärkste durch den aktuellen Stand erlaubte Aussage;
- den Grund, weshalb das Issue nicht geschlossen wird.

Ein teilweise erfülltes Issue darf nicht im Abschnitt **Abgeschlossene
Issues** erscheinen. Bedeutende ausgelieferte Teilpakete können unter
**Teilfortschritte offener Sammel-Issues** genannt werden, müssen dort aber
unmissverständlich als Teilfortschritt bezeichnet sein.

### Nicht umgesetzt oder nicht berührt

Ein unverändert offenes Issue wird nicht allein wegen seiner Existenz in die
Release Notes aufgenommen. Es kann in den bekannten Einschränkungen erscheinen,
wenn es eine für Anwender wesentliche Grenze des Releases beschreibt.

## Aufbau der Release Notes

Die öffentliche Reihenfolge ist:

1. **Abgeschlossene Issues** — nur fachlich als `completed` geschlossene Issues,
   deren Abschluss dem Tag-Intervall zugeordnet ist.
2. **Teilfortschritte offener Sammel-Issues** — optional und ausdrücklich als
   unvollständig gekennzeichnet.
3. **Technische Änderungen** — PRs aus demselben Tag-Intervall, gruppiert nach
   Produkt, Forschung, Build/QA, Dokumentation und Wartung.
4. **Bekannte Einschränkungen und verbleibende Arbeit** — nur für den
   veröffentlichten Claim relevante Grenzen.
5. **Reproduktion und Artefakte** — Buildvertrag, Prüfsummen, Manifest und
   Archivnachweis.

Geschlossene Duplikate, ersetzte Konzepte und `not planned`-Entscheidungen sind
keine abgeschlossenen Produktmerkmale. Sie werden nur dann in einem getrennten
Abschnitt **Issue-Verwaltung** genannt, wenn die Entscheidung für Nutzer oder
Mitwirkende relevant ist.

## Automatisch erzeugte GitHub-Hinweise

GitHub-generierte Hinweise sind eine technische PR-Übersicht, nicht die
fachliche Autorität für abgeschlossene Issues.

Wird `gh release create --generate-notes` verwendet, muss der vorherige Tag
explizit übergeben werden:

```bash
gh release create "$RELEASE_TAG" \
  --generate-notes \
  --notes-start-tag "$PREVIOUS_TAG"
```

Das verhindert eine unbeabsichtigte Ausweitung auf ältere Repository-Historie.
Es ersetzt jedoch nicht die Issue-Prüfung, weil automatisch generierte Hinweise
primär Pull Requests und Labels auswerten und nicht entscheiden können, ob die
vollständigen Akzeptanzkriterien eines offenen Sammel-Issues erfüllt sind.

Die bevorzugte Veröffentlichung verwendet deshalb eine vorab erzeugte und
geprüfte Release-Notes-Datei. Automatisch erzeugte PR-Details dürfen in diese
Datei übernommen werden, nachdem ihre Taggrenze und Gruppierung kontrolliert
wurden.

## Audit-Nachweis vor der Veröffentlichung

Vor dem echten Release wird ein überprüfbarer Audit-Nachweis festgehalten. Er
enthält mindestens:

- vorherigen und neuen Tag einschließlich Commit-SHAs;
- alle im Intervall geschlossenen Issues mit Schließungsgrund;
- die Teilmenge der als **Abgeschlossene Issues** veröffentlichten Einträge;
- ausgeschlossene geschlossene Issues mit Begründung, etwa `duplicate`,
  `superseded` oder `not planned`;
- alle im Intervall berührten, weiterhin offenen Issues;
- für jedes dieser Issues den aktualisierten Status oder Audit-Kommentar;
- die technische PR-Liste aus exakt demselben Tag-Intervall;
- die vollständige, vor Veröffentlichung geprüfte Release-Notes-Fassung;
- Prüfdatum und geprüften Repository-Stand.

Der Trockenlauf darf Pakete und Metadaten erfolgreich prüfen, aber der echte
Release darf erst gestartet werden, wenn dieser Issue- und Release-Notes-Audit
abgeschlossen ist.

## Nachkontrolle

Nach der Veröffentlichung wird kontrolliert, dass

- die öffentliche Liste abgeschlossener Issues dem geprüften Audit entspricht;
- kein weiterhin offenes Sammel-Issue als vollständig erledigt dargestellt ist;
- kein PR außerhalb des Tag-Intervalls in der technischen Änderungsliste steht;
- GitHub Release und Zenodo dieselbe geprüfte Release-Notes-Fassung oder eine
  inhaltlich äquivalente, claim-konforme Zusammenfassung verwenden;
- nachträglich erkannte Statusabweichungen in den betroffenen Issues und im
  Release-Prozess dokumentiert werden.

## Erkenntnis aus `v0.2.0`

Bei `v0.2.0` wurde `--generate-notes` ohne expliziten Start-Tag verwendet. Die
veröffentlichten Hinweise reichten dadurch bis zu den ersten Pull Requests des
Repositories zurück und bildeten nicht das beabsichtigte Intervall
`v0.1.4..v0.2.0` ab.

Die nachträgliche Prüfung zeigte außerdem, dass mehrere weiterhin offene Issues
bereits weitere gemergte Teilpakete enthielten, deren Status noch nicht
aktualisiert war. Daraus folgen zwei voneinander unabhängige Pflichten:

1. Die technische PR-Liste muss strikt auf das Tag-Intervall begrenzt werden.
2. Offene, im Intervall berührte Issues müssen vor der Veröffentlichung auf
   vollständige oder partielle Erfüllung geprüft werden.

Beide Prüfungen sind für künftige Releases verbindlich. Ein grüner Build und ein
korrekt erzeugtes Zenodo-Archiv reichen allein nicht aus, um korrekte Release
Notes zu belegen.
