# Discovery- und Forschungsstand

**Stand: 9. September 2026**

Diese Übersicht trennt ausgelieferte Fähigkeiten, Entwicklungen nach dem
Release, begrenzte Projektnachweise und noch nicht ausgeführte Studien.
[Regelsuche 0.4.0](releases/0.4.0.md) ist veröffentlicht. Seine unveränderten
Release-Artefakte enthalten nicht automatisch spätere Änderungen am Quellcode.

## Zusammenfassung

Regelsuche besitzt eine ausführbare Plattform für symbolische Suche,
Regelvorbereitung, generationengetrenntes Lernen und unabhängige Prüfung.
Gelernte Regeln können innerhalb größerer Ausdrücke und an ausgewählten
Summandenpaaren wiederverwendet werden. Es existieren begrenzte historische
Kompositionsnachweise und erste zielausdrucksfreie Restterm-Kombinationen.

Nicht abgeschlossen ist der stärkere Nachweis, dass das System die
übergeordnete Beweisstrategie selbst lernt und mit dieser auf unberührten
Aufgaben unter vergleichbarer Gesamtarbeit besser sucht. Eine grüne Software-CI
ist kein Ersatz für dieses Experiment.

## Status auf einen Blick

| Bereich | Implementierung | Aussagegrenze |
| --- | --- | --- |
| Produkt | Web-Workbench, CLI, Docker, Full Mode, Proof-Jobs und reproduzierbare Exporte | Kein Produktions- oder Sicherheitszertifikat |
| Exakte Polynome | Parsergebundene Literalprovenienz, native begrenzte univariate Faktorisierung über `Z[x]` und `Q[x]`, endliche Körper, Hensel-Lifting und Rekombination | Keine allgemeine multivariate Faktorisierung; Backend-Vollständigkeit ist nicht automatisch unabhängig zertifiziert |
| Faktorisierung im AST | Verifier-gebundene Ersetzung auch ausgewählter verschachtelter Vorkommen mit Kontextprüfung | Ein Budgetabbruch ist kein Irreduzibilitätsbeweis |
| Gleichungssysteme | Exaktes `A*x=b`, Blockzerlegung, RREF, Lösungsklassifikation und explizite Eigenproblemrollen | Keine Physik allein aus Symbolnamen, kein allgemeiner nichtlinearer Solver |
| Automatische Darstellungswahl | Quellengestützte unabhängige Blöcke vor Eliminierung; direkter, Matrix- und Blockvergleich mit vollständigem Replay | Rund 6 % weniger gezählte Arbeit im öffentlichen Entwicklungssatz; keine Laufzeitmessung oder globale Optimalität |
| Gelernte Darstellungsauswahl | Eingefrorene Bedingung aus 18 verifizierten Trainingstrials; 42 weitere Aufgaben und sechs Profile | Begrenzter Transfer innerhalb linearer Algebra; kein Zusatzgewinn gegenüber fester Auswahl, Lernkosten nicht amortisiert |
| Externer linearer Client | Installierbares Python-Paket, Java-Beispiel, Browserdemo und unabhängiger rationaler Prüfer | Exakte begrenzte affine Lösungsmengen; keine PyPI-Veröffentlichung oder Lean-Kernprüfung behauptet |
| Vorbereitung | Native Exact-Spezialisten, Guards, lokale Pattern-Bridges und Unified Coordinator | Allgemeines Defaultprofil und gemeinsame Multi-Principal-Ausführung bleiben gesondert zu qualifizieren |
| Regelmining | Eingefrorene Generationen, exakte Patternprüfung und kumulativer Wiederverwendungsaudit | Experimentelle Schatteninventare; Tiefenbudget-Erfolg ist keine allgemeine Reduktion der Gesamtarbeit |
| Historische Komposition | Elf-Schritt-Brahmagupta–Fibonacci-Pfad mit zweimaliger Anwendung derselben gelernten Ergänzungsregel | Deklarierte Phasenfolge; unrestricted Best-First blieb bei 20.000 Zuständen ohne Fund |
| Resttermkomposition | Disjunkte Summandenpaare und Vorzeichen können über exakte Nullrestprüfung ohne Zielausdruck ausgewählt werden | Strategie und vorbereitende Strukturwahl sind noch nicht selbst gelernt |
| Schematische Pläne | Typisierte Lücken, endliche Koeffizienten-/Vorzeichensuche und verifier-gebundene Kandidatenevidence | Vorgegebene Ansatzgrammatik ist keine autonom erfundene Taktik |
| Gelernte Regelfolgen | Verzweigende Programme aus zielausdrucksfreien primitiven TRAIN-Spuren und getrennt trainierte Fortsetzungsauswahl | Auf 288 neuen Entwicklungsfällen 1,4 % weniger gezählte Anwendungsarbeit als Greedy, gleiche Scores; Lernkosten noch nicht amortisiert, keine unabhängig zurückgehaltenen Mathematikfamilien |
| Java-SDK | Eigenständige Java-25-Domänen, Fassade, Assertions und Provider-SPI | Lokale Maven-Bereitstellung; öffentliche stabile Release-API noch nicht zugesagt |
| Externe Neuheit | Eigener, noch offener Literatur-/Expertenprüfpfad | Keine weltweite Neuheitsbehauptung |

