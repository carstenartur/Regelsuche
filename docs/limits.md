# Limits & bewusst nicht implementiert

- **Kein vollwertiges Computer-Algebra-System.** Vereinfachungen entstehen ausschließlich durch Verkettung atomarer Regeln; komplexe Identitäten (binomische Formeln, vollständige quadratische Ergänzung, allgemeine Polynom­division) sind nicht als einzelne Regel kodiert.
- **Kein automatisierter Beweischeck.** `FORMALLY_PROVED`/`FORMALLY_PROVABLE` existieren als Lifecycle-Marker, aber es ist keine Beweis-Engine angebunden – Statuswechsel müssen heute extern erfolgen.
- **Web-Workbench ist minimal.** Der eingebettete `HttpServer` ist für lokale Exploration gedacht (keine AuthN/AuthZ, kein TLS, kein Spring/Reactive Stack). Für produktive Bereitstellung wäre ein Reverse-Proxy/Hardening nötig.
- **SymPy ist optional.** `SymPyEquivalenceService` und `SymPyTransformationEngine` setzen ein installiertes Python/SymPy voraus; ohne Python fallen Tests auf `AstRewriteTransformationEngine` zurück.
- **Such-Budget.** Alle Profile haben harte Grenzen (max. Tiefe, max. besuchte Ausdrücke). Größere Suchen können auf `SearchProfile.EXHAUSTIVE_SMALL` umgestellt werden, sind aber immer noch begrenzt.
- **Inventar enabled/Tags sind In-Memory.** `InMemoryRuleInventoryRepository` persistiert enabled/disabled und Tags nicht auf Disk; bei Neustarts gehen sie verloren. `Neo4jRuleInventoryRepository` speichert sie aktuell nicht (Default-No-Ops). 
- **Division durch 0.** Die Bruchregeln verweigern explizit das Matchen, wenn das Resultat eine literale Division durch 0 wäre; allgemeinere Bedingungen (z. B. `b != 0`) werden nicht symbolisch geprüft.
