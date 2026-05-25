# Storage Architecture

Discovery-Läufe schreiben nicht nur Suchgraphen, sondern auch Hypothesen, Gegenbeispiele, Proof-Metadaten und Report-Artefakte.

Persistenzmodi:

- `IN_MEMORY` — Tests und lokale Kurzläufe
- `JSON_FILE` — leichter Demo-/Artefaktmodus
- `POSTGRESQL_WITH_JSON_FALLBACK` — relationale Metadaten plus JSON-Fallback

Architektur:

- `:regelsuche-persistence` enthält Ports und Basiskonfiguration.
- `:regelsuche-persistence-hibernate` enthält Hibernate/PostgreSQL-Adapter.
- `app` hält das Wiring im `PersistenceContext`.
- Discovery-Artefakte liegen lokal im übergebenen Artifact-Ordner (`discovery-report.*`, `discovery-replay.json`, PNG, GIF).

Weiterführende Dokumente:

- [docs/persistence.md](persistence.md)
- [docs/module-structure.md](module-structure.md)
- [docs/discovery-engine.md](discovery-engine.md)