Details zu den inzwischen vorhandenen Polynomschritten stehen unter
[domänenbewusste Faktorisierung](domain-aware-polynomial-factorization.md),
[verschachtelte Vorkommen](exact-nested-factorization-transformation.md) und
[SymPy/GraalPy-Adapter](sympy-factorization-adapter.md). Die frühere Aussage,
endliche Körper oder Hensel-Lifting seien noch gar nicht implementiert, ist
überholt. Produktgleichheit, Vollständigkeit und Irreduzibilität bleiben
unterschiedliche Aussagen.

## Entwicklung nach 0.4.0: budgettreue Programmkomposition

Der neue Einstieg `RewriteProgramInterpreter.executeBudgeted` erweitert den
isolierten `BudgetedSource`-Einstieg um `Choice`, `FirstApplicable`, `Sequence`,
`Repeat` und explizites `Prune`. Jede Alternative erhält dasselbe eingehende
Pfadbudget; Fortsetzungen und Wiederholungen erhalten nur den Rest nach der
konkreten Präfixarbeit. Mathematische und mechanische Arbeit bleiben getrennt.

Die Ausführung bindet das vollständige Programm und seine Source-Identitäten,
behält erfolglose und abgebrochene Source-Aufrufe sowie entfernte Pfade und
unterscheidet vollständige/unvollständige Ergebnisse mit/ohne Kandidaten.
Ein unvollständiges leeres Ergebnis darf in `FirstApplicable` keine spätere
Alternative freigeben.

Dies ist Ausführungsinfrastruktur unter #906, noch keine gelernte Strategie
oder gewöhnliche Suchkante. Die [Kompositionsdokumentation](budgeted-rewrite-program-composition.md)
enthält Vertrag, Prüfungen und Reproduktionsbefehle. Testdefinitionen und lokale
Teilprüfungen ersetzen nicht die vollständige CI zum jeweiligen Commit.

## Begrenzte Auswahl von Polynomstrategien

Der experimentelle Baustein `FinitePolynomialStrategySearch` erzeugt alle
Ansatzfolgen innerhalb einer vorgegebenen endlichen Grammatik, bewertet sie
auf TRAIN-Eingaben und friert die Auswahl vor weiteren Anwendungen ein.
Neue Koeffizienten werden bei der Wiederverwendung neu gelöst und geprüft;
die Trainingsdaten und ihre bloß umbenannten Äquivalente sind dabei gesperrt.

Im Entwicklungsfall gewinnt direkte Faktorisierung gegen eine unnötige
quadratische Ergänzung davor. Alle 18 Trainingszeilen und die Kosten der
Auswahl bleiben erhalten. Andere Trainingsdaten können eine andere Vorlage
wählen. Das ist datenabhängige Auswahl, nicht das Erlernen der Ansatzgrammatik,
einer verzweigenden Taktik oder der allgemeinen Resttermstrategie. Neue
Koeffizienten derselben Familie ersetzen keinen unberührten FINAL TEST.
Reproduktion und Grenzen: [Polynomstrategien aus Trainingsaufgaben](finite-polynomial-strategy-selection.md).

## Gelernte Regelfolgen aus Suchspuren

Der zusätzliche [Strategievergleich](trace-strategy-transfer.md) bildet aus
beobachteten Regelfolgen einen Präfixbaum mit `Sequence` und `Choice` im
vorhandenen Programm-IR. Die Programmstruktur hängt tatsächlich vom Training
ab; neue Ausdrücke erhalten frisch ausgeführte primitive Pfade. Acht öffentliche
Entwicklungsaufgaben werden in drei Profilen einschließlich Negativ- und
unveränderter Kontrollfälle ausgewertet. Im unterstützten Bestand findet jedes
Profil dieselben Endausdrücke; das gelernte Programm kostet mehr Sucharbeit.
Das ergänzt die Ausführungsinfrastruktur um begrenztes Lernen und eine
reproduzierbare Demo. Es schließt den offenen Nachweis einer selbst erlernten
familienübergreifenden Taktik unter vergleichbarer Gesamtarbeit nicht ab.

