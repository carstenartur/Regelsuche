# Getting Started

Diese Anleitung führt von einem leeren Checkout zur ersten nachvollziehbaren
Suche. Für die Standarddemo sind weder PostgreSQL noch Neo4j oder ein externer
Solver erforderlich.

## Voraussetzungen

Wähle einen der beiden unterstützten Einstiege:

- **Docker:** aktueller Docker-Daemon; keine lokale Java-Installation nötig.
- **Lokaler Build:** JDK 21; der Gradle Wrapper wird aus dem Repository benutzt.

Für `fullCheck`, Datenbanktests und vollständige Forschungsreproduktion werden
zusätzlich Docker, Python-`venv` und die jeweils dokumentierten externen
Werkzeuge benötigt. Siehe [Testing](testing.md).

## 1. Standarddemo starten

```bash
docker build -t regelsuche .
docker run --rm -p 127.0.0.1:8080:8080 regelsuche
```

Öffne anschließend `http://127.0.0.1:8080/`.

Die Bindung an `127.0.0.1` ist absichtlich lokal. Die Standardinstanz verwendet
HTTP ohne Anmeldung und ist nicht für eine öffentliche Bereitstellung gedacht.

### Alternative ohne Docker

```bash
./gradlew run
```

Der Server verwendet ebenfalls `http://127.0.0.1:8080/` und legt lokale Daten
im konfigurierten Dateispeicher ab.

## 2. Eine geführte Suche ausführen

1. Wähle in der Workbench eine Demo, zum Beispiel **Binomische Formel** oder
   **Bruchkürzung**.
2. Starte die Suche. Die Oberfläche blendet anschließend die relevanten
   Ergebnisbereiche ein.
3. Öffne **Graph**, um Zustände und Suchkanten zu sehen.
4. Öffne **Replay**, um den ausgewählten Rechenweg Schritt für Schritt zu
   verfolgen.
5. Öffne **AST-Regelradar**, wähle einen Baumknoten und prüfe die dort konkret
   anwendbaren Regeln samt Bindungen, Annahmen und Vorschau.
6. Lade unter **Exporte** ein Bundle für Dokumentation oder maschinelle
   Weiterverarbeitung herunter.

Die [Demo Gallery](demo-gallery.md) zeigt die empfohlenen Demos mit
browsertestgenerierten Screenshots. [User Workflows](user-workflows.md)
beschreibt vollständige Abläufe für unterschiedliche Rollen.

## 3. Einen eigenen Ausdruck untersuchen

Im Bereich **Workbench** kannst du einen Ausdruck, ein optionales Ziel, ein
Suchprofil und Regelbereiche wählen.

Für einen ersten eigenen Lauf empfiehlt sich:

- ein kleiner algebraischer Ausdruck;
- ein sichtbares Ziel, wenn du einen Rechenweg zu einer bekannten Form suchst;
- das Standardprofil `core`;
- ein begrenztes Suchbudget.

Targetfreie Suche ist ein anderer Experimenttyp: Sie erhält keinen
Zielausdruck und wählt einen Zustand nach einer eingefrorenen Policy. Ein
besuchter, aber nicht ausgewählter Ausdruck zählt dabei nicht als Ergebnis.

## 4. Ergebnisse richtig lesen

### Suchgraph

Ein Knoten ist ein vollständiger Ausdruckszustand. Eine Kante ist eine konkrete
Regelanwendung an einer bestimmten AST-Position. Der Graph zeigt den erkundeten
Raum; er ist nicht mit dem AST innerhalb eines einzelnen Zustands identisch.

### Replay

Replay stellt einen retained Pfad erneut dar. Es zeigt Regelherkunft,
Änderungen und Annahmen. Ein Replay ist nachvollziehbare Ableitungsevidence,
aber nicht automatisch ein formaler Beweis.

### Proof-Status

Regelsuche kann Proof-Obligationen und Solver-Artefakte erzeugen. Ein Status wie
`FORMALLY_PROVED` darf nur erscheinen, wenn das konfigurierte Backend die
Obligation tatsächlich bestätigt hat. Ein Test- oder Mock-Backend ist kein
fachlicher Beweis.

### Discovery- und Novelty-Status

