# Allgemeine Suche und Planung Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Die freigegebenen Empfehlungen als allgemeine, geprüfte Implementierungen mit nachvollziehbarer Gesamtkostenbilanz ausführen.

**Architecture:** Bestehende AST-/Budget-/Prüfverträge erweitern. Unabhängige Teilaufgaben arbeiten in getrennten Worktrees; Integration, Strategieindex und Release werden zentral koordiniert.

**Tech Stack:** Java 25, Maven 3.9.9, vorhandenes Gradle, JUnit, Java-Web-Workbench, JMH.

**Spec:** ../specs/2026-11-25-general-search-and-planning-design.md

## Global Constraints
- Java 25; vorhandene Maven-/Gradle-Verträge und Modulgrenzen erhalten.
- Bestehende AST-, ExactRational-, Polynomial-, Solver-IR-, Budget- und Evidence-Typen wiederverwenden.
- Allgemeine Verfahren; keine Auswahl über Benchmarknamen oder vorbereitete Zielantworten.
- Historische Messartefakte und eingefrorene Vergleichsbudgets unverändert lassen. Neue Varianten und Messungen ausdrücklich versionieren.
- Quellen, Voraussetzungen, Definitionsbereiche und Budgetverbrauch einschließlich Fehlversuchen und Rückfall binden.
- Unabhängige mathematische Prüfer erhalten. Caches sind begrenzte, eigentümergeführte Vorschlagsspeicher.
- StandardCharsets verwenden; keine festen globalen Workerzahlen; keine neuen externen Laufzeitdienste.
- Keine Leistungsbehauptung ohne passende Messung. Keine gelockerten Qualitätsgates.

## Review Focus
- Neue Quellen/Vorkommen dürfen alte Prüfbelege nicht ungeprüft übernehmen (Tasks 1, 4, 8).
- Breite Klauseln, Duplikate und Tautologien dürfen die Anzahl nicht durch freie Hilfsvariablen ändern (Task 2).
- Abgelehnte Vorschläge und Rückfall müssen dasselbe begrenzte Budget teilen (Tasks 3, 6, 7).
- Nennernullstellen, scope-abhängige Variablen und geänderte Annahmen dürfen keine unzulässige Gleichheit erzeugen (Tasks 4, 5, 7).
- Reload, fehlendes Backend und Abbruch müssen den gespeicherten mathematischen Status korrekt zeigen (Task 8).

### Task 1: Parsergebundene Teilbaumprojektion
**Files:** regelsuche-core/src/main/java/de/regelsuche/parse/ExactParsedSubtermProjector.java; ExactParsedTerm.java; ExpressionParser.java nur bei nötiger Parserausstellung; zugehörige Tests.
**Interfaces:** Bestehende project(ExactParsedTerm,List<Integer>,String) und Result/Policy erhalten. Vorhandenes withParserRootReuse um sicher ausgestellte Vorkommen erweitern oder einen klaren allgemeinen Einstieg ergänzen; historische Standardpfade unverändert.
- [x] RED: Echte Teilbäume, verschachtelte/gleiche Vorkommen, äußere Leerzeichen und exakte Literalformen unter knappen Budgets; manipulierte Fremdbelege und veralteter Formattertext ablehnen.
- [x] GREEN: Parserausgestellte Struktur/Ranges ohne erneutes Vollvalidieren wiederverwenden, aktuelle Position und Quellbindung erhalten, Arbeit tatsächlich zählen.
- [x] Prüfen: Bestehende Projektions-/Parser-/Faktorisierungstests und breiter Coretest.
- [x] Dokumentieren: Neue Arbeit im Vergleich zur bisherigen Projektion; commit.