Die anschließende [bedingte Fortsetzungsauswahl](conditional-strategy-dispatch.md)
verwendet vorhandene primitive Kandidaten erneut und führt eine kompilierte
Fortsetzung nur bei einem auf getrenntem TRAIN akzeptierten Kontext aus. Die
neue Fallserie wurde vor ihrer Anwendung festgelegt und enthält auch passende
Regelhinweise mit unpassenden Bindungen. Gegenüber einer starken Greedy-Kontrolle
sinkt die gezählte Anwendungsarbeit von 8.960 auf 8.832 bei unveränderten Scores.
Alle 1.152 Vergleichszeilen bleiben erhalten. Die einmaligen 1.166 Einheiten
Lernarbeit sind zusätzlich zu zahlen; dies ist noch kein positiver Gesamtkosten-
oder Laufzeitnachweis. Der ursprüngliche negative Zusatzprogrammvergleich bleibt
unverändert reproduzierbar.

Bei der [Bildung gelernter Folgen](primitive-trace-minimality.md) ist die Prüfung
auf kürzere primitive Verbindungen jetzt verpflichtend. Der Nachweis bindet die
konkreten TRAIN-Endpunkte und das atomare Inventar. Kürzere Wege ersetzen
beobachtete Umwege; Ressourcenabbrüche ergeben keine Freigabe. Die zusätzlichen
84 Prüfeinheiten sind in der Lernbilanz enthalten. Dies beweist keinen globalen
Mindestabstand für jede spätere Variablenbelegung.

## Was historische Wiederentdeckung hier bedeutet

Sophie-Germain besitzt einen begrenzten Entwicklungsnachweis mit eingefrorenen
gelernten Regeln. Für Brahmagupta–Fibonacci ist zunächst ein genau geprüfter
Pfad unter vorgegebenen Phasen entstanden. Die spätere
[Resttermkomposition](schematic-proof-plan-residual-composition.md) untersucht
Paarungen und Vorzeichen und bildet die beiden klassischen Darstellungen aus
einer unabhängig eingefrorenen Quadratergänzung. Historische Korrespondenz wird
erst nach Kandidatenbildung und Freeze ausgewertet.

Diese bekannten Entwicklungsfälle sind keine frischen FINAL-TEST-Aufgaben.
Die allgemeine Resttermstrategie, die Wahl der Vorbereitung und die
familienfremde Übertragung einer selbst gelernten Taktik sind weiterhin offen.
Cassini mit Induktionszertifikat, Euklids Primzahlzeuge und eine quartische
Resolventenkonstruktion sind keine bereits gelieferten allgemeinen Fähigkeiten.

Ein unveränderter Ausgangsausdruck, der mathematisch zur Referenz äquivalent
ist, zählt nicht als Wiederentdeckung der Faktorform bei Tiefe null.
Strukturelle Korrespondenz, intrinsische Validierung, Erkennung und Ranking
werden getrennt bewertet. Alte Atlas-/Diagnoseartefakte behalten ihre
ursprüngliche Bedeutung und werden nicht nachträglich zu stärkeren Ergebnissen.
Siehe [Salienz-Audit](representation-salience-audit.md).

## Begrenzte vorhandene Lernnachweise

[Generationenbasiertes Regelmining](generational-rule-mining.md) friert jedes
Inventar vor dem Lauf ein. Neue Regeln dürfen erst in einer späteren Generation
wirken. Der kumulative Audit vergleicht `0+1` mit `0+1+2` unter demselben engen
Tiefenbudget und verlangt tatsächliche Nutzung einer neuen Regel aus Generation
2. Die Regeln werden ausschließlich in experimentellen Schatteninventaren
aktiviert.

Der bestehende [autonome Walkthrough](autonomous-discovery-walkthrough.md)
verwendet den parametrisierten Kandidaten

```text
(A + 2) * x + A * x -> (2 * A + 2) * x
```

und bewertet seine Wiederverwendung getrennt. Das ist projektinterne,
symbolisch geprüfte Wiederverwendung; keine Behauptung externer mathematischer
Neuheit. Weniger äußere Suchschritte können durch komprimierte Makros entstehen.
Lernkosten, primitive Expansion, Solver- und Überprüfungsarbeit müssen für einen
Effizienzvergleich zusätzlich berücksichtigt werden.