`NOVEL_WITHIN_PROJECT` bezeichnet Neuheit gegenüber dem gebundenen
Projektinventar. Daraus folgt keine externe mathematische Neuheit. Die
Statusbegriffe sind im [Glossar](glossary.md) definiert.

## 5. Technische REST-Referenz

Die Anwendung liefert ihre REST-Dokumentation selbst aus:

- Swagger UI: `http://127.0.0.1:8080/static/openapi/index.html`
- OpenAPI 3.1 JSON: `http://127.0.0.1:8080/static/openapi/openapi.json`

Swagger/OpenAPI ist die maßgebliche Quelle für Methoden, Pfade, Payloads,
Responses und Statuscodes. Markdown-Handbücher dokumentieren den sichtbaren
Produktfluss und duplizieren den HTTP-Vertrag nicht.

## Optional: Full Mode mit PostgreSQL

```bash
docker compose up --build
```

Der Full Mode ergänzt die Anwendung um PostgreSQL, Hibernate ORM, Hibernate
Search und persistente Volumes. Anwendung und Datenbank werden standardmäßig
nur lokal gebunden.

Die in `docker-compose.yml` vorhandenen Fallback-Zugangsdaten dienen
ausschließlich der lokalen Demo. Vor jeder anderen Verwendung müssen eigene
Credentials, authentifiziertes TLS, Netzwerkgrenzen, Backup und Betriebskonzept
festgelegt werden.

Neo4j bleibt ein optionales Profil für Graph-Provenienz:

```bash
docker compose --profile neo4j up --build
```

Details: [Persistenz](persistence.md) und
[Storage Architecture](storage-architecture.md).

## Optional: Proof-Image

Das Proof-Image enthält Z3 und cvc5; Lean kann beim Build optional ergänzt
werden:

```bash
docker build -f Dockerfile.proof -t regelsuche-proof .
```

Konfiguration, Job-Lebenszyklus und Artefakte beschreibt
[Proof Workbench](proof-workbench.md).

## Lokale Verifikation

Für einen gewöhnlichen Entwicklungscheck:

```bash
./gradlew check
```

Für den vollständigen, auch in CI verwendeten Lebenszyklus:

```bash
./gradlew ciCheck
```

`ciCheck` ist absichtlich umfangreich. Für schnelle lokale Zyklen stehen
modul- und schichtspezifische Tasks zur Verfügung; siehe
[Developer Guide](developer-guide.md) und [Testing](testing.md).

## Fehlerdiagnose

### Port 8080 ist belegt

Verwende einen anderen lokalen Host-Port:

```bash
docker run --rm -p 127.0.0.1:8081:8080 regelsuche
```

Öffne anschließend `http://127.0.0.1:8081/`.

### Browser-E2E kann Chromium nicht starten

Installiere auf Linux einmalig die von Playwright benötigten Hostbibliotheken:

```bash
./gradlew :app:installPlaywrightHostDependencies
```

### Docker-/Datenbanktests werden übersprungen oder schlagen fehl

Prüfe, ob der Docker-Daemon für den aktuellen Benutzer erreichbar ist. Die
Tests verwenden Testcontainers und zufällige Host-Ports; fest konfigurierte
GitHub-Service-Container sind nicht erforderlich.

### Ein Evidence-Verifier blockiert

Nicht umgehen. Prüfe den ersten fachlichen Fehler im Log sowie die erzeugten
Artefakte unter den jeweiligen `build/`-Verzeichnissen. Ein Blocker soll
fehlende, inkonsistente oder nicht reproduzierbare Evidence sichtbar machen.

## Nächste Schritte

- [Web-Workbench](web-workbench.md) — Bereiche und sichtbare Ergebnisse.
- [Web-Workbench-Benutzerhandbuch](web-ui-user-guide.md) — alle aktuellen
  Bedienelemente und Zustände.
- [User Workflows](user-workflows.md) — geführte End-to-End-Abläufe.
- [AST-Regelradar](ast-rule-radar.md) — lokale Regelanwendungen verstehen.
- [Discovery- und Forschungsstand](discovery-status.md) — aktuelle Ergebnisse
  und Claim-Grenzen.
- [Architektur](architecture.md) — Module, Flüsse und Trust-Grenzen.
- [Erweiterungssystem](extension-system.md) — Regeln, Packs und Plugins.
