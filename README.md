# Regelsuche

[![CI/CD](https://github.com/carstenartur/Regelsuche/actions/workflows/ci-cd.yml/badge.svg?branch=main)](https://github.com/carstenartur/Regelsuche/actions/workflows/ci-cd.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Regelsuche/coverage/badge.json)](https://carstenartur.github.io/Regelsuche/coverage/)
[![Tests](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Regelsuche/tests/badge.json)](https://carstenartur.github.io/Regelsuche/tests/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![SBOM](https://img.shields.io/badge/SBOM-CycloneDX-informational?logo=owasp&style=flat)](https://github.com/carstenartur/Regelsuche/dependency-graph/sbom)
[![GitHub release](https://img.shields.io/github/v/release/carstenartur/Regelsuche?style=flat-square)](https://github.com/carstenartur/Regelsuche/releases)

> **Regelsuche macht mathematische Umformungsräume sichtbar.**
> Knoten sind Ausdrücke, Kanten sind Umformungen, Pfade sind Rechenwege —
> mit Replay, Proof-Bridge und einem klickbaren Web-Workbench.

![Binomial-Demo](docs/assets/screenshots/binomial-graph.png)

## 30 Sekunden (Demo Standard)

**Der Standardmodus der Demo läuft ohne externe Infrastruktur:**

```bash
docker build -t regelsuche .
docker run --rm -p 8080:8080 regelsuche
```

[http://localhost:8080/](http://localhost:8080/) öffnen, einen der neun
Demo-Buttons klicken — Binomische Formel, Bruchkürzung, Trigonometrie,
Polynom-Expansion, Macro-Learning, Gleichung, Ungleichung, Ableitung,
Matrix. Die Suche läuft, der Suchgraph erscheint, der Replay läuft Schritt
für Schritt durch den Lösungsweg, der Bericht steht als ZIP bereit.

Eine kuratierte Übersicht **mit echten, aus den Tests generierten
Screenshots** zu jeder Demo:
👉 **[docs/demo-gallery.md](docs/demo-gallery.md)**.

## Optional: Full Mode mit Neo4j

Für persistente, größere Analysen mit externem Neo4j-Server gibt es eine
optionale `docker-compose.yml` — sie ist **nur optionaler
Persistenz-/Full-Mode** und nicht Voraussetzung für die Quickstart-Demo:

```bash
docker compose up --build
```

Das startet App + Neo4j 5 Community + persistentes Volume und setzt
`NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD` automatisch. Details in
[`docs/getting-started.md`](docs/getting-started.md) und
[`docs/architecture.md`](docs/architecture.md).

## Was kann Regelsuche?

* **Atomare Rewrite-Regeln** statt vorgefertigter Formeln — Schulbuchidentitäten
  emergieren als Pfade durch den Suchraum.
* **Vier Math-Domänen** mit eigenen Replay-Karten: Gleichungen,
  Ungleichungen (inkl. Vergleichszeichen-Flip), Analysis
  (Potenz-/Summen-/Produktregel), Lineare Algebra (`bmatrix`-Preview).
* **Suchstrategien**: Best-First, Beam, A\*, Random-Monte-Carlo,
  **Equality Saturation** mit eigenem E-Graph.
* **Discovery+** — domain-aware Mining: gefundene Makroregeln werden
  automatisch mit `equations`, `inequalities`, `calculus` oder
  `linear-algebra` getaggt.
* **Proof-Bridge** generiert ein Lean/SMT-Skript pro Pfad. `FORMALLY_PROVED`
  wird nur gesetzt, wenn der Prover den Beweis bestätigt.
* **Persistenz** als einzelner JSON-File (Killer-Demo-Modus) oder echtes
  Neo4j 5 (Full Mode via `docker compose up`).
* **Export-Bundle** mit Markdown, LaTeX, JSON, Mermaid, GraphML und dem
  Rule-Inventory in einer einzigen ZIP.

## Quickstart-Varianten

* [Getting Started](docs/getting-started.md) — Docker, lokaler Gradle-Lauf,
  wichtige Endpunkte, optionaler Neo4j-Mode.
* [Architektur](docs/architecture.md) — Module, Datenflüsse, Persistenz.
* [Such-Intelligenz](docs/search-intelligence-roadmap.md) und
  [Equality-Saturation](docs/equality-saturation.md).
* [Math-Domains](docs/math-domains.md) — semantische Domänen, Replay-Karten,
  Discovery-Tags.
* [Proof-Bridge](docs/proof-bridge.md) — vom Pfad zum formalen Beweis.
* [Macro-Rules](docs/macro-rules.md) — wie das System eigene Regeln lernt.
* [Testing](docs/testing.md) — Unit-, Browser-E2E- und Doc-Asset-Pipelines.

Eine Komplett-Übersicht der Dokumentation findet sich unter
[`docs/`](docs/). Die historische Langfassung dieses README ist
nach [`docs/README.legacy.md`](docs/README.legacy.md) verschoben und
bleibt für Detail-Recherchen erhalten.

## Mitmachen

Pull Requests sind willkommen — bitte beachte:

* `./gradlew test` muss grün sein,
* `./gradlew e2eTest` muss grün sein, sobald UI-Code geändert wurde
  (siehe [docs/testing.md](docs/testing.md)),
* Screenshots in [docs/demo-gallery.md](docs/demo-gallery.md) werden
  ausschließlich über `./gradlew e2eTest -Pregelsuche.recordDocs=true`
  aktualisiert — niemals händisch ersetzen.

Lizenz: [MIT](LICENSE).
