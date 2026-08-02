# Persistenz und Full Mode

Regelsuche trennt flüchtigen Suchzustand, relationale Metadaten,
Volltextsuche, große Trace-Artefakte und optionale Graph-Provenienz. Keine
einzelne Datenbank übernimmt alle Rollen.

## Speicherrollen

| Speicher | Verantwortung | Lebensdauer |
| --- | --- | --- |
| RAM, E-Graph und Transposition Table | aktiver Suchzustand und schnelle Deduplikation | nur während des Laufs |
| kompakter `SearchTraceStore` und Graphartefakte | große Traces, Replay und Reports | dateibasiert oder exportiert |
| PostgreSQL mit Hibernate ORM | Experimente, Läufe, Hypothesen, Gegenbeispiele, Benchmarks, Seeds, Reports und Proof-Job-Metadaten | persistent |
| Hibernate Search mit Lucene | Volltext- und Facettensuche über relationale Entitäten | aus Primärdaten rekonstruierbar |
| Neo4j, optional | mathematische Provenienz und Beziehungsabfragen | persistent, aber nicht Primärspeicher aller Metadaten |

## Betriebsmodi

| Modus | Geeignet für | Externe Dienste |
| --- | --- | --- |
| `IN_MEMORY` | Tests und kurze CLI-Läufe | keine |
| `JSON_FILE` | Standarddemo und einzelnes Dockerimage | keine |
| `POSTGRESQL` | strikt relationale Metadatenpersistenz | PostgreSQL erforderlich; unvollständige Konfiguration blockiert den Start |
| `POSTGRESQL_WITH_JSON_FALLBACK` | Full Mode mit PostgreSQL und dateibasierten großen Artefakten | PostgreSQL, ansonsten schreibbarer Fallbackpfad |
| `REMOTE_NEO4J` | optionale Graph-Provenienz | Neo4j erforderlich |

Der Standardmodus für neue Nutzer bleibt die dateibasierte lokale Demo. Der
Full Mode erweitert die Persistenz, ändert aber nicht die mathematische
Suchsemantik.

## Konfiguration

### PostgreSQL und Artefaktpfad

```bash
REGELSUCHE_PERSISTENCE_MODE=POSTGRESQL_WITH_JSON_FALLBACK
REGELSUCHE_PERSISTENCE_PATH=/opt/regelsuche/data
POSTGRES_URL=jdbc:postgresql://postgres:5432/regelsuche
POSTGRES_USER=regelsuche
POSTGRES_PASSWORD=replace-with-a-secret
```

### Optionale Neo4j-Provenienz

```bash
NEO4J_URI=bolt://neo4j:7687
NEO4J_USER=neo4j
NEO4J_PASSWORD=replace-with-a-secret
```

Die in `docker-compose.yml` enthaltenen Demo-Passwörter sind ausschließlich
lokale Fallbacks. Vor jeder anderen Nutzung müssen eigene Secrets gesetzt und
die Datenbankports vom öffentlichen Netz getrennt werden.

## Full Mode mit Docker Compose

```bash
docker compose up --build
```

Der Standard-Full-Mode startet Anwendung und PostgreSQL mit Healthchecks und
persistenten Volumes. Veröffentlichte Ports binden standardmäßig an
`127.0.0.1`.

Neo4j wird nur bei Bedarf gestartet:

```bash
docker compose --profile neo4j up --build
```

Ein separates Proof-Worker-Profil kann ergänzend aktiviert werden:

```bash
docker compose --profile proof-worker up --build
```

Die Compose-Konfiguration ist eine lokale Referenzumgebung. Die Anwendung
verwendet ohne zusätzliche Sicherheitskonfiguration weiterhin HTTP ohne
Anmeldung. Für einen externen Betrieb sind authentifiziertes TLS,
Secret-Verwaltung, Netzwerkregeln, Backup, Monitoring und Kapazitätsplanung
erforderlich.

## Relationales Schema und Migrationen

Versionierte SQL-Migrationen liegen unter:

```text
regelsuche-persistence-hibernate/src/main/resources/db/migration/
```

Sie werden vor dem Start von Hibernate ORM angewendet. Eine Schema-History
bindet bereits ausgeführte Versionen; wiederholter Start darf eine Migration
nicht doppelt ausführen.

Das relationale Modell speichert kompakte Metadaten und ausgewählte JSONB-
Strukturen. Hibernate Search mit Lucene ist der primäre Volltext- und
Facettenindex. Der Index ist abgeleitet und muss aus den relationalen
Primärdaten wiederaufbaubar bleiben.

## Graph-Provenienz

Wenn Neo4j aktiviert ist, werden neben dem round-trippable Snapshot typisierte
Provenienzentitäten und Beziehungen gespeichert. Beispiele sind:

- Hypothesen, Gegenbeispiele und Proof-Versuche;
- Suchläufe, Seeds und Transformationspfade;
- Makroregeln und Annahmensignaturen;
- Beziehungen wie `SUPPORTED_BY`, `REFUTED_BY`, `DERIVED_FROM`,
  `GENERALIZES`, `REPLAY_OF` und `GENERATED_BY`.

Neo4j ergänzt relationale Metadaten; es ersetzt sie nicht. Eine deaktivierte
Graph-Provenienz darf den normalen Such- und Evidence-Lebenszyklus nicht
unbrauchbar machen.

## Große Suchtraces

Große Discovery-Läufe verwenden einen kompakten Trace-Store, damit Replay und
Reportgenerierung nicht durch wiederholte JSON-Ausdrücke dominiert werden.

Das Format verwendet insbesondere:

- Interning beziehungsweise Hash-Consing für Ausdrücke, Regel-IDs und
  Annahmensignaturen;
- numerische Referenzen in Kanten;
- kompakte Pfadrepräsentationen;
- Delta- und Varint-Encoding für weniger häufig benötigte Pfade;
- vollständige Rückübersetzung für Replay und Evidence-Ausgabe.

Kompaktierung darf die mathematische Lineage oder die Rekonstruktion eines
retained Pfads nicht verändern.

## Backup und Wiederherstellung

### PostgreSQL

`POSTGRES_URL` ist eine JDBC-URL und kann nicht direkt an `pg_dump` übergeben
werden. Verwende die PostgreSQL-Clientvariablen oder eine libpq-kompatible URL:

```bash
export PGHOST=127.0.0.1
export PGPORT=5432
export PGDATABASE=regelsuche
export PGUSER=regelsuche
export PGPASSWORD='replace-with-a-secret'

pg_dump \
  --format=custom \
  --file=regelsuche-metadata.dump
```

`PGPASSWORD` sollte in einem realen Betrieb durch eine geeignete Secret- oder
`.pgpass`-Lösung ersetzt werden. Die Wiederherstellung muss in einer getrennten
Umgebung getestet werden. Ein vorhandener Dump allein ist noch kein
nachgewiesener Restore-Prozess.

### Dateibasierte Artefakte

Der vollständige Pfad aus `REGELSUCHE_PERSISTENCE_PATH` muss gemeinsam mit der
zugehörigen Anwendungsversion, Konfiguration und Manifestidentität gesichert
werden. Einzelne Dateien ohne ihren gebundenen Kontext sind kein vollständiges
Forschungsbackup.

### Neo4j

Neo4j benötigt eine eigene, zur eingesetzten Edition passende Backup- und
Restore-Strategie. Da Neo4j optional ist, darf ein Graphbackup nicht als Ersatz
für PostgreSQL- und Artefaktbackups behandelt werden.

## Konsistenz und Fehlerverhalten

- Ein strikt gewählter PostgreSQL-Modus blockiert bei fehlender Konfiguration.
- Ein Fallbackmodus darf nur auf einen schreibbaren, ausdrücklich
  konfigurierten Pfad ausweichen.
- Evidence-Manifeste werden zuletzt geschrieben, damit ein fehlendes Manifest
  einen unvollständigen Export kennzeichnet.
- Abgeleitete Suchindizes dürfen neu aufgebaut werden; Primärdaten und
  kanonische Evidence dürfen dabei nicht neu interpretiert werden.
- Technische Persistenzfehler bleiben von mathematischen Terminalzuständen
  getrennt.

## Tests

Schnelle Port- und Repositorytests:

```bash
./gradlew :regelsuche-persistence:test
./gradlew :regelsuche-persistence-hibernate:test
```

Reale PostgreSQL-/Hibernate-Integration über Testcontainers:

```bash
./gradlew :app:dockerE2eTest \
  --tests de.regelsuche.dockere2e.HibernateFullModePersistenceTest
```

Der vollständige Repositoryvertrag wird über den autoritativen CI-Aufruf
geprüft:

```bash
./gradlew --no-configuration-cache ciCheck
```

## Produktionsgrenze

Der Full Mode belegt, dass die Persistenzadapter gemeinsam in einer realen
Containerumgebung funktionieren. Er belegt nicht automatisch:

- Hochverfügbarkeit;
- horizontale Skalierung;
- unterbrechungsfreie Migrationen;
- Backup-RPO oder Restore-RTO;
- Mandantentrennung;
- regulatorische Eignung;
- vollständige Betriebsüberwachung.

Diese Eigenschaften benötigen einen eigenen Betriebsvertrag und entsprechende
Last-, Recovery- und Sicherheitsprüfungen.

## Siehe auch

- [Storage Architecture](storage-architecture.md)
- [Getting Started](getting-started.md)
- [Testing](testing.md)
- [Web-Workbench Security](web-workbench-security.md)
