# Öffentliche Java-API und Kompatibilität

Die maschinenlesbare Liste steht in `config/sdk/public-api.json`, im
Release-ZIP mit konkreter Produktversion und SHA-256-Ledger zusätzlich in
`sdk-compatibility.json`. Alle Module benutzen dieselbe Produktversion; die
BOM `de.regelsuche:regelsuche-bom` richtet sie aus.

| Achse | Aktueller Vertrag |
|---|---|
| Java | 25 |
| Discovery-SPI | `DiscoveryApi.VERSION = 1` |
| Plugin-SPI | `apiVersion() = 1` |
| Core-Kompatibilität für Plugins | `1.0.0` |
| Produktversion | `release.properties`, im Release konkret im Manifest |
| Stabile SDK-Pakete | `de.regelsuche.api`, exakt `de.regelsuche.sdk.discovery` |
| Stabile Plugin-Klassen | die explizite Klassenliste in `public-api.json`, ausschließlich aus `regelsuche-plugin-api` |
| Experimentell | `de.regelsuche.sdk.discovery.python`, `de.regelsuche.sdk.discovery.cli` |

Die nach innen gerichteten Module `core`, `egraph`, `search`, `validation` und
`discovery` sind aufgelöste Laufzeitabhängigkeiten. Ihre gesamte öffentliche
Implementierung ist damit nicht automatisch als stabile SDK-API freigegeben.
Typen in öffentlichen SDK-Signaturen werden beim API-Diff mit aufgelöst; die
externen Consumer prüfen die konkret benutzten unteren Verträge.

`@StableApi` und `@IncubatingApi` sind im Bytecode dokumentierte
Lebenszykluskennzeichnungen. In einer unterstützten API-Revision sind binär
oder im Quelltext inkompatible Änderungen an stabilen Verträgen verboten.
Entfernung verlangt eine neue API-Revision und mindestens eine vollständige
Produkt-Minor-Version Vorlauf mit `@Deprecated` sowie einem Migrationsleitfaden.
Die Produktnummer ist unabhängig davon; ein Snapshot ist kein veröffentlichtes
Release und kein Nachweis menschlicher Bedienbarkeit.

## API-Diff und Migration von 0.4.0

`verifySdkApiCompatibility` verwendet
[japicmp](https://siom79.github.io/japicmp/CliTool.html) mit Fehlern bei Binär-
und Quellinkompatibilität. Referenz ist Release 0.4.0, unveränderlich gepinnt auf
`a5f17cfe7ce9a6bed71c4a56f80254f509eebc46`. Die alten öffentlichen Quellen und
benötigten Quellabhängigkeiten werden mit Java 25 kompiliert. Aktuelle
Regelsuche-JARs dürfen diesen alten Compiler-Klassenpfad nicht ersetzen.
Berichte liegen unter `build/reports/sdk-api-compatibility`.

Die bisherigen vollqualifizierten Plugin-Klassennamen bleiben erhalten. Bei
einem externen Projekt ersetzt `regelsuche-plugin-api` die Abhängigkeit auf
`app`; Anwendungsverdrahtung und Vertrauen verbleiben in `app`. Die bisherigen
Discovery-Fassade, Provider-Implementierungen und der vierteilige
`Registration`-Konstruktor bleiben quellkompatibel. Für Provenienz im Lauf
ersetzt `forRegistration(registration)` die Rekonstruktion einer losgelösten
Domaininstanz. Registrierte Evidence erhält reservierte `sdk.provider.*`-
Eigenschaften und damit einen anderen Evidence-Hash. Ihre mathematischen
Status- und Zertifikatsregeln sowie das generische Evidence-Schema bleiben gleich.

Provider-Provenienz bindet Identität, Version, angegebene Quellenreferenz,
implementierende Klasse und SHA-256 des beobachteten CodeSource-Artefakts.
Bei JARs umfasst der Hash alle JAR-Bytes. Bei Entwicklungsverzeichnissen umfasst
er sortierte relative Dateinamen und Bytes. Absolute Installationspfade und
Zeitstempel gehen nicht ein. Abhängigkeiten außerhalb dieses Artefakts und
vom Provider gelesene externe Daten sind keine automatisch attestierte Closure.
Vor Ausführung und Evidence-Erstellung werden die Artefaktbytes erneut verglichen; eine zwischenzeitliche Änderung wird abgewiesen. Der Hash ist weder Signatur noch Sandbox oder mathematische Freigabe.

## Noch ausstehende Abnahmen

Das neue SDK-ZIP muss erstmals durch einen erfolgreichen getaggten Release
öffentlich bereitgestellt werden. Die getrennte Veröffentlichung auf Maven
Central/GitHub Packages und eine öffentlich im Browser navigierbare
Javadoc-Website sind noch nicht eingerichtet; die versionierten HTML-Seiten
liegen im gehosteten Release-ZIP. Die [Human-DX-Erprobung](java-sdk-human-dx.md)
ist offen. Pareto-Planoptimierung ist weiterhin kein Vertrag des ersten
bestätigten Kandidaten liefernden Runners.
