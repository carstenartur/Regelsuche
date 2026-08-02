# Testing-Strategie

Die Teststrategie folgt den Architektur- und Trust-Grenzen. Innere mathematische
Module werden schnell und deterministisch geprüft; Infrastruktur, Browser,
Container und externe Solver werden erst in den dafür verantwortlichen äußeren
Schichten einbezogen.

Diese Seite beschreibt die **Strategie**. Konkrete Tasks, Voraussetzungen und
Triage stehen in [Testing und Verifikation](testing.md).

## Testschichten

| Schicht | Hauptfrage | Infrastruktur | Typischer Task |
| --- | --- | --- | --- |
| Core | Ist AST-, Parser-, Identitäts-, Annahmen- und Regel-Semantik korrekt? | keine | `:regelsuche-core:test` |
| E-Graph | Sind E-Matching, Merge und Saturation deterministisch? | keine | `:regelsuche-egraph:test` |
| Search | Sind Strategie, Budget, Tie-Break, Memory und Work Accounting korrekt? | keine | `:regelsuche-search:test` |
| Validation | Werden Äquivalenz, Annahmen, Refutation und Inconclusive korrekt getrennt? | JVM, optionale Adapter | `:regelsuche-validation:test` |
| Math Algorithms | Liefern begrenzte Algorithmen die deklarierte Semantik? | überwiegend JVM | `:regelsuche-math-algorithms:test` |
| Solver IR und Portfolio | Bleiben Obligation, Translation, Resultat, Execution und Backend-Auswahl getrennt? | reale Solver nur in fokussierten Tests | Modul- und Portfolio-Tests |
| Learning und Discovery | Sind Candidate Formation, Lineage, Mutation, Handoff und Grenzen reproduzierbar? | keine App-Infrastruktur | Modul-Tests |
| Experiments und Benchmarks | Sind Corpus, Parity, Budgets, Matrix und negative Outcomes vollständig? | externe Tools nur trackbezogen | Experiment-/Benchmark-Tests |
| Persistence Ports | Sind Konfiguration, Checkpoints und atomare Dateioperationen korrekt? | Temp-Dateien | `:regelsuche-persistence:test` |
| Relationale Adapter | Funktionieren Migration, ORM, Search Index und Repository-Verträge? | Testcontainers/PostgreSQL | Hibernate- und Docker-E2E |
| App Integration | Ist das Runtime-Wiring über CLI, HTTP, Persistence und Proof konsistent? | JVM, optionale Adapter | `:app:test` |
| Browser-E2E | Kann ein Nutzer den sichtbaren Flow vollständig ausführen? | Playwright, reale lokale Workbench | `:app:e2eTest` |
| Container-E2E | Enthalten reale Images die erwartete Runtime und Assets? | Docker/Testcontainers | `:app:dockerE2eTest` |
| Evidence und Release | Sind Hashes, Manifeste, Claims und Reproduktion vollständig gebunden? | Gradle, Python, Docker, Solver je Vertrag | Verifier, `fullCheck`, `ciCheck` |
| Performance | Bleibt eine eingefrorene Messpolitik innerhalb ihrer Ratchets? | JMH und gepinnte Umgebung | Benchmark-Verifier |

## Grundprinzipien

### 1. Schnellste fachliche Ebene zuerst

Eine neue Regel, Heuristik oder Identitätsfunktion erhält zuerst einen Test im
innersten passenden Modul. Ein Browser- oder Container-Test ersetzt keinen
fehlenden Unit- oder Komponententest.

### 2. Reale Infrastruktur nur an der Adaptergrenze

Testcontainers und externe Prozesse werden dort eingesetzt, wo ihre reale
Semantik Teil des Vertrags ist. Core- und Search-Tests bleiben ohne Docker,
Datenbank oder Browser ausführbar.

### 3. Positive und negative Semantik

Jeder Vertrag charakterisiert nicht nur Erfolg, sondern auch:

- ungültige Inputs;
- fehlende Annahmen;
- Budgeterschöpfung;
- unsupported Fälle;
- technische Nichtverfügbarkeit;
- Timeout und Abbruch;
- Duplicate-, Collision- und Substitutionsversuche;
- Nullresultate und Refutations.

### 4. Determinismus und kanonische Identität

Wiederholte Läufe mit identischen Inputs müssen semantisch identische Ergebnisse
liefern. Wo der Vertrag Byteidentität fordert, werden Serialisierung,
Sortierung, Hashes und Manifestbytes exakt verglichen.

Wandzeit, temporäre Pfade, Ports und Prozess-IDs gehören nicht in kanonische
Evidence.

### 5. Vollständiges Accounting

Ein Test darf fehlende Arbeit nicht durch kleinere Nenner verbergen. Reports
bilanzieren konfigurierte, ausgeführte, übersprungene, unsupported,
fehlgeschlagene und verbleibende Fälle.

### 6. Claim-Stufen getrennt prüfen

Search, Validation, Counterexample Search, Proof, Project Novelty, External
Novelty, Utility, Promotion und Public Evidence besitzen eigene Assertions. Ein
Erfolg in einer Stufe darf nicht implizit die nächste erfüllen.

## Nutzerfluss-Tests

