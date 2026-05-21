# Search Intelligence Roadmap

Diese Datei dokumentiert die sechs eigenständigen Folge-PRs, die die
Suche von „lernende Rewrite-Schleife" hin zu „mathematische
Suchintelligenz" weiterentwickeln. Die Reihenfolge ist nach Hebelwirkung
und technischer Abhängigkeit sortiert.

Stand: **PR 1, PR 3 und PR 6 sind umgesetzt**, PRs 2, 4 und 5 sind als Folge-PRs offen.

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

## PR 3 — Cost Models & TransformationGoal &nbsp;✅ geliefert

Geliefert in `de.regelsuche.scoring.cost`:

* Interface `CostModel` mit stabiler `id()` für UI/JSON-Export und einer
  Convenience-`cost(expression, canonicalizer, score)`-Overload, die das
  Parsing kapselt.
* Sechs Implementierungen:
  * `OperatorCountCost` — historischer Default, zählt AST-Operatoren;
  * `DepthCost` — bevorzugt flache Strukturen;
  * `FactoredFormCost` — belohnt Multiplikationen nicht-trivialer
    Teilausdrücke und bestraft Top-Level-Summanden;
  * `NumericStabilityCost` — bestraft Subtraktion ähnlicher Operanden
    (Auslöschung), Division durch potenziell kleine Werte, hohe Potenzen;
  * `TeachingFriendlinessCost` — bevorzugt kleine Koeffizienten, geringe
    Tiefe, vermeidet Division und exotische Funktionen;
  * `SymmetryCost` — Kommutativ-Bonus (gleich lange Operanden in
    AC-Gruppen), Palindrom-Bonus (z. B. `a + b + a`).
* Enum `TransformationGoal { SIMPLIFY, FACTORIZE, NUMERICALLY_STABLE,
  PROOF_FRIENDLY, TEACHING_FRIENDLY }` mit `defaultCostModel()`.
* `SearchProblem` trägt jetzt ein optionales `CostModel`-Feld
  (API-rückwärtskompatibel über zwei zusätzliche Konstruktoren) plus
  `withCostModel(...)` und `withGoal(...)`-Convenience-Methoden.
* `BestFirstSearchStrategy.priority(state, problem)` und
  `AStarSearchStrategy.priority(state, problem)` konsultieren das
  Cost-Model statt der rohen `weightedTotal()`-Summe, wenn eines gesetzt
  ist; ohne Cost-Model bleibt das alte Verhalten exakt erhalten.
* `SearchProfile` trägt jetzt ein `defaultGoal()`, sodass die UI für jedes
  Profil eine sinnvolle Goal-Voreinstellung anzeigen kann
  (`TEACHING → TEACHING_FRIENDLY`, `PROOF_ORIENTED → PROOF_FRIENDLY`, ...).

Tests:

* `TransformationGoalTest` — pro Goal ein „A ist strikt besser als B"-Beispiel
  plus stabile `id()`-Kontrakte.
* `GoalAwareSearchTest` — verifiziert, dass das Cost-Model die
  BestFirst-Reihenfolge tatsächlich beeinflusst, jeder Goal die Suche
  steuern kann und die Legacy-Konstruktoren weiter funktionieren.

Offen für eine kleine Folge-Iteration: das UI-Dropdown im
`WebWorkbenchServer` (REST-Feld `goal` auf `/api/search`,
`<select>`-Element neben dem bestehenden Profil-Dropdown). Die
Server-/Search-Seite ist bereits vollständig vorbereitet.

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

## PR 6 — Global Mathematical Memory &nbsp;✅ geliefert

Geliefert (additive, keine API-Brüche):

* `GlobalMemoryService` in `de.regelsuche.search.memory` als zentraler
  Einstieg für sessionübergreifende Statistik auf der
  `TranspositionTable`.
* `SCHEMA_VERSION`-Konstante; `JsonFileTranspositionTable.persist()`
  schreibt jetzt `"schemaVersion": 1` in den JSON-Header.
  Bestehende `transposition.json`-Dateien ohne Version werden weiterhin
  gelesen (das `hydrate()`-Parsing ignoriert unbekannte Felder).
* `garbageCollect(minVisits, retainOlderThan, now)` — entfernt nur
  Einträge, die **beide** Kriterien erfüllen (selten *und* alt). Persistenz
  wird beim JSON-Backend automatisch durchgereicht.
* `universalityScore(entry, now)` — Diversität der erreichenden
  Regelpfade dominiert (Faktor 10), Visit-Count ist auf 20 gedeckelt,
  damit eine einzige populäre syntaktische Variante keinen wirklich
  universellen Knoten verdrängt; Recency liefert leichten Bonus.
* `topUniversalPatterns(limit, now)` — deterministisches Ranking,
  Tiebreaker per `canonicalHash`.
* `ruleCoverage()` — Map `ruleId → distinct states reached`, in
  absteigender Frequenz, geeignet für die geplante UI-Sektion
  „universelle Muster".
* `InMemoryTranspositionTable.remove(hash)` — neue, additive Methode,
  vom JSON-Backend mit Persistenz überschrieben.

Tests: `GlobalMemoryServiceTest` deckt Score-Gewichtung, Top-Ranking,
Rule-Coverage, GC-Selektivität und JSON-Roundtrip (inkl. Schema-Version
und persistenter GC) ab.

Offen für eine kleine Folge-Iteration: UI-Sektion „universelle Muster"
im Suchgedächtnis-Tab des `WebWorkbenchServer` — Service-Seite ist
vollständig vorhanden.

## Empfohlene Reihenfolge

`1` (erledigt) → `3` (erledigt) → `2` → `6` (erledigt) → `4a/4b/…` → `5`.

Begründung:

* PR 1 ist Fundament — wirkt sich auf alles Folgende aus.
* PR 3 ist klein, gibt aber sofort sichtbares Verhalten.
* PR 2 ist groß, profitiert massiv von 1 und 3.
* PR 6 ohne 1 verfrüht (siehe oben).
* PRs 4 und 5 orthogonal und können danach in beliebiger Reihenfolge.
