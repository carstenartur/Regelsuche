# Limits & bewusst nicht implementiert

## Was geht (Stand jetzt)

- **Web-Workbench hardenable.** Der eingebettete `HttpServer` läuft standardmäßig ohne Auth/TLS, kann aber per `WebSecurityConfig` mit HTTP Basic Auth und TLS (PKCS12-Keystore) hochgezogen werden – plus konfigurierbarer Request-Größen-Cap. Siehe [`docs/web-workbench-security.md`](web-workbench-security.md).
- **Proof-Bridge.** `ProofBridge` mit `LeanProofBridge` und `SmtProofBridge` erzeugt Lean-4-Theorem-Skelette bzw. SMT-LIB-2-Skripte. Der `ProofBridgeService` lift den Kandidaten-Status auf `FORMALLY_PROVABLE` und schreibt das Artefakt optional auf Platte. Siehe [`docs/proof-bridge.md`](proof-bridge.md).
- **Inventar-Persistenz.** `InMemoryRuleInventoryRepository.persistTo(file)` / `loadFrom(file)` schreibt/liest enabled/tags plus alle Rule-Felder als JSON. Das `Neo4jRuleInventoryRepository` speichert enabled/tags zusätzlich als Knoten-Properties.
- **Symbolische Nebenbedingungen.** `Assumption` + `AssumptionContext` modellieren Vorbedingungen; `RewriteRule.assumptions(subtree)` ist ein Erweiterungspunkt. `RationalRules` emittiert konkrete `NON_ZERO`-Assumptions. Siehe [`docs/assumptions.md`](assumptions.md).
- **Jobsteuerung.** `SearchJobManager` mit `submit/pause/resume/cancel/checkpoint/restore` für persistente, abbrechbare Suchläufe. Siehe [`docs/job-control.md`](job-control.md).

## Was bewusst (noch) nicht geht

- **Kein vollwertiges Computer-Algebra-System.** Vereinfachungen entstehen ausschließlich durch Verkettung atomarer Regeln; komplexe Identitäten (binomische Formeln, vollständige quadratische Ergänzung, allgemeine Polynom­division) sind nicht als einzelne Regel kodiert.
- **Kein Proof-Solver-Aufruf aus dem JVM.** `LeanProofBridge`/`SmtProofBridge` erzeugen nur Skripte; der eigentliche Theorem-Prover (Lean, Z3, CVC5) wird **nicht** automatisch gestartet, weil die Tools plattformabhängig installiert werden müssen. `FORMALLY_PROVED` bleibt damit ein manueller Lift.
- **Trigonometrie / Logarithmus / Analysis.** Diese Domänen brauchen einen `FunctionExpr`-Knoten im `Expr`-AST (`sin`, `cos`, `log`, Ableitungs-/Integraloperatoren) sowie Parser- und Formatter-Erweiterungen. Das ist nicht enthalten, weil es eine breitflächige AST-Refaktorierung wäre, die viele bestehende Komponenten (Canonicalizer, Pattern-Matcher, Mining) berührt.
- **Equation-Domain ohne dedizierte Regel-Engine.** Gleichungen werden derzeit termweise transformiert; eine eigene `EquationRewriteEngine`, die Operationen "auf beide Seiten" anwendet (z. B. `a + b = c  ⇒  a = c - b`), fehlt. Das hängt mit dem AST-Punkt oben zusammen.
- **Assumptions tragen sich nicht automatisch durch die Suche.** `AssumptionContext` ist als Sammelpunkt vorbereitet, aber `TransformationSearchService` führt aktuell keinen Assumption-Strang an der Kante mit; eine Erweiterung wäre additiv möglich.
- **Web-Workbench ist auch im gehärteten Modus minimal.** Kein eingebautes OAuth/OIDC, keine Rate-Limits, kein CSRF-Schutz. Für produktiven Mehrnutzer-Betrieb wird weiterhin ein Reverse-Proxy/WAF empfohlen.
- **SymPy ist optional.** `SymPyEquivalenceService` und `SymPyTransformationEngine` setzen ein installiertes Python/SymPy voraus; ohne Python fallen Tests auf `AstRewriteTransformationEngine` zurück.
- **Such-Budget bleibt hart.** Alle Profile haben Grenzen (max. Tiefe, max. besuchte Ausdrücke). Job-Resume startet derzeit von vorn (kein mittendrin-Checkpoint des Suchstacks).
- **Job-Manager hat keine Prioritäten / globalen Slots.** Der ServiceFactory-Block trägt die Verantwortung; eine geteilte Jobwarteschlange mit Quoten ist nicht enthalten.
