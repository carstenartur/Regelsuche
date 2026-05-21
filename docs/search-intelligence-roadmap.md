# Search Intelligence Roadmap

Diese Datei dokumentiert die sechs eigenständigen Folge-PRs, die die
Suche von „lernende Rewrite-Schleife" hin zu „mathematische
Suchintelligenz" weiterentwickeln. Die Reihenfolge ist nach Hebelwirkung
und technischer Abhängigkeit sortiert.

Stand: **PR 1 ist umgesetzt**, PRs 2–6 sind als Folge-PRs offen.

## PR 1 — Strong Canonicalization &nbsp;✅ geliefert

Bessere Kanonisierung ist der Hebel mit dem höchsten Effekt pro
Komplexität: Suchraumgröße, Wiedererkennung, Lernfähigkeit, Performance
hängen alle direkt daran.

Geliefert in `de.regelsuche.canonical.ExpressionCanonicalizer`:

* **AC-Normalisierung** für `+` und `*` (Flatten + sortierte Operanden).
* **Konstantenfaltung** (`2+3 → 5`, `3*4 → 12`, `x*1 → x`,
  `2 + x + 3 → x + 5`).
* **Polynom-Normalform**: Monome werden nach absteigendem Grad sortiert,
  bei Gleichstand lexikographisch (`1 + 2*x + x^2 → x^2 + 2*x + 1`,
  `x^2 + x^10 → x^10 + x^2`).
* **Assumption-bewusste Reduktionen** als Opt-in über
  `canonicalizeWith(...)`/`stableHashWith(...)` mit
  `AssumptionContext`:
  * `x/x → 1` unter `x ≠ 0`
  * `0/d → 0` unter `d ≠ 0`
  * `(a*x)/x → a` unter `x ≠ 0`

  Die Hashfunktion `stableHashWith` mischt einen stabilen Fingerprint
  über die gesammelten Assumptions in den SHA-256 ein, sodass
  Transposition-Table-Treffer mit aktiven Assumptions nicht mit
  assumption-freien Einträgen verschmelzen — die Korrektheitsgarantie,
  die der Plan einfordert, ohne dass `TranspositionEntry` ein extra Feld
  braucht.

Tests in `ExpressionCanonicalizerTest`:

* AC- und Assoziativitäts-Kollaps,
* Polynom-Grad-Sortierung,
* Default-DIV bleibt assumption-frei,
* Assumption-aware Reduktionen liefern die richtigen `Assumption.nonZero(...)`,
* Hash-Fingerprint trennt assuming/non-assuming Hashes,
* Hash-Kollisions-Baseline für `a+b+c`-Varianten kollabiert auf 1 Hash,
* Property-Test über `RandomExpressionGenerator`: kanonische Form ist
  Fixpunkt unter erneuter Kanonisierung.

## PR 2 — E-Graphs / Equality Saturation

Größter Architekturschritt, profitiert massiv von PR 1.

Vorgeschlagene Struktur:

* Neues Paket `de.regelsuche.egraph` mit `EGraph`, `EClass`, `ENode`,
  Union-Find, Congruence Closure, Rebuild-Logik.
* Saturierung mit den vorhandenen `RewriteRule`s als egg-artige
  `Searcher`/`Applier`-Adapter (Pattern-Match + bedingte Anwendung).
* Extraktion über pluggable Cost-Funktion (Schnittstelle siehe PR 3).
* Integration als neue `SearchStrategy` `EqualitySaturationStrategy`,
  koexistiert mit BestFirst/Beam/AStar/MCTS; standardmäßig aus,
  Opt-in über `SearchProfile.EQUALITY_SATURATION` oder ein neues Flag.

Akzeptanzkriterien:

* Saturierung verschmilzt `(a+b)*c` und `a*c + b*c` in dieselbe E-Class.
* Auf den bestehenden Demo-Beispielen skaliert die Knotenzahl messbar
  besser als `BeamSearchStrategy` (Benchmark via `SearchBenchmark`).

## PR 3 — Cost Models & TransformationGoal

Kleines API, aber sofort sichtbare „intelligentere" Entscheidungen.

* Interface `CostModel` mit Implementierungen:
  * `OperatorCountCost` (heute implizit),
  * `DepthCost`,
  * `FactoredFormCost` — bevorzugt faktorisierte Formen,
  * `NumericStabilityCost` — vermeidet Subtraktion ähnlich großer
    Floats / Division durch kleine Werte,
  * `TeachingFriendlinessCost` — bevorzugt Schulnotation,
  * `SymmetryCost` — bevorzugt symmetrische Strukturen.
* Enum `TransformationGoal { SIMPLIFY, FACTORIZE, NUMERICALLY_STABLE,
  PROOF_FRIENDLY, TEACHING_FRIENDLY }` — wählt Default-Cost und
  Heuristik in `AStar`/`BestFirst`.
* UI: Goal-Dropdown neben Profil; in `search-analysis-report.json`
  als Feld `goal` mitgeführt.

Akzeptanzkriterien: Pro Goal mindestens ein Beispiel mit klarer
„Ergebnis A besser als Ergebnis B"-Aussage.

## PR 4 — Größere mathematische Domänen (in Sub-PRs splitten)

Jeder Schritt eigener PR samt eigener Demo + Tests.

* **4a — Gleichungen/Ungleichungen**: `Equation`/`Inequality`-Knoten
  (Equation existiert teilweise), Balancing-Rules, Schul-Demos.
* **4b — Ableitungen**: `Derivative`-Knoten, Standard-Ableitungsregeln,
  Kettenregel über Pattern-Match.
* **4c — Integrale / komplexe Zahlen / Matrizen / Vektoren**: jeweils
  eigener PR — sonst wird die Domain-Erweiterung ein Monster.

## PR 5 — Serious Proof Integration

* Lean-Bridge: Tactic-Export für eine `RewriteRule`.
* `RewriteRuleValidationService` ruft den Proof-Worker asynchron auf
  und hebt den Status auf `FORMALLY_PROVED`, sobald der externe Prover
  bestätigt.
* Job-Queue für lange Beweise.
* Infrastruktur (Lean-Installation, Cache, Worker-Container) explizit
  dokumentieren — wahrscheinlich blockiert durch externes Setup.

## PR 6 — Global Mathematical Memory

Sinnvoll **nach** PR 1, sonst zählt der Speicher syntaktische Varianten
doppelt (mit PR 1 sind die Varianten bereits zusammengefasst).

* Long-Running-Persistenz für `TranspositionTable` und
  `RuleInventoryRepository` über Suchläufe und Sessions hinweg.
* Schema-Versionierung für `transposition.json` (Migrationspfad).
* Garbage Collection seltener Knoten (z. B. Visit-Count-Threshold).
* Universalitäts-Score: Wie oft (in wie vielen unterschiedlichen
  Aufgaben) wurde dieser Knoten besucht?
* Statistik-API: „in wie vielen Läufen erschien diese Struktur",
  „welche Regelkombinationen sind cross-task nützlich".
* UI-Sektion „universelle Muster" im Suchgedächtnis-Tab.

## Empfohlene Reihenfolge

`1` (erledigt) → `3` → `2` → `6` → `4a/4b/…` → `5`.

Begründung:

* PR 1 ist Fundament — wirkt sich auf alles Folgende aus.
* PR 3 ist klein, gibt aber sofort sichtbares Verhalten.
* PR 2 ist groß, profitiert massiv von 1 und 3.
* PR 6 ohne 1 verfrüht (siehe oben).
* PRs 4 und 5 orthogonal und können danach in beliebiger Reihenfolge.
