# Limits & bewusst nicht implementiert

## Was geht (Stand jetzt)

- **Web-Workbench hardenable.** Der eingebettete `HttpServer` läuft standardmäßig ohne Auth/TLS, kann aber per `WebSecurityConfig` mit HTTP Basic Auth und TLS (PKCS12-Keystore) hochgezogen werden – plus konfigurierbarer Request-Größen-Cap. Siehe [`docs/web-workbench-security.md`](web-workbench-security.md).
- **Proof-Bridge und optionale Prover-Ausführung.** `LeanProofBridge` und `SmtProofBridge` erzeugen Lean-4- bzw. SMT-LIB-2-Artefakte. `ProofBridgeService` kann diese Artefakte optional über einen konfigurierten `ProverExecutor` an Lean, Z3 oder cvc5 übergeben. Ohne bestätigten Prover-Lauf bleibt der Status höchstens `FORMALLY_PROVABLE`; `FORMALLY_PROVED` wird ausschließlich nach `PROVER_CONFIRMED` gesetzt. Siehe [`docs/proof-bridge.md`](proof-bridge.md) und [`docs/proof-workbench.md`](proof-workbench.md).
- **Inventar-Persistenz.** `InMemoryRuleInventoryRepository.persistTo(file)` / `loadFrom(file)` schreibt/liest enabled/tags plus alle Rule-Felder als JSON. Das `Neo4jRuleInventoryRepository` speichert enabled/tags zusätzlich als Knoten-Properties.
- **Symbolische Nebenbedingungen.** `Assumption` + `AssumptionContext` modellieren Vorbedingungen; `RewriteRule.assumptions(subtree)` ist ein Erweiterungspunkt. `RationalRules`, `LogarithmicRules`, `RadicalRules`, `TrigonometricRules` und `CalculusBasicRules` emittieren konkrete `NON_ZERO`/`POSITIVE`/`NON_NEGATIVE`-Assumptions. Siehe [`docs/assumptions.md`](assumptions.md).
- **Jobsteuerung.** `SearchJobManager` mit `submit/pause/resume/cancel/checkpoint/restore` für persistente, abbrechbare Suchläufe. Siehe [`docs/job-control.md`](job-control.md).
- **Suchraumabschätzung.** `SearchSpaceEstimator` schätzt aus den pro Tiefe entdeckten Frontier-Größen die Anzahl bekannter Zustände, die Wachstumsrate, die erwartete Suchraumgröße und ein Explosionsrisiko (`LOW|MODERATE|HIGH|EXPLOSIVE`) inkl. Klartext-Warnung. Siehe [`docs/job-control.md`](job-control.md#suchraumabschätzung).
- **Modulare mathematische Algorithmen.** `MathematicalAlgorithmRegistry` schaltet Verfahren einzeln (`polynomialEquivalence`, `groebnerBasis`, `jasBackend`, `singularBackend`, `knuthBendix`, `criticalPairs`, `pslq`, `numericRelationSearch`) und enthält Budgets/Semantik pro Verfahren.
- **Proof-vs-Hypothesis strikt getrennt.** `PolynomialNormalFormEquivalenceService` darf für direkte Polynomidentitäten im unterstützten Domain `PROOF` liefern; `GroebnerBasisEquivalenceService` ist davon getrennt und reduziert Polynome modulo kleiner Ideale/Systeme. PSLQ und numerische Relationssuche liefern ausschließlich `HYPOTHESIS`, begrenzte erfolglose Suchen bleiben `UNKNOWN`.
- **Runtime-Konfiguration für mathematische Algorithmen.** Flags können über explizite Properties-Maps (Tests/CLI), JVM-System-Properties (`regelsuche.math.*`) oder Environment Variables (`REGELSUCHE_MATH_*`) gesetzt werden. Präzedenz: explizite Map vor System Properties vor Environment Variables vor Registry-Defaults.
- **Evidence-Provenance.** Symbolic-Regression-Proposals, numerische Relationskandidaten und CAS-Validierungsversuche werden als eigene Provenance-Knoten erfasst, bleiben aber semantisch Discovery-/Validierungsartefakte und kein vollständiger Knowledge-Graph-Ersatz.
- **Counterexample Search als Angriffsmechanismus.** Gegenbeispielsuche liefert
  `COUNTEREXAMPLE_FOUND`, `NO_COUNTEREXAMPLE_FOUND` oder `INCONCLUSIVE`.
  Nur ein gefundenes Gegenbeispiel widerlegt; ein Nicht-Fund ist kein Beweis und
  ein inconclusive Ergebnis darf nicht als robuste Validierung gewertet werden.

## Was bewusst (noch) nicht geht

- **Kein vollwertiges Computer-Algebra-System.** Vereinfachungen entstehen ausschließlich durch Verkettung atomarer Regeln; komplexe Identitäten (binomische Formeln, vollständige quadratische Ergänzung, allgemeine Polynom­division) sind nicht als einzelne Regel kodiert.
- **Gröbner/JAS/Singular nur begrenzt.** Die reine Java-Gröbner-Schicht ist als `pureJavaSmallGroebner` für kleine Polynomideale über Q gedacht. JAS wurde geprüft, aber das auf Maven Central verfügbare Artefakt ist GPL-3.0-or-later und wird nicht in die MIT-lizenzierte Standard-Distribution eingebunden; ein aktiviertes `jasBackend` ohne kompatiblen Adapter meldet `UNAVAILABLE` statt auf Normalform-Identitäten auszuweichen. `singularBackend` bleibt ein externer Prozess-Adapter mit sicherem `UNAVAILABLE`-Default.
- **Keine vollständige trigonometrische/radikale/divisionsbasierte/nichtkommutative Algebra.** Die unterstützten Regel-Domänen liefern lokale Rewrite- und Assumption-Semantik, aber kein allgemeines CAS für diese Bereiche.
- **Keine eingebettete oder garantiert verfügbare Prover-Runtime.** Die Bridges selbst bleiben reine Artefaktgeneratoren. Ein `LeanProofWorker` oder `SmtProofWorker` kann Lean, Z3 beziehungsweise cvc5 als begrenzten externen Prozess starten, aber nur wenn der Worker mit einem `ProverExecutor` konfiguriert und das Werkzeug installiert ist. Der normale Skeleton-Modus bleibt bei `FORMALLY_PROVABLE`; fehlende Werkzeuge, Timeouts, Fehler und Lean-Artefakte mit `sorry` dürfen den Status nicht auf `FORMALLY_PROVED` anheben.
- **Trigonometrie / Logarithmus / Analysis (Basis).** `FunctionExpr` ist im AST, `sin/cos/tan/log/ln/sqrt/exp/abs` werden geparst, formatiert, kanonisiert und durch eigene Regel-Domänen (`trigonometric`, `logarithmic`, `radical`, `calculus_basic`) abgedeckt. Erweiterte Analysis (Ableitungs-/Integraloperatoren, vollständige Identitäten-Datenbank) ist bewusst nicht enthalten.
- **Equation-Domain (Basis).** `EquationRewriteEngine` führt Operationen "auf beide Seiten" als eigene Semantik (`+c`, `*c` mit `c≠0`, injektive Funktion `f(·)`). Komplexere Lösungsstrategien (Substitution, Faktorisierungs-getriebene Fallunterscheidung) sind nicht enthalten.
- **Assumptions tragen sich nicht automatisch durch die Suche.** `AssumptionContext` ist als Sammelpunkt vorbereitet, aber `TransformationSearchService` führt aktuell keinen Assumption-Strang an der Kante mit; eine Erweiterung wäre additiv möglich.
- **Web-Workbench ist auch im gehärteten Modus minimal.** Kein eingebautes OAuth/OIDC, keine Rate-Limits, kein CSRF-Schutz. Für produktiven Mehrnutzer-Betrieb wird weiterhin ein Reverse-Proxy/WAF empfohlen.
- **SymPy ist optional.** `SymPyEquivalenceService` und `SymPyTransformationEngine` setzen ein installiertes Python/SymPy voraus; ohne Python fallen Tests auf `AstRewriteTransformationEngine` zurück.
- **Symbolic Regression ist Backend-ready, aber nicht extern produktiv.** Die stabile Backend-Schnittstelle erlaubt spätere PySR-/Operon-/GP-Adapter; die eingebauten Quellen sind weiterhin Heuristik-/Template-Baselines und erzeugen nur beobachtete Hypothesen.
- **Counterexample-Quellen sind budgetiert.** Aktiv sind deterministische
  Boundary-/Rational-/Random-Numerik, Division-durch-Null-Annahmen, einfache
  Domain-Kanten (`sqrt`, `log`/`ln`), optionale Complex-Samples und kleine
  2x2-Matrix-Samples für nichtkommutative Kandidaten. Vollständige
  Quantorenabdeckung, NaN/Infinity-Semantik und allgemeine SMT-Beweise sind
  dadurch nicht ersetzt.
- **Such-Budget bleibt hart.** Alle Profile haben Grenzen (max. Tiefe, max. besuchte Ausdrücke). Job-Resume kann über die `SearchCheckpointRepository`-Implementierungen (`InMemorySearchCheckpointRepository`, `JsonFileSearchCheckpointRepository`) den besten gefundenen Ausdruck als neuen Startpunkt verwenden; ein wirklich serialisierter Suchstack (komplette Frontier eines beliebigen Strategiezustands inkl. MCTS-Baum) ist nicht enthalten. Siehe [`docs/checkpointing.md`](checkpointing.md).
- **Job-Manager hat keine Prioritäten / globalen Slots.** Der ServiceFactory-Block trägt die Verantwortung; eine geteilte Jobwarteschlange mit Quoten ist nicht enthalten.
