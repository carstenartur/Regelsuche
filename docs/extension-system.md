# Erweiterungssystem

Regelsuche besitzt mehrere Erweiterungsflächen mit unterschiedlichen Aufgaben und Vertrauensgrenzen. Sie sind absichtlich **nicht** zu einem einzigen universellen Plugin-Mechanismus zusammengefasst.

## Überblick

| Erweiterungsfläche | Typischer Autor | Ladeweg | Darf Suchkanten beitragen? | Qualitäts-/Trust-Gate |
|---|---|---|---:|---|
| Java-Plugin | Java-Entwickler | JAR in `plugins/`, `ServiceLoader`, `PluginRuntime` | ja | API-/Core-Kompatibilität, Abhängigkeiten, Laufzeitdiagnosen; externe Signaturen werden noch nicht kryptografisch verifiziert |
| Regeldatei | Anwender oder Regelautor | `.regelsuche` / `.rules` in `rules/` | ja | Parser, typisiertes internes Modell, Profile, Konflikt-/Zykluserkennung und Debug-Diagnosen |
| Knowledge Pack | Kurator oder Domänenautor | eingebaute YAML-Packs unter `rules/packs/` | ja, nach Registrierung | Provenance, Lizenz, Reviewstatus, Risiko und Validierungsbeispiele; nur freigegebene Einträge werden registriert |
| Deklaratives Makro | Regelautor | `macro`-Eintrag in Regeldatei | ja, als `macro.<id>` | dieselben Profil-, Konflikt- und Laufzeitgrenzen wie Regeldateien |
| Gelerntes/promoviertes Makro | Discovery-Pipeline | Mining, Validierung und Inventar-Promotion | ja, nach Promotion | Holdouts, Gegenbeispielsuche, Novelty, Proof-/Promotion- und Public-Evidence-Gates |
| Discovery-Operator | Core- oder Plugin-Entwickler | `HypothesisOperatorRegistry` beziehungsweise expliziter Plugin-Beitrag | erzeugt Hypothesenkandidaten | operatorspezifische Bounds; Kandidaten durchlaufen anschließend die normalen Discovery-Gates |
| Mathematische Capability | Backend-Entwickler | `MathematicalAlgorithmRegistry` / Capability-Registry | nein, validiert oder beweist | deklarierte Fähigkeiten, unterstützte Domänen und Ergebnissemantik; Solverorchestrierung bleibt separat |
| Autopilot-Capability | Campaign-Autor | erlaubte Capability-Namen im Research Brief | nein | begrenzt erlaubte Arbeit; ist weder Plugin-, Proof- noch Novelty-Status |

## 1. Java-Plugins

Java-Plugins implementieren `de.regelsuche.plugin.RegelsuchePlugin`. Die Laufzeit lädt Classpath-Plugins und externe JARs über `ServiceLoader`; `PluginRuntime` unterstützt Reload, Diff, Diagnosen und einen Verzeichnis-Watcher.

Plugins können insbesondere Regeln, Transformationen, AST-Visitor, Makros, Suchstrategien, Heuristiken, Kostenfunktionen, Renderer, Erklärungen, Parser-Erweiterungen und Beispiele registrieren. Eine Plugin-Transformation erscheint als normale Kante im Suchgraphen.

Wichtige Grenze: Nicht jede interne Registry ist automatisch ein öffentlicher JAR-Erweiterungspunkt. Discovery-, Proof- und Solverkomponenten bleiben nur dann extern ladbar, wenn dafür ein ausdrücklich dokumentierter Plugin-Vertrag existiert.

Siehe [Plugins](plugins.md) und [Plugin-API](plugin-api.md).

## 2. Regeldateien und deklarative Makros

Regeldateien verwenden die mathematisch orientierte `.regelsuche`-/`.rules`-DSL. `RuleFileParser` überführt sie in ein typisiertes internes Modell. Unterstützt werden Regeln, Makros und Aktivierungsprofile.

Regeldateien bieten den niedrigschwelligen Erweiterungsweg:

- keine Java-Kompilierung,
- verständliche Parser- und Validierungsdiagnosen,
- Prioritäten und Tags,
- Whitelist/Blacklist und Profile,
- Konflikt- und Zykluserkennung,
- Import, Export und Debug-Ausgabe.

Deklarative Makros sind dabei vom Discovery-Lernen zu unterscheiden: Sie werden vom Autor vorgegeben und direkt als einstufige Transformationskante registriert.

Siehe [Regeldateien](rule-files.md) und [Makros](macros.md).

## 3. Knowledge Packs

Knowledge Packs sind kuratierte, eingebaute YAML-Pakete. Sie tragen neben Pattern und Replacement zusätzliche Governance-Metadaten wie Provenance, Lizenz, Reviewstatus, Risiko und Validierungsbeispiele.

Sie sind kein Ersatz für externe Java-Plugins oder die anwenderorientierte Regel-DSL. Ihr Zweck ist ein nachvollziehbares, reviewbares Core-Inventar. Kandidaten bleiben deaktiviert, bis ihr Status die Registrierung erlaubt.