### Task 2: Indexierte und kompakte exakte Constraint-Zählung
**Files:** regelsuche-math-algorithms/.../combinatorics/FiniteConstraintCounter.java; neue fokussierte Hilfsklasse für aktive Faktorinzidenz; kompakte Klauselübersetzung und Prüfbelege im selben Paket; regelsuche-optimization-sdk/.../combinatorics/FiniteConstraintSdk.java; Tests.
**Interfaces:** Bestehende Problem/Limits/Outcome/Verifier erhalten. Neuer CNF-Einstieg arbeitet mit ursprünglicher Variablenanzahl und signierten Literalen; liefert Problem plus unabhängig prüfbare Quellabbildung, eindeutige Hilfsbelegung und Abbruchstatus.
- [x] RED: Kleine CNFs gegen vollständige Originalbelegungen; breite 62-Variablen-OR mit Ergebnis 2^62-1; leere Klausel=0; leere Konjunktion=2^n; Duplikate/Tautologie; feste Variablen; Ressourcenende.
- [x] GREEN: Bucket-Inzidenz pflegen und zählerhaltende funktionale OR-Ketten statt exponentieller Eingabetabellen erzeugen. Unabhängigen Originalquellenprüfer erhalten.
- [x] Prüfen: Bestehende Zähler-/Planner-/SDK-Tests; gemessene Arbeit pro allgemeiner Kette/Komponente dokumentieren.
- [x] Neue Fähigkeit über SDK verfügbar machen; commit.

### Task 3: Gemeinsame kostengebundene Auswahl
**Files:** regelsuche-math-algorithms Planwahlvertrag; vorhandene BilinearIntegerPortfolio und FiniteConstraintPlanner/PlanLearning; jeweilige SDKs und Tests.
**Interfaces:** Wiederverwendbarer unveränderlicher Auswahlbefund mit Anbieteridentität, Voraussetzungen/Größenprofil, Schätzung, gemessener verbrauchter Arbeit und Ablehnungsgrund; bestehende öffentliche APIs bleiben nutzbar.
- [x] RED: Direkte feste Methode, wiederverwendeter Kandidat, fehlgeschlagene Prüfung und bezahlter Rückfall unter gemeinsamer Grenze; deterministische Reihenfolge.
- [x] GREEN: Gemeinsamen begrenzten Vertrag in mindestens zwei tatsächliche Plananbieter einhängen. Bekannte feste Verfahren behalten Vorrang ohne belastbaren erwarteten Vorteil.
- [x] Prüfen: 256-/381-/2048-/8192-Bit-Profile und Zählgraphen; keine Auswahl über Fallnamen; vollständige Kosten.
- [x] Dokumentieren und commit.

### Task 4: Rationalfunktionen mit Definitionsbedingungen
**Files:** regelsuche-math-algorithms/.../equivalence/RationalFunctionNormalFormEquivalenceService.java und vorhandene Polynomial-Typen; neuer begrenzter Vorschlagspfad und SDK; getrennter FPCore-Vergleich.
**Interfaces:** Bestehende exakte Normalformprüfung wiederverwenden. Ergebnis enthält Originalquelle, Kandidat, explizite Nichtnullbedingungen, Prüfstatus und Budget.
- [x] RED: (x^2-1)/(x-1), verschachtelte Division, Nullnenner, neuer Nenner, falsche Annahmen; Verifikation ohne passende Voraussetzungen darf nicht bestätigen.
- [x] GREEN: Allgemeine rationale Kandidaten erzeugen und Definitionsbedingungen erhalten; keine Erweiterung der historischen Polynomstudie.
- [x] Prüfen: Rationale Normalform-/Adaptertests, unabhängige ausgewählte Werte innerhalb/außerhalb der Domäne; vollständiges separates Fremdkorpus inventarisieren.
- [x] SDK/Dokumentation und commit.

### Task 5: Produktnaher Fortsetzungsquotient
**Files:** regelsuche-search SearchProfile/BestFirst-Vertrag, AstRewriteTransformationEngine/PreparedAstRewriteTransformationEngine nur bei belegtem Bedarf, app runtime routing, Tests.
**Interfaces:** Anbieter muss zustandslokale Fortsetzungen ausdrücklich garantieren; keine automatische Freigabe pfadabhängiger Controller.
- [x] RED: Produktaufrufe mit gleichen Werten bei anderer Syntax/Annahmen, Teilbaumpositionen und Budgetdominanz.
- [x] GREEN: Qualifizierten allgemeinen Einstieg mit vorbereiteten Strukturen und nachvollziehbarem Auswahlstatus integrieren.
- [x] Messen: Gleiche Aufgaben und unabhängiges Replay; CPU, Wandzeit, Allokation, Treffer und echte gezählte Arbeit getrennt.
- [x] Dokumentieren und commit.

