# Getting Started

## 30 Sekunden zur ersten Demo

```bash
docker build -t regelsuche .
docker run --rm -p 8080:8080 regelsuche
```

Anschließend [http://localhost:8080/](http://localhost:8080/) öffnen. Im
Demo-Hero stehen neun Buttons bereit — von der **Binomischen Formel** über
**Bruchkürzung**, **Trigonometrie**, **Polynom-Expansion** und
**Macro-Learning** bis zu den vier Math-Domain-Demos
**Gleichung / Ungleichung / Ableitung / Matrix**. Ein Klick startet die Suche,
zeigt Suchgraph, Replay, Best Move und erkannte Identität an und liefert den
Bericht als Bundle.

Eine kuratierte Übersicht aller Demos mit echten Screenshots steht in der
[Demo-Gallery](demo-gallery.md).

## Ohne Docker (lokaler Gradle-Lauf)

```bash
./gradlew run
```

Erfordert JDK 21. Der Server bindet auf
`http://127.0.0.1:8080/` und benutzt eine lokale JSON-Datei unter
`./data/` für die Persistenz.

## Wichtige Endpunkte

| Endpoint | Zweck |
| --- | --- |
| `GET  /api/demo` | Liste aller Demos |
| `POST /api/demo/{id}` | Demo ausführen (`binomial`, `rational`, `trigonometry`, `polynomial-expansion`, `macro-learning`, `math-equation`, `math-inequality`, `math-derivative`, `math-matrix`) |
| `GET  /api/paths` | Bisher entdeckte Pfade |
| `GET  /api/paths/{id}/replay` | Schritt-für-Schritt-Replay eines Pfades |
| `GET  /api/proof-status` | Erklärungen zu Proof-Status-Stufen |
| `POST /api/proof-bridge` | Lean/SMT-Skript erzeugen und (sofern installiert) prüfen lassen |
| `GET  /api/benchmark` | Benchmark-Lauf als JSON |
| `GET  /api/exports/bundle.zip` | Kompletter Bericht in einer ZIP |

## Optional: Full Mode mit Neo4j

```bash
docker compose up --build
```

Startet App + Neo4j 5 Community + persistentes Volume und setzt
`NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD` automatisch. Die App schaltet
dann den `REMOTE_NEO4J`-Persistenzmodus aktiv (siehe
[`docs/architecture.md`](architecture.md)).

## Nächste Schritte

* [Demo-Gallery](demo-gallery.md) – Visuelle Übersicht aller Flows.
* [Architektur](architecture.md) – Leitplanken und Überblick.
* [Modulstruktur](module-structure.md) – logische Module und Paketmapping.
* [Dependency-Regeln](dependency-rules.md) – erlaubte Abhängigkeitsrichtungen.
* [Such-Intelligenz](search-intelligence-roadmap.md) – Strategien, Beam, A\*, Equality Saturation.
* [Math-Domains](math-domains.md) – Gleichungen, Ungleichungen, Ableitung, Lineare Algebra.
* [Proof-Bridge](proof-bridge.md) – Vom Suchpfad zum formalen Beweis.
* [Macro-Rules](macro-rules.md) – Wie das System eigene Regeln lernt.
* [Testing](testing.md) – Unit-, Integrations- und Browser-E2E-Tests.
* [Testing-Strategie](testing-strategy.md) – Schichtung für schnelle Core-Tests und stabile E2E-Layer.