Siehe [Knowledge Packs](knowledge-packs.md).

## 4. Gelernte und promovierte Makros

Die Discovery-Pipeline kann aus wiederkehrenden realen Suchpfaden generalisierte Makroregeln gewinnen. Diese Regeln werden nicht durch bloßes Auftreten aktiv. Vor einer Promotion stehen unter anderem:

- strukturelle Generalisierung,
- frische positive und negative Holdouts,
- Gegenbeispielsuche,
- projektinterne Novelty,
- Proof-Evidenz,
- gepaarter Suchnutzen,
- Promotion- und Public-Evidence-Gates.

Erst danach darf ein Kandidat als wiederverwendbarer `MacroMove` in den Suchpfad zurückkehren. Damit bleibt gelerntes Wissen von benutzerdefinierten Regeldateien und ungeprüften Plugin-Beiträgen unterscheidbar.

Siehe [Makroregeln und emergente Identitäten](macro-rules.md) und [Von Umformungen zu mathematischen Entdeckungen](from-transformations-to-discovery.md).

## 5. Discovery-Operatoren

Discovery-Operatoren erzeugen begrenzte Hypothesenkandidaten aus einem Ausdruck oder Suchgraphen. Sie sind keine Wahrheitsorakel und keine automatische Promotion. Ein Operator kann im Core registriert oder über einen ausdrücklich dafür vorgesehenen Plugin-Beitrag geliefert werden.

Jeder erzeugte Kandidat muss danach dieselben Falsifikations-, Novelty-, Proof- und Promotion-Gates durchlaufen wie andere Open-Target-Kandidaten.

## 6. Mathematische Capabilities und Solver

Mathematische Algorithmen und Solver werden als Fähigkeiten mit klarer Ergebnissemantik behandelt. Sie validieren, falsifizieren oder beweisen eine bereits gebildete Obligation; sie dürfen nicht unbemerkt den Discovery-Pfad oder die erwartete Antwort konstruieren.

Die heutigen Capability-Registries sind deshalb nicht pauschal identisch mit `PluginRuntime`. Eine capability-aware Auswahl und die solver-neutrale Obligations-/Proof-IR werden in den separaten Issues #233 und #234 entwickelt.

Siehe [Mathematical Algorithms](mathematical-algorithms.md) und [Proof Bridge](proof-bridge.md).

## 7. Autopilot

Der Autopilot Research Brief enthält erlaubte Capability-Namen, Domänen, Generatoren und Budgets. Diese Angaben begrenzen, welche Arbeit eine Campaign planen und ausführen darf. Sie laden selbst keinen Code und sind kein Vertrauensnachweis für ein Plugin oder Backend.

Planner- und Executor-Entscheidungen bleiben Telemetrie. Sie sind weder mathematische Evidenz noch Novelty-, Proof-, Promotion- oder Public-Evidence-Status.

Siehe [Autopilot](autopilot-planner.md).

## Vertrauensmodell

Für externe Java-Plugins sind Herkunfts- und Signaturmetadaten sichtbar. Aktuell gilt jedoch:

- `signaturePresent` bedeutet nur, dass Metadaten vorhanden sind;
- `signatureVerified` bleibt ohne kryptografischen Verifizierer `false`;
- externe JARs bleiben ohne Trust Store oder Allowlist untrusted;
- Classpath-/Built-in-Beiträge gelten als bekannte Quelle, nicht automatisch als mathematisch korrekt;
- mathematische Korrektheit und Artefaktvertrauen sind getrennte Fragen.

Echte Signaturprüfung, Publisher-Identität, Schlüsselrotation, Sperrlisten und reproduzierbare Installation/Updates gehören zum offenen Plugin-Ökosystem in Issue #104.

## Auswahlhilfe

- Eine eigene Transformation ohne Java schreiben: **Regeldatei**.
- Eine feste mehrstufige Abkürzung deklarieren: **Regeldatei-Makro**.
- Kuratierte Core-Regeln mit Governance-Metadaten beitragen: **Knowledge Pack**.
- Eigene Java-Logik, AST-Hooks oder Suchkomponenten bereitstellen: **Java-Plugin**.
- Eine neue Hypothesengenerierung implementieren: **Discovery-Operator**, danach normale Discovery-Gates.
- Einen neuen Solver oder Validator anbinden: **mathematische Capability**, nicht automatisch allgemeines Plugin-JAR.
- Wiederkehrende Suchpfade automatisch wiederverwenden: **gelernte/promovierte Makroregel**.

## Offener Ökosystemumfang

Die technische Grundlage aus Issue #74 ist abgeschlossen. Offen bleibt in Issue #104 insbesondere:

- öffentlicher oder föderierter Plugin-/Paketindex,
- separat baubare Beispielprojekte,
- reproduzierbare Installation, Update und Rollback,
- echte kryptografische Signaturprüfung und Trust-Policy,
- Publishing-, Security- und Review-Dokumentation.