Die ältere targetfreie Simplification-Referenz behält das negative Ergebnis
Regelsuche 6/7 gegenüber SymPy `simplify` 7/7 für ihren eingefrorenen Bestand.
Neue optionale Fähigkeiten dürfen diesen historischen Vergleich nicht
rückwirkend verbessern. Information-Parität und weitere Baselines bleiben in
#235 separat zu bearbeiten.

<!-- capability-status:start -->
## Maschinengebundener Capability-Status

Die folgende Kurzmatrix wird aus den kanonischen Release-, Domain- und Trust-Verträgen erzeugt. Die vollständige Matrix mit Evidence-Roots steht in [`capability-status.md`](generated/capability-status.md).

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

## Noch offene wissenschaftliche Strecke

#874 verfolgt das Lernen und die Übertragung schematischer Beweispläne.
#750 behält die unerledigten historischen Studienpflichten aus den enger
abgeschlossenen Liefer-Issues #747 und #863. Dazu gehören unabhängige
Aufzählungs-/Zufallskontrollen, gemeinsame Arbeitsgrenzen, vollständige
Fallmatrizen und unabhängige Reproduktion. Die Schließung eines Liefer-Issues
bedeutet nicht, dass diese Studie stattgefunden hat.

Das stärkere Flagship-Experiment #533/#220/#521 ist nicht durch die neuen
Kompositionsbausteine freigegeben. Der reale `FROZEN_NOT_RUN`-Vertrag,
TRAIN-Ergebnisse, VALIDATION-Auswahl und genau einmal verwendeter FINAL TEST
müssen weiterhin gesondert nachgewiesen werden. Dieser Änderungsschritt führt
keinen solchen Lauf aus und verändert keine zurückgehaltenen Aufgaben.

Externe Relevanz und mathematische Neuheit bleiben getrennte Entscheidungen in
#389 und #391. Automatisierte Reviews, interne Tests und Repository-Fixtures
ersetzen keine unabhängigen Expertenurteile.

## Build und Reproduktion

Der Projektstand umfasst weiterhin die Gradle-Autorität und den vollständigen
Maven-Produkt-/Docker-Vertrag auf JDK 25. #749 verfolgt die noch nicht
abgeschlossene Umstellung auf ausschließlich Maven. Es werden keine neuen
GitHub-spezifischen Testsemantiken durch diese Komposition eingeführt.

```bash
./gradlew --no-configuration-cache ciCheck
```

Die einzelnen mathematischen und technischen Dokumentationen geben die
fokussierten Befehle an. Softwareprüfung, ausgeführtes Forschungsprotokoll,
unabhängiger mathematischer Beweis und öffentliche Release-Artefakte bleiben
getrennt nachzusehen.

## Aus Spuren abgeleitete Planvorlagen

`ExactFinitePolynomialTraceLearner` bildet aus mehreren vollständigen,
verifier-gebundenen Trainingspfaden parametrisierte Ansatzfolgen. Die neue
[Vorlagendokumentation](trace-derived-polynomial-plans.md) beschreibt die
exakten Koeffizientenlücken, bewahrten Wiederholungsbeziehungen und erneute
Prüfung jeder Anwendung. Semantisch gleiche Trainingspolynome zählen auch nach
Umordnung oder Faktorisierung nicht mehrfach. Alle Anwendungsschritte weisen
Eingaben ab, die einem beobachteten Formationszustand äquivalent sind.

Der gemeinsame unveränderliche Vorlagenvertrag verbindet Lerner und Selektor:
Formation, getrennte Auswahl-TRAIN-Matrix und neue Anwendung verwenden dieselbe
exakte Polynomidentität. Herkunft, Quellform und endliche Domänen bleiben gebunden;
die Auswahl trägt auch die Formationssperren nicht ausgewählter Vorlagen weiter.
Der begrenzte Integrationstest erzeugt zwölf Auswahlzeilen und prüft jede der
zwei späteren Theorieanwendungen frisch über die vorhandene Verifierpipeline.

Die neuen Koeffizienteninstanzen sind bekannte Entwicklungsfälle derselben
Familie, kein frischer FINAL TEST. Allgemeine Verzweigungsstrategien, unabhängige
Kontrollen, vollständige Kostenbilanz und Produktionspromotion bleiben unter
#874/#750 offen.
