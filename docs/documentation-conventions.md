# Dokumentationskonvention: GUI zuerst, Swagger für REST

Regelsuche trennt Benutzerdokumentation und technische Schnittstellenreferenz bewusst.

## Verbindliche Quellen

- **Swagger/OpenAPI ist die einzige verbindliche REST-Referenz.** Dort werden HTTP-Methoden, Pfade, Parameter, Request- und Response-Schemata, Statuscodes und technische Beispiele gepflegt.
- **Die Markdown-Dokumentation erklärt die grafische Benutzung.** Sie beschreibt, wo eine Funktion in der Web-Workbench zu finden ist, welche Eingaben erforderlich sind, welche Aktion ausgelöst wird und welches Ergebnis in der Oberfläche sichtbar wird.
- **Architektur- und Entwicklungsdokumente erklären Grenzen und Zusammenspiel.** Sie dürfen API-Bereiche oder eine Swagger-Operation nennen, sollen den HTTP-Vertrag aber nicht erneut als Tabelle oder Beispielblock abbilden.

Damit wird vermieden, dass sich REST-Beschreibungen in README, Handbüchern und Feature-Dokumenten unterschiedlich weiterentwickeln.

## Aufbau einer GUI-Funktionsbeschreibung

Eine nutzerbezogene Beschreibung soll, soweit für die Funktion relevant, folgende Punkte enthalten:

1. **Einstiegspunkt:** Tab, Panel, Schaltfläche oder Menü in der Web-Workbench.
2. **Voraussetzungen:** beispielsweise eine bereits ausgeführte Suche oder ein ausgewählter Pfad.
3. **Eingaben:** ausschließlich in den Begriffen und Bezeichnungen der Oberfläche.
4. **Aktion:** welche Schaltfläche oder Interaktion die Funktion ausführt.
5. **Sichtbares Ergebnis:** Darstellung, Status, Download oder persistierte Änderung.
6. **Leere-, Lade- und Fehlerzustände:** was Nutzerinnen und Nutzer sehen und wie sie weiterarbeiten können.
7. **Technische Zuordnung:** optional der Swagger-Tag oder die `operationId`, wenn dies beim Testen oder bei der Weiterentwicklung hilft.

## Was nicht in Nutzerseiten gehört

Nutzerseiten enthalten keine eigenen Kataloge mit:

- HTTP-Methoden und Pfaden,
- JSON-Payloads oder Response-Beispielen,
- Statuscode-Tabellen,
- `curl`- oder rohen HTTP-Beispielen,
- Listen von Endpunkten als Ersatz für eine Funktionsbeschreibung.

Für diese Angaben wird auf Swagger/OpenAPI verwiesen. Ein Integrationsleitfaden darf ein minimales Aufrufbeispiel enthalten, wenn genau die programmatische Integration sein Thema ist; auch dann bleibt Swagger/OpenAPI die maßgebliche Vertragsquelle.

## Zuordnung von GUI und API

Eine GUI-Funktion kann intern mehrere REST-Operationen verwenden. Die Dokumentation beschreibt deshalb den fachlichen Bedienvorgang und nicht die Reihenfolge einzelner Requests. Eine knappe Zuordnung wie „Swagger-Bereich: Search, Paths und Exports“ ist ausreichend.

Umgekehrt soll für jede öffentlich nutzbare REST-Funktion geprüft werden, ob eine grafische Bedienmöglichkeit existiert. Fehlt sie, wird dies nicht durch zusätzliche REST-Prosa kaschiert, sondern als fehlende GUI-Funktion dokumentiert und als eigener Produktpunkt behandelt.

## Review-Regel

Bei Änderungen an REST-Operationen werden Swagger/OpenAPI und die zugehörigen Tests aktualisiert. Bei Änderungen am Nutzerfluss werden die GUI-Dokumentation, Screenshots und Browser-E2E-Tests aktualisiert. Änderungen an einem HTTP-Pfad allein rechtfertigen keine Anpassung in einer Nutzerseite, solange der sichtbare Bedienvorgang unverändert bleibt.