Browser-E2E prüft den sichtbaren Ablauf, nicht lediglich HTTP-Erreichbarkeit.
Ein vollständiger Flow umfasst je nach Funktion:

1. verständlichen Einstieg;
2. Eingabe und Aktion;
3. Lade- oder Fortschrittszustand;
4. Ergebnis oder leeren fachlichen Terminalzustand;
5. Fehlerdarstellung;
6. nächsten sinnvollen Schritt;
7. gegebenenfalls Download oder Replay.

Dokumentationsscreenshots werden aus denselben Flows erzeugt. Ein Bild ohne
zugehörige semantische Assertion ist kein ausreichender Produkttest.

## Solver- und Proof-Tests

Mock-Backends dürfen Orchestrierung und Fehlerpfade prüfen, aber keinen
fachlichen Proof-Claim autorisieren. Tests für formale Statusübergänge müssen
mindestens unterscheiden:

- bestätigte Obligation;
- widerlegte Obligation;
- `UNKNOWN`;
- Backend nicht verfügbar;
- Timeout oder Prozessfehler;
- unvollständiges Proof-Artefakt.

Der mathematische Status wird aus der konkreten Solver Execution abgeleitet,
nicht aus Planner- oder Portfolioerfolg.

## Benchmark-Tests

Ein Vergleichstest prüft zusätzlich zur Ergebniszahl:

- identische Informationsoberfläche;
- identische oder ausdrücklich normalisierte Budgets;
- vollständige Konfiguration-mal-Fall-Matrix;
- Translation Losses und unsupported Capabilities;
- retained negative Ergebnisse;
- track-spezifischen Claim;
- Abwesenheit eines universellen Scores.

Performance-Ratchets und mathematische Work-Budgets bleiben getrennt.

## Evidence- und Schema-Tests

Ein neues Evidence-Format benötigt:

- Runtime-Codec und striktes Schema;
- positive Roundtrips;
- Duplicate-Field- und Unknown-Field-Negativtests, soweit vorgesehen;
- kanonische Sortierungs- und Byteidentitätstests;
- Hash- und Wurzelrekonstruktion;
- Cross-Artifact-Substitutionstests;
- Ressourcen- und Vollständigkeitsbilanz;
- unabhängigen Verifier außerhalb des produktiven Writers.

Schema-Validität allein genügt nicht für einen Claim.

## Testauswahl nach Änderung

| Änderung | Mindestabdeckung |
| --- | --- |
| AST oder kanonische Identität | Core-Tests, Downstream-Charakterisierung, Evidence-Hash-Auswirkungsprüfung |
| neue Regel | Match, Nicht-Match, Annahmen, Pack/Tier, Suchintegration |
| Suchstrategie | deterministische Ordnung, Budgets, Work Accounting, Vergleich zur Referenz |
| HTTP-Funktion | Handler-/Codec-Test, reale HTTP-Integration, OpenAPI, gegebenenfalls Browser-E2E |
| UI-Flow | Browser-E2E, Fehler-/Leerzustand, Handbuch und gegebenenfalls Screenshot |
| Persistenzadapter | Port-Charakterisierung, Migration, reale Testcontainers-Integration, Recovery-Pfad |
| Solver-Backend | Translation, reale Execution, Timeout, Unsupported, Certificate-Semantik |
| Evidence-Vertrag | Codec, Schema, Writer, unabhängiger Verifier, Substitution und Reproduktion |
| Workflowänderung | `verifyWorkflowSemantics` und unveränderte lokale Reproduzierbarkeit |

## Fehlerklassifikation

Ein Testfehler wird vor dem Fix klassifiziert:

1. **mathematischer Fehler** — Regel, Ergebnis, Annahme oder Proof ist falsch;
2. **Vertragsfehler** — Codec, Status, Budget oder Evidence-Beziehung ist falsch;
3. **Produktfehler** — sichtbarer Ablauf, Darstellung oder Export ist falsch;
4. **Infrastrukturfehler** — Werkzeug, Container, Netzwerk oder Runner ist nicht verfügbar;
5. **Flakiness/Determinismusfehler** — Ergebnis hängt unzulässig von Reihenfolge,
   Timing oder Umgebung ab.

Die Klassifikation bestimmt, ob Implementierung, Test, Infrastruktur oder
Messpolitik geändert werden muss. Eine Schranke wird nicht abgeschwächt, nur
weil sie einen realen Fehler sichtbar macht.

## Anti-Patterns

Nicht zulässig sind insbesondere:

- fachliche Assertions ausschließlich in GitHub Actions;
- Core-Tests, die Docker oder eine Datenbank benötigen;
- `Thread.sleep` als primäre Synchronisationsstrategie;
- Überspringen mandatory Fälle ohne retained Status;
- manuelle Änderung generierter Evidence, um einen Test zu erfüllen;
- Akzeptieren eines neuen Baselineswerts ohne Analyse der Regression;
- Mock-Proof als formaler Beweis;
- Entfernen negativer Benchmarkfälle nach Ergebnisinspektion;
- ein normaler Testlauf, der tracked Source-Dateien verändert.

## Siehe auch

- [Testing und Verifikation](testing.md)
- [Developer Guide](developer-guide.md)
- [Architektur](architecture.md)
- [Dependency-Regeln](dependency-rules.md)
- [Dokumentationskonventionen](documentation-conventions.md)