### Task 6: Bilineare Grenzen und Strukturtransfer
**Files:** regelsuche-math-algorithms/.../algebra/BilinearRankBound.java, BilinearProductLearning.java, BilinearProgramVerifier.java; SDK; Tests/Studie.
**Interfaces:** Quelltensor, rationaler Basiswechsel und erhaltene Produktformen exakt binden; bestehende Zertifikatsform erweitern/versionieren.
- [x] RED: Kleine rationale 2x2x2-Tensoren mit Lücke 2..3, degenerierte Fälle, gefälschte Minoren/Basiswechsel; unabhängige Kontraktion.
- [x] GREEN: Allgemeines begrenztes Rangzertifikat und geprüften Eingabebasis-Transfer ergänzen.
- [x] Messen: Disjunkte deterministisch erzeugte Tensorfamilien, feste klassische Kontrolle, Erwerb/Fehlversuche/Ausführung getrennt.
- [x] Dokumentieren und commit.

### Task 7: Fortsetzbare Beweispläne
**Files:** regelsuche-learning ResidualPlanController/Compiler, regelsuche-discovery ScopedLogicDiscoveryDomain, vorhandener inkrementeller Providervertrag; Tests.
**Interfaces:** Typisierte offene Pflichten, Scope, Cursor, Ressourcen und geprüfte gemeinsame Präfixe; bestehender Empfänger prüft exakte Aussage.
- [x] RED: Zwei konkurrierende Pläne, geteilte Präfixarbeit, geänderter Binder/Annahme, Abbruch und Fortsetzung.
- [x] GREEN: Bestehenden Residualplan als inkrementellen Provider integrieren, gemeinsame Arbeit einmal zählen, keine ungebundene Proof-Übernahme.
- [ ] Prüfen: Bestehende logische/Residualfälle und negative Scopingkontrollen lokal bestanden; der echte Backendpfad ist für die aktuelle Integration noch in CI auszuführen.
- [x] Dokumentieren und commit.

### Task 8: Dauerhafte Prüfbelege und editierbarer Zählablauf
**Files:** app DomainExportWorkspaceActions/View/Store und scoped-check; certificate-demos Seite und Handler; HTTP-/Browsertests.
**Interfaces:** Persistenter GET-Beleg für logische Einzelprüfung mit vollständiger Run-/Workspace-/Kandidatbindung. Zählmaske verwendet ausschließlich Task-2-SDK.
- [x] RED: POST prüfen, Reload/GET öffnen, neue/falsche Quelle, fehlendes Lean, Gegenbeispiel; editable CNF mit Sollwert und Budgetfehler.
- [x] GREEN: Vorhandene Speicher-/Receiptkonvention verwenden; begrenzte Benutzereingabe, verständliche Fehler und Download.
- [x] Browser: echter Server, Desktop/schmale Ansicht, Tastatur, Reload, ungültige Eingabe und Abbruch.
- [x] Dokumentieren und commit.

### Task 9: Lebenszyklusmessung, schnelle CI und Lieferung
**Files:** neue datierte Forschungsprotokolle, docs/research/strategies.md, docs/getting-started.md, docs/mathematical-capabilities.md, .github/workflows/gradle.yml, maven-build-contract Tests; Release-/Consumer-Dokumentation.
**Interfaces:** Vorab fixierte Familien, stärkste feste Baseline, gleiche Algorithmen/Budgets, gesamte gemessene Kosten; neue Messdaten neben alten.
- [x] Entwicklungs-/Validierungs-/Finalfamilien vor Ausführung festlegen, abgelehnte Lernversuche einschließen.
- [ ] Frühe schnelle Vertragsprüfung ausführbar vor lange CI-Jobs setzen; verbleibende doppelte Autoritäten nur nach bestehender vollständiger Archivparität ablösen.
- [ ] Strategieindex und Einstieg mit tatsächlichem Implementierungs-/Release-/Consumerstand synchronisieren.
- [ ] Gesamtreview, komplette aktuelle CI und SDK-Consumer; erst dann verwalteten Releasepfad benutzen.

