# Regelsuche

[![CI/CD](https://github.com/carstenartur/Regelsuche/actions/workflows/ci-cd.yml/badge.svg?branch=main)](https://github.com/carstenartur/Regelsuche/actions/workflows/ci-cd.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Regelsuche/coverage/badge.json)](https://carstenartur.github.io/Regelsuche/coverage/)
[![Tests](https://img.shields.io/endpoint?url=https://carstenartur.github.io/Regelsuche/tests/badge.json)](https://carstenartur.github.io/Regelsuche/tests/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![SBOM](https://img.shields.io/badge/SBOM-CycloneDX-informational?logo=owasp&style=flat)](https://github.com/carstenartur/Regelsuche/dependency-graph/sbom)
[![GitHub release](https://img.shields.io/github/v/release/carstenartur/Regelsuche?style=flat-square)](https://github.com/carstenartur/Regelsuche/releases)

Gradle-basiertes Java-Projekt für regelbasierte Ausdrucksumformungen mit:

- Eingabe von Termen, Gleichungen und Gleichungssystemen
- Parsing in einen abstrakten Syntaxbaum (AST)
- AST-Rewrite-Engine mit lokalen, strukturellen Umformungsregeln
- Optionale SymPy-Integration über GraalVM Polyglot als zusätzliche Transformations- und Äquivalenzquelle
- Neo4j-Graphmodell (Knoten: Ausdrücke, Kanten: Umformungen)
- Heuristischer Suchbegrenzung (Suchtiefe, besuchte Ausdrücke)
- Hintergrundausführung der Umformungssuche
- Benachrichtigung bei deutlich besseren Vereinfachungen
- Abfrage des aktuellen Graphzustands und der besten gefundenen Lösung
- Generator für konkrete algebraische Beispiele mit kleinen Integer-Parametern
- Mining von Regel-Kandidaten per AST-Normalisierung, Anti-Unification und Parameter-Relationen
- Referenzbestand bekannter Regeln zum Einordnen gefundener Kandidaten

> Regelsuche macht mathematische Umformungsräume sichtbar – Knoten sind Ausdrücke,
> Kanten sind Umformungen, Pfade sind Rechenwege. Siehe [docs/visual-search-graph.md](docs/visual-search-graph.md),
> [docs/replay-mode.md](docs/replay-mode.md), [docs/macro-rules.md](docs/macro-rules.md)
> und [docs/didactic-ranking.md](docs/didactic-ranking.md).

## Quickstart: Single Docker Image (Killer-Demo Standard)

**Der Standardmodus der Killer-Demo benötigt keine externe Infrastruktur.**
Ein einziger Docker-Build, ein Image, in unter fünf Minuten ein klickbarer
Demo-Flow im Browser:

```bash
docker build -t regelsuche .
docker run --rm -p 8080:8080 regelsuche
```

Anschließend `http://localhost:8080/` öffnen. Im Demo-Hero stehen vier große
Buttons bereit – **Binomische Formel**, **Bruchkürzung**, **Trigonometrie**,
**Polynom-Expansion**. Ein Klick startet die Suche, zeigt Suchgraph, Replay,
Best Move und erkannte Identität an und liefert den Bericht als Bundle.

Das Image enthält:

* die Regelsuche-App und Web-Workbench,
* lokale Persistenz **ohne externe Dienste** (JSON-Datei unter
  `/opt/regelsuche/data`, siehe Abschnitt *Persistenz*),
* alle vorkonfigurierten Demos.

Wichtige Endpunkte:

* `GET /api/demo` – Liste der Demos
* `POST /api/demo/{binomial|rational|trigonometry|polynomial-expansion}` – Demo ausführen
  (`equation` bleibt als Alias bestehen)
* `GET /api/proof-status` – Erklärungen zu Proof-Status-Stufen
* `GET /api/benchmark` – Benchmark-Lauf als JSON
* `GET /api/exports/bundle.zip` – kompletter Bericht (Markdown, LaTeX, JSON,
  Mermaid, GraphML, Rule-Inventory) in einer Zip-Datei

## Optional: Full Mode mit Neo4j

Für persistente, größere Analysen mit externem Neo4j-Server liefert das
Repository eine optionale `docker-compose.yml`:

```bash
docker compose up --build
```

Das startet App + Neo4j 5 Community + persistentes Neo4j-Volume und setzt
die Variablen `NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD` automatisch. Die
App nutzt dann den `REMOTE_NEO4J`-Modus statt der JSON-Datei.

**Wichtig:** Compose ist nur optionaler Persistenz-/Full-Mode – der
Wow-Demo-Flow funktioniert vollständig schon mit dem Single-Image-Quickstart
oben.

## Persistenz: GraphPersistenceMode

Konfiguration über zwei Umgebungsvariablen (alternativ über die
JVM-Properties `regelsuche.persistence.mode` / `regelsuche.persistence.path`):

| Modus            | Beschreibung                                                |
|------------------|-------------------------------------------------------------|
| `IN_MEMORY`      | Alles im RAM. Standard für CLI/Tests.                       |
| `JSON_FILE`      | Lokale JSON-Datei, keine externen Dienste. Docker-Standard. |
| `EMBEDDED_NEO4J` | *(reserviert)* Im aktuellen Distribut auf `JSON_FILE` gemappt – das Image bleibt schlank, keine GPL-lizenzierte Embedded-DB nötig. |
| `REMOTE_NEO4J`   | Externer Neo4j-Server. Wird automatisch gewählt, wenn `NEO4J_URI/USER/PASSWORD` gesetzt sind. |

Beispiele:

```bash
# Standard (Docker-Image setzt das bereits):
REGELSUCHE_PERSISTENCE_MODE=JSON_FILE \
REGELSUCHE_PERSISTENCE_PATH=./data/regelsuche \
./gradlew :app:run --args='serve --host 0.0.0.0 --port 8080'

# Full Mode (manuell, ohne docker-compose):
NEO4J_URI=bolt://localhost:7687 NEO4J_USER=neo4j NEO4J_PASSWORD=secret \
./gradlew :app:run --args='serve --port 8080'
```

## Starten

```bash
./gradlew :app:run --args='term "x + 0"'
```

## Demo: Binomische Formel

Eingegeben wird `(x+3)^2`, herausgefunden wird die binomische Formel als
emergente Identität aus rein atomaren Rewrite-Regeln (`power_two_to_product`
→ `distribute` → `combine_like_terms`).

* **Eingabe:** `(x+3)^2`
* **Erwarteter Treffer:** `(x+3)^2 = 9 + 6*x + x^2`
* **Replay-Tab** zeigt den Weg über `(x+3)*(x+3) → x*x + x*3 + 3*x + 3*3 →
  9 + 6*x + x^2`.
* **Makroregel:** `(a+b)^2 → a^2 + 2*a*b + b^2`.

## Demo: Bruchkürzung

* **Eingabe:** `(x*y)/(x*z)`
* **Erwarteter Treffer:** `(x*y)/(x*z) = y/z`
* **Annahme** (im UI sichtbar): `x ≠ 0`.
* Regel: `rational_cancel_common_factor`.

## Demo: Trigonometrie

* **Eingabe:** `sin(x)^2 + cos(x)^2`
* **Erwarteter Treffer:** `sin(x)^2 + cos(x)^2 = 1`
* Regel: `trig_pythagorean_sin_cos` (bzw. die symmetrische Variante).

## Demo: Polynom-Expansion

(Die frühere Demo „Polynom-Gleichung" wurde ehrlich umbenannt: der aktuelle
atomare Regelvorrat löst keine linearen Gleichungen, expandiert aber
Polynome.)

* **Eingabe:** `(x+1)*(x+2)`
* **Erwarteter Treffer:** `(x+1)*(x+2) = x^2 + 3*x + 2`
* `equation` bleibt als URL-Alias erhalten.

## Grenzen

* Keine Gleichungslösung über Symbol-Isolation – die Demos beschränken sich
  auf Term-Umformungen.
* Embedded-Neo4j ist im Standard-Image **nicht** enthalten (sonst zöge das
  eine GPL-lizenzierte Server-Abhängigkeit ins Image); statt dessen kommt
  `JSON_FILE` zum Einsatz, das für den Single-User-Demo-Charakter mehr als
  genug ist.
* Suchtiefe und Knotenbudget sind pro Demo bewusst begrenzt – grössere
  Analysen sind die Domäne des Full Mode.


## CLI Quickstart

Die CLI unterstützt mehrere Unterkommandos für Entdeckung, Transformation, Regelvorrat und Pfadanzeige:

```bash
# Regelentdeckung mit Export nach Markdown, JSON und Mermaid in das Verzeichnis ./exports
./gradlew :app:run --args='discover --min 1 --max 5 --export markdown,json,mermaid'

# Direkte Umformung eines Terms
./gradlew :app:run --args='transform "x + 0"'

# Regelvorrat anzeigen
./gradlew :app:run --args='inventory list'

# Regelvorrat als JSON exportieren
./gradlew :app:run --args='inventory export --format json'

# Konkreten Pfad als Markdown rendern
./gradlew :app:run --args='path show <pfadId> --format markdown'
```

Beispielausgabe nach einem Entdeckungslauf:

```
Found 2 rule candidate(s).
Best improvement: (x + 3)^2 -> x^2 + 6*x + 9 (Δ=24)
Exported 18 transformations to /…/exports/discovered-transformations.json
Exported 18 transformations to /…/exports/discovered-transformations.md
Exported 18 transformations to /…/exports/transformation-graph.mmd
```

## Exportformate

Discovery-Ergebnisse können in mehreren Formaten ausgegeben werden:

- **JSON** (`discovered-transformations.json`) – versioniertes Schema (`schemaVersion`, `generatedAt`, `transformations`, `ruleCandidates`, `reusableRules`) mit vollständigem Rechenweg pro Transformation, lesbar/zurücklesbar via `TransformationImportService`.
- **Markdown** (`discovered-transformations.md`) – nummerierte Abschnitte mit Rechenweg und Bewertungstabelle (Vorher/Nachher).
- **LaTeX** (`discovered-transformations.tex`) – mathematische Darstellung in `\begin{align*}`-Umgebungen.
- **Mermaid** (`transformation-graph.mmd`) – Graph-Ansicht der Rechenwege; aufeinanderfolgende Schritte teilen sich Knoten-IDs.
- **Regelvorrat** (`rule-inventory.json`) – aktuelle wiederverwendbare Regeln.

## Regelvorrat

`RuleInventoryRepository` speichert wiederverwendbare Regeln (`ReusableRule`) inklusive `canonicalHash`, `createdAt`, `lastUsedAt`, `usageCount`. Doppelte Einträge (gleicher `canonicalHash`) werden zurückgewiesen und stattdessen wird der Verwendungszähler der bestehenden Regel erhöht. Repository-Methoden: `findById`, `findByStatus`, `findReusable`, `saveAll`, `importBundle`, `exportBundle`.

## Wiederverwendung gefundener Regeln

`InventoryBackedRewriteRuleProvider` aktiviert nicht alle bekannten Regeln blind. Die `RuleInventoryConfiguration` erlaubt Allow-/Deny-Listen, das Deaktivieren einzelner Regeln, einen `minProofStatus` und eine maximale Komplexitätszunahme. Für jede betrachtete Regel wird eine `RuleActivationDecision` geliefert, sodass nachvollziehbar ist, warum eine Regel nicht aktiviert wurde.

## Grenzen der Regelentdeckung

Die Entdeckung verlässt sich auf atomare Rewrite-Regeln und symbolische Äquivalenz. Spezialregeln (z.B. `quadratic_*`, `binomial_*`, `perfect_square_*`, `difference_of_squares_*`) werden bewusst **nicht** zur Erzeugung von Rewrite-Schritten verwendet; der `QuadraticAnalyzer` dient nur als Baseline-/Äquivalenz-Fallback bzw. in Tests. Welche Pfade entdeckt werden, hängt von der Suchstrategie, der `SearchHeuristic` und der `DiscoverySettings` ab (`includeNonImprovingEquivalentPaths`, `maxPathLengthForCandidateMining`, `minExamplesPerCandidate`, `minReusableStatus`). Bei besonders breiten Suchen sollte die Beam-Breite und der `maxExpandingSteps`-Wert hinreichend hoch gewählt werden.

## Glossar

| Begriff | Bedeutung |
|---|---|
| `RewriteRule` | Atomare strukturelle Umformungsregel (z.B. `ast_distribute_left_add`). Bausteine, aus denen Pfade entstehen. |
| `TransformationStep` | Ein einzelner Anwendungsschritt einer `RewriteRule` mit Vorher/Nachher-Ausdruck und Bewertung. |
| `DiscoveredTransformation` | Vollständiger Rechenweg (Liste von `TransformationStep`s) mit stabiler ID, Scores und Validierungsstatus. |
| `RuleCandidate` | Aus mehreren Pfaden destillierter Regelvorschlag mit Parameter-Relationen, Beispielen und unterstützenden Transformations-IDs. |
| `ReusableRule` | Persistierte, wiederverwendbare Variante eines validierten Kandidaten samt `canonicalHash` und Nutzungs-Metadaten. |
| `ExportBundle` | Selbstbeschreibendes Paket aus Transformationen, Kandidaten und wiederverwendbaren Regeln für Export/Import. |

## Tests

```bash
./gradlew test
```

Die Tests dokumentieren bewusst die Stärken des AST-Ansatzes:

- `AstRewriteTransformationEngineTest` zeigt lokale Rewrites an beliebigen und verschachtelten Teilbäumen.
- `ExpressionParserTest` schützt Operatorpräzedenz und Formatierung, insbesondere Unary-Minus und verschachtelte Potenzen.
- `RuleCandidateMinerTest` prüft, dass Kandidaten aus validierten Pfaden und nicht aus bekannten Regelnamen entstehen.
- `AppTest` deckt robuste CLI-Fehlerbehandlung ab.

Optionaler Neo4j-Store per Umgebungsvariablen:

- `NEO4J_URI`
- `NEO4J_USER`
- `NEO4J_PASSWORD`

## Eingabe von Ausdrücken

Die Anwendung akzeptiert Terme, Gleichungen und Gleichungssysteme als Strings.
Terme nutzen explizite Multiplikation, z. B. `x^2 + 2*x + 1`.
Gleichungen werden mit `=` getrennt, Gleichungssysteme mit `;` oder Zeilenumbrüchen.

## AST-Rewrite-Engine

Die Klasse `AstRewriteTransformationEngine` implementiert `TransformationEngine`
und wendet `RewriteRule`-Instanzen rekursiv auf jeden Teilbaum eines Ausdrucks
an. Dadurch entstehen Suchgraph-Kanten aus allgemeinen lokalen Regeln, statt aus
hart codierten quadratischen Sonderfällen.

Das Pattern-System bindet strukturelle Platzhalter an beliebige AST-Teilbäume:

- `PatternExpr.var("A")` bindet einen Teilbaum und erzwingt bei Wiederholung dieselbe Struktur.
- `PatternExpr.op(ADD, A, B)` beschreibt Operator-Muster.
- `PatternRewriteRule` instanziiert Zielmuster aus den gefundenen Bindings.

Aktuell enthaltene Basisregeln sind absichtlich atomar. Es gibt keine direkte
Schulbuchregel für binomische Formeln, Differenz von Quadraten oder quadratische
Ergänzung. Erlaubte Spielzüge sind z. B.:

- `A^2 -> A*A`
- `A*(B + C) -> A*B + A*C`
- `(B + C)*A -> B*A + C*A`
- `A*B + A*C -> A*(B + C)`
- `A + A -> 2*A`
- `A*A -> A^2`
- `(A^m)*(A^n) -> A^(m+n)`
- `(A^m)^n -> A^(m*n)`
- neutrale und absorbierende Elemente wie `A + 0`, `A * 1`, `A * 0`

Diese Regeln gelten für beliebige AST-Teilbäume, nicht nur für einzelne Variablen
oder Integer. Bekannte Formeln dürfen nicht als Spielzüge eingebaut sein; sie
müssen als Muster aus vielen kleinen allgemeinen Spielzügen entstehen. Beispiel:

```text
(x+a)^2
-> (x+a)*(x+a)
-> x*(x+a) + a*(x+a)
-> x*x + x*a + a*x + a*a
-> x^2 + 2*a*x + a^2
```

`RewriteRule` trägt Metadaten (`RewriteKind`, Komplexitätsrisiko,
geschätztes Kosten-Delta und Äquivalenzstatus), damit die Suche expandierende
oder potenziell teure Regeln gezielt begrenzen kann. Quadratische Analyzer dürfen
weiterhin für Scoring, Äquivalenz-Fallbacks, Tests und bekannte Baselines
existieren. Sie werden aber nicht als direkte Transformationslogik verwendet.

## Suche und Graph

Die Transformationssuche erzeugt aus jedem Ausdruck Folgezustände primär durch
lokale AST-Rewrite-Regeln. Regeln werden nicht nur am Wurzelausdruck, sondern
rekursiv an jedem Teilbaum ausprobiert. Jeder Ausdruckszustand wird als Knoten
gespeichert, jede angewendete Umformung als gerichtete Kante mit Regelname,
Tiefe und Score-Verbesserung. Der Graph speichert konkrete Suchpfade und nicht
nur Endergebnisse.

Die Suche läuft über eine `SearchStrategy`-Schnittstelle. Implementiert sind
`BestFirstSearchStrategy`, `BeamSearchStrategy`, `RandomMonteCarloSearchStrategy`
und eine A*-ähnliche `AStarSearchStrategy`; einfache BFS ist nicht mehr die
zentrale Suchlogik. Ein Zustand enthält Ausdruck, Tiefe, Score, Pfad,
angewendete Regel-IDs, Anzahl expandierender Schritte und kanonischen Hash.
Bewertet werden u. a. Ausdruckskomplexität, AST-Größe, Operatoranzahl,
Verschachtelung, bisherige Tiefe, expandierende Schritte, Regelvielfalt und
Expansion ohne Verbesserung.

Der Suchraum wird durch Heuristiken begrenzt: maximale Suchtiefe, maximale Anzahl
besuchter Strukturen, maximale AST-Größenzunahme pro Schritt, keine Wiederholung
derselben Regel auf demselben kanonischen Teilbaum im selben Pfad, maximale
Anzahl expandierender Schritte und maximale Kandidaten pro Zustand. Das ist
notwendig, weil algebraisch äquivalente Umformungen sehr schnell zyklische oder
exponentiell wachsende Suchräume bilden.
Der Speicher kann lokal im Arbeitsspeicher oder über Neo4j erfolgen. Graph-Kanten
speichern neben Quelle, Ziel, Tiefe und Verbesserung auch konkrete Pfad-IDs,
kanonische Hashes, Scores, Regelart, Regelkosten, Komplexitätsrisiko,
Äquivalenz-Metadaten und Validierungsstatus.

Gefundene Verbesserungen können zusätzlich als vollständige
`DiscoveredTransformation` gespeichert werden. Eine solche Entdeckung enthält
Ausgangsausdruck, verbesserten Ausdruck, vollständige `TransformationStep`-Liste,
Scores vor/nach der Umformung, Gesamtverbesserung, Proof-Status,
Entdeckungszeitpunkt und kanonischen Hash. Ein `TransformationStep` beschreibt
einen einzelnen Rechenschritt mit Vorher-/Nachher-Ausdruck, angewendeter Regel,
`RewriteKind`, Scores und Erklärung. Dadurch lässt sich nicht nur das Ergebnis,
sondern der gesamte mathematische Rechenweg nachvollziehen.

`RuleCandidate` beschreibt weiterhin einen aus Beispielen geminten abstrakten
Regelkandidaten inklusive Parameterrelationen und Validierungsstatus.
`ReusableRule` ist dagegen ein dauerhaft gespeicherter Regelvorratseintrag, der
aus ausreichend validierten Kandidaten entstehen kann. In den Regelvorrat werden
nur Regeln übernommen, die mindestens den konfigurierten Proof-Status erreichen
und genügend unterstützende Beispiele bzw. Verbesserungssignal besitzen.

Der Regelvorrat kann über `RuleInventoryRepository` im Speicher oder in Neo4j
geführt werden. Optional können wiederverwendbare Regeln über
`InventoryBackedRewriteRuleProvider` als Rewrite-Regeln aktiviert werden:

```properties
regelsuche.rules.inventory.enabled=true
regelsuche.rules.inventory.minProofStatus=VALIDATED_BY_EXAMPLES
```

Aktiviert werden nur Regeln mit ausreichendem Proof-Status, eindeutiger ID, ohne
Konflikt mit bestehenden Regeln und ohne offensichtliches Suchraumexplosionsrisiko.

## Mathematische Darstellung und Export

`MathRenderer` stellt Ausdrücke, einzelne Schritte und vollständige
Umformungswege dar. Implementiert sind Plain-Text-, Markdown- und LaTeX-Renderer.
`TransformationExportService` exportiert nachvollziehbare Entdeckungen als:

- `exports/discovered-transformations.md`
- `exports/rule-inventory.json`
- `exports/transformation-graph.mmd`

Markdown- und LaTeX-Ausgaben zeigen Ausgang, Rechenweg, Verbesserung, Score und
Status. Der Mermaid-Export visualisiert die konkreten Schritte als Graph, z. B.
`A["(x+3)^2"] -->|power_to_product| B["(x+3)(x+3)"]`.

Für spätere UI-/API-Schichten existieren DTOs und Query-Services für alle
gefundenen Verbesserungen, beste Verbesserungen, Verbesserungen zu einem
Ausdruck, Rechenwege, Regelkandidaten, wiederverwendbare Regeln und
Graphansichten einzelner Pfade.

`SearchBenchmark` vergleicht Strategien über Metriken wie explorierte Zustände,
beste Verbesserung, kürzeste Verbesserungstiefe, expandierende Schritte und
Regelvielfalt. Diese Benchmarks sind bewusst leichtgewichtig und dienen dazu,
Suchqualität und Suchraumexplosion reproduzierbar sichtbar zu machen.


## Normalform und Kanonisierung

`ExpressionCanonicalizer` vereinheitlicht Ausdrücke für `visited`-Mengen,
Anti-Zyklen und Graph-Vergleiche:

- kommutative Operatoren werden stabil sortiert (`b+a == a+b`)
- verschachtelte Additionen und Multiplikationen werden geflattet
- neutrale Elemente werden entfernt (`x*1 == x`)
- Zahlen werden normalisiert und konstant gefaltet (`2+3 == 5`, `3*4 == 12`)
- wiederholte Faktoren werden zusammengeführt (`x*x == x^2`)
- Polynom-Normalform: Monome nach absteigendem Grad sortiert
  (`1 + 2*x + x^2 → x^2 + 2*x + 1`)
- stabile Hashes markieren bereits gesehene Strukturen

Assumption-bewusste Reduktionen wie `x/x → 1` (nur unter `x ≠ 0`) sind
**Opt-in** über `canonicalizeWith(expr, AssumptionContext)` bzw.
`stableHashWith(expr, AssumptionContext)`. Die gesammelten Assumptions
fließen als Fingerprint in den Hash ein, damit Transposition-Treffer mit
aktiven Assumptions nicht mit assumption-freien Einträgen verschmelzen.

Die Kanonisierung ersetzt keine Regelentdeckung. Sie kontrolliert Suche und
Duplikate; die konkreten Graph-Kanten bleiben die kleinen Rewrite-Schritte.

Die nächsten Schritte Richtung mathematische Suchintelligenz (Equality
Saturation, Cost Models, Global Memory, weitere Domänen, Proof-Integration)
sind in [`docs/search-intelligence-roadmap.md`](docs/search-intelligence-roadmap.md)
beschrieben.

## Bewertung und Äquivalenz

Ausdrücke werden anhand einer Score-Struktur bewertet:

- String-Länge
- AST-/Token-Knoten
- Operatoranzahl
- Verschachtelungstiefe
- Bonus für erkannte Strukturen wie Quadrat-, Produkt- oder Faktorform

Äquivalenz wird primär über SymPy geprüft (`simplify(lhs - rhs) == 0`). Die
GraalVM-Polyglot-Ausführung verwendet keinen Host-All-Access-Kontext; Eingaben
werden vor der Übergabe durch den eigenen Parser auf die unterstützte
Ausdrucksgrammatik begrenzt. Falls die Python-Laufzeit nicht verfügbar ist,
werden deterministische numerische Stichproben und die lokale Normalisierung für
unterstützte quadratische Muster verwendet.

`RewriteRuleValidationService` validiert konkrete Rewrite-Anwendungen gegen das
`EquivalenceService`. Zusammen mit `RandomExpressionGenerator` entstehen
property-artige Tests: viele zufällige algebraische Ausdrücke werden erzeugt,
alle erreichbaren Rewrite-Schritte werden geprüft, und die Kanonisierung muss
bedeutungserhaltend sein. Diese Tests sind keine formalen Beweise, erhöhen aber
die systematische Abdeckung der Regelkorrektheit.

## Regel-Kandidaten

Der Beispielgenerator erzeugt viele konkrete Testausdrücke. Er deckt weiterhin
quadratische Muster ab und enthält zusätzlich mehrvariable und höhergradige
Polynomformen, z. B.:

- `(x + a)^2`
- `(x - a)^2`
- `(x + a)*(x - a)`
- `x^2 + b*x + c`
- `a*x^2 + b*x + c`
- `x^2 + 2*a*x + a^2`
- `x^2 - 2*a*x + a^2`
- `x^2 + 2*a*x`
- `x^2 - 2*a*x`

Für erfolgreiche, äquivalente und besser bewertete Umformungspfade speichert das
System Ausgangsausdruck, Zielausdruck, Pfad, Regeln, Scores, Äquivalenznachweis
und Variablenstruktur.

Der aktuelle Mining-Ansatz besteht aus drei Schritten:

1. **AST-Normalisierung:** Quelle und Ziel jedes erfolgreichen Pfads werden
   geparst. Kommutative Operatoren wie `+` und `*` werden sortiert, neutrale
   Elemente entfernt, Variablennamen vereinheitlicht, Zahlenliterale
   normalisiert und daraus stabile kanonische Strings erzeugt.
2. **Anti-Unification:** `PatternGeneralizer` vergleicht mehrere konkrete
   AST-Paare desselben Strukturclusters und ersetzt abweichende Zahlen durch
   Platzhalter. So wird z. B. aus `(x + 1)^2`, `(x + 3)^2`, `(x + 5)^2`
   zunächst `(x + A)^2`.
3. **Parameter-Relation-Mining:** `ParameterRelationMiner` erkennt einfache
   numerische Beziehungen zwischen Platzhaltern, z. B. `B = 2*A`,
   `C = A^2` oder `C = -A^2`. Daraus entsteht ein Kandidat wie
   `x^2 + 2*A*x + A^2 -> (x + A)^2`.

Ein Cluster wird erst dann zu einem Regelkandidaten, wenn mindestens drei
konkrete Beispiele dieselbe abstrakte Struktur und dieselben
Parameterbeziehungen unterstützen. Danach erzeugt das System frische
Testinstanzen mit bisher nicht genutzten Zahlenwerten und prüft sie mit dem
`EquivalenceService`. Kandidaten, die diese Validierung nicht bestehen, werden
verworfen.

Jeder Kandidat trägt zusätzlich einen `CandidateProofStatus`:

- `OBSERVED`: aus Suchpfaden beobachtet, aber noch nicht frisch validiert
- `VALIDATED_BY_EXAMPLES`: an zusätzlichen Beispielen validiert
- `SYMBOLICALLY_VERIFIED`: durch symbolische CAS-Prüfung bestätigt
- `FORMALLY_PROVED`: reserviert für spätere SMT-/Theorem-Prover-Anbindung

Der aktuelle Code erreicht je nach Umgebung und Muster `VALIDATED_BY_EXAMPLES`
oder `SYMBOLICALLY_VERIFIED`; `FORMALLY_PROVED` ist bewusst noch ein zukünftiger
Integrationspunkt.

Anti-Duplikation erfolgt durch kanonisierte Variablennamen und einen Hash des
abstrahierten Musters. Wenn ein neuer Kandidat entdeckt wird, erzeugt die
asynchrone Suche ein `RuleCandidateDiscoveredEvent`.

Wichtig ist die klare Trennung:

- **Rewrite-Regeln** sind kleine erlaubte Spielzüge.
- **Suchstrategie** erzeugt konkrete Pfade aus diesen Spielzügen.
- **RuleCandidateMiner** abstrahiert erfolgreiche Pfade per Normalisierung,
  Anti-Unification, Parameter-Relationen und frischer Validierung.
- **KnownRuleRepository** ordnet Kandidaten nur ein; es entdeckt und löst nichts.
- **Regelkandidat** ist ein validiertes, plausibles abstrahiertes Muster.
- **Bewiesene mathematische Regel** erfordert weiterhin einen separaten Beweis;
  die Suche ist heuristisch, nicht vollständig, und garantiert keine
  mathematische Neuheit oder Vollständigkeit.

## Bekannte Regeln als Referenz

Bekannte Regeln liegen als Baseline vor, damit gefundene Kandidaten eingeordnet
werden können, z. B. als bekannte binomische Formel. Sie werden nicht als
direkter Suchschritt verwendet.

**Die bekannten Regeln dürfen als Test- und Referenzbestand existieren, aber
nicht als direkte Suchlösung verwendet werden.**

**Keine Regel darf durch ihren bekannten Namen oder durch eine Spezialmethode
erkannt werden; sie muss aus konkreten Transformationspaaren generalisiert und
an neuen Beispielen validiert werden.**

Das System kann Regel-Kandidaten plausibel rekonstruieren, garantiert aber
nicht, dass ein Kandidat mathematisch neu oder vollständig allgemein bewiesen
ist. Die Baseline dient nur dem Vergleich und der Statusmeldung.

## Forschungsplattform und Grenzen

Der Prototyp ist zu einer belastbareren Forschungsplattform erweitert worden:

- Property-artige Rewrite-Validierung gegen viele deterministisch zufällige
  Ausdrücke
- Zufallsgenerator für algebraische Ausdrücke und höhergradige Polynom-Beispiele
- CAS-/SymPy-Validierung mit numerischem Fallback, wenn SymPy nicht verfügbar ist
- mehrere Suchstrategien inklusive Best-First, Beam, Random/Monte-Carlo und A*
- Proof-Status-Lifecycle für Regelkandidaten
- Graph-Persistenz vollständiger Suchpfad-Metadaten
- reproduzierbare Suchbenchmarks für Qualität und Explosion

## Suchintelligenz (Transposition Table & Lern-Loop)

Die Suchplattform wurde um eine mathematische **Transposition Table** und einen
**lernenden Regelvorrat** ergänzt. Ziel ist es, dass Folgesuchen messbar
intelligenter werden – analog zu einem Schachcomputer, der gleiche Stellungen
wiedererkennt.

### Transposition Table (`de.regelsuche.search.memory`)

Die Tabelle indiziert besuchte Zustände über `ExpressionCanonicalizer.canonicalHash(...)`
und hält pro Eintrag `bestScore`, `minDepthSeen`, `bestKnownPathId`,
`reachedByRuleIds` und `visitCount`. Drei Implementierungen folgen dem in
`PersistenceContext` etablierten Modus:

| Persistenz   | Implementierung                  | Default-Pfad                      |
| ------------ | -------------------------------- | --------------------------------- |
| `IN_MEMORY`  | `InMemoryTranspositionTable`     | nur Prozessspeicher               |
| `JSON_FILE`  | `JsonFileTranspositionTable`     | `./data/regelsuche/transposition.json` |
| `REMOTE_NEO4J` | `Neo4jTranspositionTable`      | wiederverwendet `Driver`/`Session` |

### Explainable Pruning

Vor `expand()` prüft jede Strategie über `TranspositionGate` die Tabelle. Re-Visits
sind erlaubt, wenn (a) geringere Tiefe, (b) besserer Score oder (c) ein neuer
Eintrag in `reachedByRuleIds` vorliegt; andernfalls wird der Zustand mit einem
`PruningDecision` protokolliert:

| Reason                | Bedeutung                                                |
| --------------------- | -------------------------------------------------------- |
| `ALREADY_KNOWN_BETTER`| Bekannter Zustand mit besserem Score                     |
| `ALREADY_KNOWN_EQUAL` | Bekannter Zustand, kein Mehrwert (klassischer Zyklus)    |
| `REPLACED_WORSE_PATH` | Neuer Pfad ersetzt schlechteren                          |
| `KEPT_NEW_RULE_COMBO` | Re-Visit beibehalten, weil neue Regelkombination         |
| `KEPT_LOWER_DEPTH`    | Re-Visit beibehalten, weil kürzerer Pfad                 |
| `BUDGET_EXCEEDED`     | Suchbudget aufgebraucht                                  |

Die Entscheidungen werden via `JsonWriter` an `/api/search`-Antworten,
`search-analysis-report.json` und einen neuen Bundle-Eintrag
`pruning-decisions.json` angehängt.

### Suchprofil `DISCOVERY_PLUS`

Neues Profil mit aktiver Transposition Table, positiv gewichteten Mining-Signalen
und höherer Pfaddiversität. Die bestehenden Profile (`FAST_SIMPLIFY`, `DISCOVERY`,
`TEACHING`, `PROOF_ORIENTED`, `EXHAUSTIVE_SMALL`) bleiben unverändert; die Tabelle
ist dort optional und per Default aus, damit alle bisherigen `transform`-Tests
stabil weiterlaufen.

### Lern-Loop (`MacroRuleLearningService`)

Nach jedem Suchlauf werden über den bestehenden `RuleCandidateMiner`
Makro-Kandidaten anti-unifiziert. `ReusableRule` führt jetzt `occurrenceCount`,
`averageImprovement`, `supportingPathIds` und `confidenceScore`; alte
`rule-inventory.json`-Dateien werden ohne diese Felder migriert (Default `0`).
Ab `confidenceScore ≥ 0.8` und `occurrenceCount ≥ 3` aktiviert der Service die
Regel automatisch im `RuleInventoryRepository`, sodass die nächste Suche sie über
`InventoryBackedRewriteRuleProvider` als Schritt einsetzen kann.

### Demo „System lernt eine Makroregel"

`POST /api/demo/macro-learning` führt vier Suchläufe nacheinander aus –
`(x+1)^2`, `(x+2)^2`, `(x+3)^2`, `(x+7)^2`. Die Antwort liefert pro Schritt
Schrittanzahl, Laufzeit, `confidenceScore`-Verlauf und das Flag
`usedLearnedRule`, das anzeigt, ob der vierte Lauf die gelernte
binomische Regel als Schritt nutzt. Im UI ist die Demo als fünfter Button
zugänglich; ein eigener Summary-Renderer zeigt die „vorher / nachher"-Tempo.

### UI-Tab „Suchgedächtnis"

Neuer Reiter mit drei Sektionen: bekannte Zustände (Ausdruck, Hash, `visitCount`,
`bestScore`, `bestKnownPathId`), Pruning-Entscheidungen mit Filter nach
`PruningReason` und gelernte Makroregeln. Daten kommen aus
`/api/memory/states`, `/api/memory/pruning` und `/api/memory/macros`.

### Nächste Schritte: mathematische Suchintelligenz

Die Roadmap für die nächsten sechs Schritte (Strong Canonicalization
geliefert; E-Graphs, Cost Models, weitere Domänen, Proof-Integration,
Global Memory ausstehend) ist in
[`docs/search-intelligence-roadmap.md`](docs/search-intelligence-roadmap.md)
dokumentiert.

Die Grenzen bleiben explizit:

- Suche ist heuristisch und nicht vollständig.
- Kandidaten sind keine bewiesenen neuen Regeln.
- Beispielvalidierung kann Fehler finden, ersetzt aber keinen Beweis.
- Symbolische CAS-Prüfung ist stärker, aber noch kein formaler Theorem-Prover.
- `FORMALLY_PROVED` erfordert eine spätere SMT- oder Theorem-Prover-Anbindung.
