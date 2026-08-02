# Regelsuche

[![CI / fullCheck](https://github.com/carstenartur/Regelsuche/actions/workflows/gradle.yml/badge.svg?branch=main)](https://github.com/carstenartur/Regelsuche/actions/workflows/gradle.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Regelsuche/coverage/badge.json)](https://carstenartur.github.io/Regelsuche/coverage/)
[![Tests](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Regelsuche/tests/badge.json)](https://carstenartur.github.io/Regelsuche/tests/)
[![Performance](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Regelsuche/dev/bench/badge.json)](https://carstenartur.github.io/Regelsuche/dev/bench/)
[![SBOM](https://img.shields.io/badge/SBOM-CycloneDX-informational?logo=owasp&style=flat)](https://github.com/carstenartur/Regelsuche/dependency-graph/sbom)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.20951900.svg)](https://doi.org/10.5281/zenodo.20951900)


📊 **[Coverage report](https://carstenartur.github.io/Regelsuche/coverage/)** ·
📋 **[Test report](https://carstenartur.github.io/Regelsuche/tests/)** ·
⚡ **[Performance charts](https://carstenartur.github.io/Regelsuche/dev/bench/)**

**Navigation:** [Web Workbench](docs/web-workbench.md) ·
[User Workflows](docs/user-workflows.md) ·
[Demo Gallery](docs/demo-gallery.md) ·
[Discovery Engine](docs/discovery-engine.md) ·
[Discovery Evidence v1](docs/discovery-evidence-v1.md) ·
[Capability status](docs/generated/capability-status.md) ·
[Independent Reproduction](docs/independent-reproduction.md) ·
[Macro Rules](docs/macro-rules.md) ·
[Erweiterungssystem](docs/extension-system.md)

> **Regelsuche macht mathematische Umformungsräume sichtbar.**
> Knoten sind Ausdrücke, Kanten sind Umformungen, Pfade sind Rechenwege —
> mit Replay, Proof-Bridge und einem klickbaren Web-Workbench.

Why “Mathematics is a Game”?

Regelsuche is based on the idea that mathematics can be treated as a rule-based game.

A mathematical expression is a position in the game.

A transformation rule is a legal move.

A sequence of transformations is a line of play.

Unlike chess, the mathematical game space is not finite. Mathematical expressions form an effectively infinite search space with infinitely many possible structures, values, substitutions and levels of abstraction.

Regelsuche does not try to enumerate this space naively.

Instead, it searches for and applies parametrized transformation patterns over mathematical abstract syntax trees.

A transformation rule consists of two parts:

1. a recognition pattern that matches a class of AST structures,
2. an application step that creates an equivalent transformed structure.

In this sense, transformation rules behave like regular expressions over mathematical syntax trees: they can describe infinitely many concrete expressions with a finite pattern.

For example, a single rule can represent all instances of a structure such as:

a² + 2ab + b²  →  (a + b)²

The variables in the rule allow it to match entire families of expressions instead of only one concrete expression.

This is what makes systematic search possible even inside very large or infinite mathematical spaces.

Regelsuche can also expand its own stock of transformations. If the system repeatedly discovers that a sequence of smaller transformations leads to useful simplifications, that sequence can become a higher-level transformation strategy.

The nested sequence of recognized and applied transformations is a reproducible derivation trace. It is reported as a formal proof only when an independent prover returns the required proof evidence.

The long-term vision is that mathematical discovery becomes less dependent on isolated intuition and more the result of systematic exploration: the system searches the game tree of possible transformations, identifies useful paths, reuses successful strategies, and turns repeated discoveries into new rules.

<!-- capability-status:start -->
## Verifizierter Capability- und Claim-Status

Die folgende Kurzmatrix wird aus den kanonischen Release-, Domain- und Trust-Verträgen erzeugt. Die vollständige Matrix mit Evidence-Roots steht in [`capability-status.md`](docs/generated/capability-status.md).

| Capability | Status |
|---|---|
| `AUTONOMOUS_CAMPAIGN` | `QUALIFIED` |
| `DOMAIN_GENERIC_DISCOVERY` | `QUALIFIED` |
| `EXTERNAL_NOVELTY_REVIEW` | `BLOCKED` |
| `FORMAL_PROOF_OF_RETAINED_CANDIDATE` | `NOT_EVALUATED` |
| `PLUGIN_ARTIFACT_TRUST` | `IMPLEMENTED` |
| `PLUGIN_INDEX_AUTHENTICATION` | `IMPLEMENTED` |
| `PLUGIN_TRUST_STATE_REVISIONS` | `IMPLEMENTED` |
| `PROMOTION` | `NOT_EVALUATED` |
| `PUBLIC_EVIDENCE` | `NOT_EVALUATED` |
| `PUBLIC_PLUGIN_DISTRIBUTION` | `BLOCKED` |

`QUALIFIED` autorisiert nur den jeweils benannten Claim. Externe mathematische Neuheit, formaler Beweis, Promotion und Public Evidence werden nicht aus einem anderen erfolgreichen Profil abgeleitet.
<!-- capability-status:end -->

## Discovery evidence

Regelsuche records mathematical transformation searches as replayable evidence.
Generated examples:
- Complete-square factorization: bridge discovery + learned macro reuse
- Sophie-Germain: hidden-structure bridge + learned macro reuse

<a href="docs/demo-gallery.md">Open the generated Discovery Gallery</a>

| Scenario | Bridge | Macro learned | Macro reused | Evidence |
|---|---:|---:|---:|---|
| Complete square | yes | yes | yes | [regelsuche.discovery-evidence/v1#sha256:217d5ca4dff3dba588f16d8451195a883378956a795259936317d3d35e8ce273](docs/generated/discovery/complete-square/evidence.json) |
| Sophie-Germain | yes | yes | yes | [regelsuche.discovery-evidence/v1#sha256:ad5a70e80124c9154f03c870b1f1b6d26fe482463eeb991d805f14eef38a1f31](docs/generated/discovery/sophie-germain/evidence.json) |

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

Die technische REST-Referenz derselben laufenden Instanz ist vollständig lokal
verfügbar: **Swagger UI** unter
`http://localhost:8080/static/openapi/index.html` und **OpenAPI 3.1 JSON** unter
`http://localhost:8080/static/openapi/openapi.json`. Der Tab **Hilfe** verlinkt
beide Zugänge; Swagger lädt keine Assets von einem CDN.

## Optional: Full Mode mit PostgreSQL/Hibernate

Für persistente, größere Analysen mit PostgreSQL, Hibernate ORM und Hibernate
Search gibt es eine optionale `docker-compose.yml` — sie ist **nur optionaler
Persistenz-/Full-Mode** und nicht Voraussetzung für die Quickstart-Demo:

```bash
docker compose up --build
```

Das startet App + PostgreSQL + persistentes Volume und setzt `POSTGRES_URL`,
`POSTGRES_USER`, `POSTGRES_PASSWORD` automatisch. Neo4j bleibt optional für
mathematische Graph-Provenance (`docker compose --profile neo4j up --build`).
Details in
[`docs/getting-started.md`](docs/getting-started.md) und
[`docs/persistence.md`](docs/persistence.md).

## 5-Minuten-Tour

Eine knappe, geführte Tour für neue Nutzer (≈ 5 Minuten):

1. **Demo starten.** Auf `http://localhost:8080/` einen Demo-Button (z.B.
   _Binomische Formel_) klicken. Erst nach diesem Klick werden die Folge-Tabs
   (Graph, Replay, Proof-Jobs, AST-Regelradar, Export, …) sichtbar — das Landing-Form ist
   absichtlich auf einen einzigen Hauptfluss „Ausdruck → Ziel → Suche starten"
   reduziert.
2. **Suchgraph + Replay ansehen.** `Graph`-Tab zeigt den entdeckten
   Transformationsraum; `Replay` spielt den besten Pfad Schritt für Schritt
   ab.
3. **Lokale Züge verstehen.** Im Tab `AST-Regelradar` einen AST-Knoten
   auswählen. Die Regelpunkte zeigen, welche konkreten Grund-, Erweiterungs-
   und Makroregeln dort mit welchen Bindungen und Annahmen anwendbar sind und
   welchen vollständigen Folgeausdruck sie erzeugen würden.
4. **Eine nichttriviale Identität formal prüfen.** Im `Proof-Jobs`-Tab
   `Left=a^4 + 4*b^4` und
   `Right=(a^2 - 2*a*b + 2*b^2)*(a^2 + 2*a*b + 2*b^2)` eintragen, dann
   _Job einreichen_. Das ist die Sophie-Germain-Identität, die auch in der
   generierten Discovery Gallery als Hidden-Structure-Bridge mit
   Makrowiederverwendung dokumentiert ist — nicht nur eine Neutralregel wie
   `a + 0 = a`. Nichtnegative ganzzahlige Exponenten werden vom SMT-Bridge in
   gewöhnliche nichtlineare reelle Arithmetik expandiert. Der von der
   Anwendung konfigurierte Proof-Worker prüft die Gleichheit; im Proof-Image
   steht dafür der SMT-Pfad mit Z3 beziehungsweise cvc5 bereit. Status und
   Solver-Ausgabe werden live aktualisiert. Das Bundle (`proof.lean` oder
   `proof.smt2`, `metadata.json`, `stdout.txt`, `stderr.txt`) liegt unter
   `$REGELSUCHE_PROOF_ARTIFACT_PATH/<jobId>/`. `FORMALLY_PROVED` wird nur
   gesetzt, wenn der konfigurierte Solver die Obligation tatsächlich bestätigt.
   ![Proof-Job-Panel](docs/assets/screenshots/proof-job-panel.png)
5. **Qualitätsdashboard prüfen.** `Benchmark`-Tab → jede Zeile zeigt
   Ampelstatus, `expectedResultMatched`, e-Graph-Größe, Saturation-Sparung
   und ob eine gelernte Makroregel beteiligt war. Der vollständige Report
   liegt unter [`docs/benchmark-report.md`](docs/benchmark-report.md) (CI lädt
   ihn als Artefakt `benchmark-report` hoch).
6. **Bericht exportieren.** Im `Exporte`-Tab den `bundle.zip` Download
   starten — enthält Markdown/LaTeX/JSON/Mermaid/GraphML und das aktuelle
   Rule-Inventory.

Die Markdown-Dokumentation beschreibt diese grafischen Abläufe. Für direkte
REST-Integrationen ist ausschließlich die Swagger/OpenAPI-Dokumentation der
laufenden Installation verbindlich; HTTP-Pfade, Payloads und Statuscodes werden
nicht parallel im README gepflegt.

## Autonomous Discovery Result Card

Der qualifizierte autonome Produktionslauf lässt sich ohne UI und ohne externe
Dienste als hashgebundene Result Card reproduzieren:

```bash
./gradlew :regelsuche-release:runAutonomousDiscoveryWalkthrough
```

Der Befehl erzeugt JSON, Markdown, vier evidence-abgeleitete SVGs und das
vollständige Rohartefakt-Bundle. Jede angezeigte Zahl verweist auf ihr
Autoritätsartefakt und dessen Hash. Kandidatenbildung ohne Target-/Antwortzugriff,
Qualification und paired held-out reuse werden sichtbar; externe mathematische
Neuheit, Promotion und Public Evidence bleiben ausdrücklich getrennt und
`NOT_EVALUATED`.

![Generated autonomous discovery evidence sequence](docs/generated/autonomous-discovery-walkthrough/sequence.svg)

[Walkthrough, Container-Befehl und unabhängiger Verifier](docs/autonomous-discovery-walkthrough.md)

## Independent Reproduction Artifact

Aus derselben qualifizierten Evidence kann ein deterministisches, unveränderlich gebundenes Reproduktionspaket mit Quellarchiv, Manifest, gepinntem Container und maschinenlesbarem Receipt gebaut werden. Nach dem Entpacken lautet der unterstützte Aufruf:

```bash
./reproduce.sh --output /path/to/reproduction-output
```

Die technische Paketierung sowie lokale und Container-Reproduktion sind maschinenprüfbar. Eine auf unabhängig administrierter Infrastruktur veröffentlichte Drittanbieter-Attestation liegt noch nicht vor und bleibt explizit `NOT_COLLECTED`.

[Artifact-, Receipt- und Attestation-Vertrag](docs/independent-reproduction.md)

## Scientific Discovery Walkthrough

Für reproduzierbare Discovery-Läufe ohne UI:

```bash
./gradlew :app:test --tests de.regelsuche.discovery.ScientificDiscoveryReproductionTest
```

Der Lauf speist kuratierte `SeedExpression`s in die produktive
`ScientificDiscoveryWorkflow`-Komposition ein und erzeugt Replay-/Report-
Artefakte: `discovery-report.json`, `discovery-report.html`,
`discovery-report.md`, `discovery-replay.json`, `discovery-summary.png` und
`discovery-replay.gif`. Für PostgreSQL-/Hibernate-Metadaten plus Artefakte:

```bash
./gradlew :app:dockerE2eTest --tests de.regelsuche.dockere2e.ScientificDiscoveryPostgresE2ETest
```

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
* **AST-Regelradar** — zoombare Baumansicht mit positionsgebundenen
  Regelanwendungen, Vorschau, Annahmen, Makro-Expansion und korreliertem
  Auswahl-, Anwendungs- und Pruningstatus.
* **Proof-Bridge** generiert ein Lean/SMT-Skript pro Pfad. `FORMALLY_PROVED`
  wird nur gesetzt, wenn der Prover den Beweis bestätigt.
* **Proof-Workbench** — persistente Job-Queue mit eigenem UI-Tab. Über
  **Proof-Jobs** lassen sich Aufträge einreichen, verfolgen, abbrechen und ihre
  Artefakte öffnen. Jobs, Cache und Artefakt-Bundle (`proof.lean`, `proof.smt2`,
  `stdout.txt`, `stderr.txt`, `metadata.json` pro Job) werden via
  `REGELSUCHE_PROOF_ENABLED`, `REGELSUCHE_PROOF_ARTIFACT_PATH`,
  `REGELSUCHE_PROOF_JOB_STORE` und `REGELSUCHE_PROOF_CACHE` konfiguriert.
  Für echte Prover gibt es `Dockerfile.proof` (Z3 + cvc5 vorinstalliert, Lean
  optional via `--build-arg INSTALL_LEAN=true`). Der technische REST-Vertrag
  steht in Swagger/OpenAPI.
* **Persistenz** als leichter JSON-Demo-Modus, PostgreSQL/Hibernate-Metadaten
  im Full Mode, Hibernate Search für Text/Facetten und optionales Neo4j für
  mathematische Graph-Provenance.
* **Export-Bundle** mit Markdown, LaTeX, JSON, Mermaid, GraphML und dem
  Rule-Inventory in einer einzigen ZIP.

## Erweiterungen

Regelsuche kann auf mehreren Ebenen erweitert werden:

- **Java-Plugins** über `ServiceLoader` und `PluginRuntime`,
- **Regeldateien** in der `.regelsuche`-/`.rules`-DSL,
- kuratierte **Knowledge Packs**,
- deklarative sowie gelernte/promovierte **Makros**,
- explizite **Discovery-Operatoren** und mathematische **Capabilities**.

Diese Wege haben unterschiedliche Vertrauens- und Qualitätsgrenzen. Insbesondere
ist nicht jede interne Discovery- oder Solver-Registry automatisch ein externer
Plugin-JAR-Endpunkt. Lokale Plugin-Artefakte, unveränderliche Indexrevisionen
und Trust-State-Revisionen können kryptografisch geprüft werden. Ein gehosteter
Katalog sowie Download, Installation, Update, Entfernung und Rollback bleiben
davon getrennte, noch nicht qualifizierte Capabilities.

👉 **[Erweiterungssystem und Auswahlhilfe](docs/extension-system.md)** ·
[Plugins](docs/plugins.md) ·
[Regeldateien](docs/rule-files.md) ·
[Knowledge Packs](docs/knowledge-packs.md)

## Quickstart-Varianten

* [Getting Started](docs/getting-started.md) — Docker, lokaler Gradle-Lauf,
  grafischer Einstieg und optionaler Full Mode.
* [Web-Workbench](docs/web-workbench.md) und
  [Benutzerhandbuch](docs/web-ui-user-guide.md) — aktuelle GUI-Bereiche,
  sichtbare Ergebnisse und Zustände.
* [Docker-/Compose-Setup](docker-compose.yml) — PostgreSQL-Full-Mode und
  optionales Neo4j-Profil.
* [Architektur](docs/architecture.md) — Leitplanken und Überblick.
* [Modulstruktur](docs/module-structure.md) — Gradle-Module inkl. Search/Persistence/Learning/Experiments (inkl. Seed-Corpus)/CLI/Discovery, verbleibende logische Module und Paketmapping.
* [Dependency-Regeln](docs/dependency-rules.md) — erlaubte Abhängigkeitsrichtungen.
* [Nutzer-Workflows](docs/user-workflows.md) — Lernen, Discovery, Vergleich,
  Proof, Qualität und AST-Regelradar.
* [Such-Intelligenz](docs/search-intelligence.md) und
  [Equality-Saturation](docs/equality-saturation.md).
* [Math-Domains](docs/math-domains.md) — semantische Domänen, Replay-Karten,
  Discovery-Tags.
* [Discovery Engine](docs/discovery-engine.md) — End-to-End-Pipeline von Seed
  über Replay bis Persistenz/Reports.
* [Hypothesis Mining](docs/hypothesis-mining.md) — Kandidaten, Annahmen,
  Counterexamples, Promotion.
* [Experiment Runner](docs/experiment-runner.md) — deterministische
  Seed-Auswertung, Budgets, Parallelität.
* [Replay & Reports](docs/replay-and-reports.md) — Replay-UX, Discovery-Report-
  Artefakte, Browser-Screenshots/GIFs.
* [Exakte lineare Rekurrenzen](docs/linear-recurrence-discovery-domain.md) —
  kandidatunabhängige Sequenzbildung mit rationaler Arithmetik und Holdout-Prüfung.
* [Erweiterungssystem](docs/extension-system.md) — Java-Plugins, Regel-DSL,
  Knowledge Packs, Makros, Discovery-Operatoren und Capabilities im Vergleich.
* [Plugins](docs/plugins.md) und [Plugin-API](docs/plugin-api.md) — externe
  Java-JARs, Registries, Reload und Vertrauensmetadaten.
* [Regeldateien](docs/rule-files.md) — deklarative Regeln, Makros und
  Aktivierungsprofile ohne Java.
* [Storage Architecture](docs/storage-architecture.md) — In-Memory, JSON,
  PostgreSQL/Hibernate und Artefakt-Ablage.
* [Persistenz](docs/persistence.md) — Betriebsmodi und Adapterdetails.
* [Mathematical Algorithms](docs/mathematical-algorithms.md) — Registry,
  Validierungs-Backends und aktuelle Toggles.
* [Scientific Reproducibility](docs/scientific-reproducibility.md) —
  reproduzierbare Discovery-Läufe, Seeds und CI-Artefakte.
* [Benchmarks](docs/benchmark-report.md) — aktuelle Benchmark-Beispiele und
  Qualitätsdashboard.
* [Bekannte Grenzen](docs/limits.md) — bewusst begrenzte Semantik und
  Follow-up-Entscheidungen.
* [Proof-Bridge](docs/proof-bridge.md) — vom Pfad zum formalen Beweis.
* [Proof-Workbench](docs/proof-workbench.md) — grafischer Job-Lebenszyklus,
  Artefakt-Bundle und Dockerfile.proof.
* [AST-Regelradar](docs/ast-rule-radar.md) — lokale anwendbare Regeln pro
  Baumposition und Korrelation mit dem globalen Suchgraphen.
* [Macro-Rules](docs/macro-rules.md) — wie das System eigene Regeln lernt.
* [Testing](docs/testing.md) — Task-Referenz der Testpipelines.
* [Testing-Strategie](docs/testing-strategy.md) — Schichtung nach Core/Integration/E2E.
* [Developer Guide](docs/developer-guide.md) — Repo-Layout, Build-Kommandos,
  Konventionen sowie API- und UI-Erweiterungen.

Eine Komplett-Übersicht der Dokumentation findet sich unter
[`docs/`](docs/). Die historische Langfassung dieses README ist
nach [`docs/README.legacy.md`](docs/README.legacy.md) verschoben und
bleibt für Detail-Recherchen erhalten.

## Mitmachen

Pull Requests sind willkommen — bitte beachte:

* `./gradlew test` muss grün sein,
* `./gradlew e2eTest` muss grün sein, sobald UI-Code geändert wurde
  (siehe [docs/testing.md](docs/testing.md)),
* Screenshots in [docs/demo-gallery.md](docs/demo-gallery.md) werden über
  `./gradlew test e2eTest -Pregelsuche.recordDocs=true`
  aktualisiert — niemals händisch ersetzen.
* Nutzerseitige Texte (Demo-Gallery, UI, Replay-Karten, Berichte)
  bitte gegen die
  [Documentation Quality Checklist](docs/documentation-quality-checklist.md)
  prüfen.
* Die [Dokumentationskonvention](docs/documentation-conventions.md) ist
  verbindlich: GUI-Abläufe in Markdown, REST-Verträge ausschließlich in
  Swagger/OpenAPI.

Lizenz: [MIT](LICENSE).
