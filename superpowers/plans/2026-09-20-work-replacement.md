# Regelsuche: Wissen ersetzt Sucharbeit – detaillierter Implementierungsplan

> **Ausführung:** Diesen Plan paketweise im bestehenden Repository umsetzen. Für die Implementierung `superpowers:executing-plans` beziehungsweise bei verfügbarer unabhängiger Agentenausführung `superpowers:subagent-driven-development` verwenden. Die Kontrollkästchen sind Arbeitsaufträge, keine bereits erledigten Schritte.

**Ziel:** Regelsuche soll durch geprüftes, wiederverwendbares Wissen auf getrennten Aufgaben dieselbe Ergebnisqualität mit weniger Gesamtaufwand oder bessere Ergebnisse unter demselben Gesamtbudget erreichen.

**Architektur:** Den vorhandenen Suchkern, Lerner, Regelanbieter, Programminterpreter und mathematischen Prüfer weiterentwickeln. Wissen soll unnötige Erzeugung, Fortsetzung und Auswahlentscheidungen ersetzen. Suchheuristik, mathematische Autorisierung und wissenschaftliche Bewertung bleiben getrennt.

**Technik:** Java 25 entsprechend der geprüften CI; bestehende Gradle-/Maven-Module und JUnit-Tests. Keine neue verpflichtende Laufzeitbibliothek, kein zusätzlicher Suchkern, kein neuer paralleler Lerner und kein LLM im Ausführungspfad.

**Entwurfsgrundlage:** Die fünf im Gespräch vereinbarten Richtungen werden in Abschnitt 1 konkretisiert. Abschnitt 1 ist die eingebettete Spezifikation; Abschnitte 3–6 bilden ihren Implementierungs- und Abnahmeplan.

**Stand:** 20. September 2026. Read-only-Abgleich mit PR #1047, PR #1048, Issues #696 und #874 sowie ausgewählten Implementierungsdateien. Grundlage des Codeabgleichs ist `ec1e5d40dde7b52f086621de704ac25b5a2b1c2a`, der Head von #1048. Bei der Abfrage waren #1047 offen und #1048 offen/im Draft. PR-Beschreibungen sind keine aktuelle CI-Autorität; die Ausführung beginnt mit einer erneuten Prüfung des tatsächlichen Heads.

**Dokumentstatus:** Plan, keine durchgeführte Implementierung. Vorgeschlagene Dateinamen und APIs sind ausdrücklich als solche gekennzeichnet. Die angegebenen Tests und Kommandos sind Arbeitsaufträge und wurden zur Erstellung dieses Plans nicht ausgeführt.

## Globale Grenzen

- Regelsuche selbst erhält die allgemeinen Fähigkeiten. Primachsenraum bleibt eine getrennte Anwendung und unabhängige fachliche Kontrolle, nicht der Ort eines neuen Spezialoptimierers.
- Erst bestehende geeignete PRs qualifizieren und integrieren; bereits vorhandene Komponenten nicht noch einmal implementieren.
- Ein gemeinsam verwendeter Frontier-Algorithmus, ein bestehender Lernprozess und ein bestehender Programminterpreter. Neue Adapter oder Hilfsklassen begründen keine zweite Implementierung dieser Algorithmen.
- Mathematische Wahrheit darf nicht aus Rang, Statistik, Digest, Laufzeitobjekt-ID, gespeicherter Ergebniszeile oder unaufgelöstem Plan folgen.
- Exakte Zahlen, Symbolidentität einschließlich Gültigkeitsbereich, Annahmen, Nichtkommutativität und konkrete Ausdrucksstruktur erhalten. Semantisch äquivalente Ausdrücke sind nicht automatisch austauschbare Suchzustände.
- Historische v1/v2-Artefakte, ihre Arbeitsrevisionen und bisherigen negativen Ergebnisse bleiben unverändert. Neue Verfahren erhalten explizite neue Ausführungs-/Messrevisionen.
- Bestehende Qualitäts-, Sicherheits-, Coverage- und Komplexitätsprüfungen weder absenken noch umgehen. Keine unbegründeten Erhöhungen von Domänen-, Speicher-, Matcher- oder Kandidatengrenzen.
- Vollständige begrenzte Referenzsuche und heuristische schnelle Suche bleiben unterscheidbar. Ein Budgetabbruch beweist weder mathematische Unmöglichkeit noch vollständige Nichterreichbarkeit.
- Fehlgeschlagene Suche, verworfene Regeln, erfolglose Trainingsversuche, Kompilierung, Modellladen, Wiederprüfung, Fallback und Ergebnisprüfung gehören in die Bilanz.
- Erhaltung der Nachvollziehbarkeit: strukturgeteilte Nachweise und optional gestreamte Vollprotokolle statt Weglassen der für eine zugesagte Erklärung benötigten Information.
- Kein universelles Beschleunigungsversprechen. Ein enges positives Ergebnis wird nur für seine tatsächlich geprüfte Aufgaben- und Semantikklasse freigegeben.

## Besonders zu prüfende Fehlerklassen

| Risiko | Erwartetes Verhalten | Eigentümer im Plan |
|---|---|---|
| Gleiche Schreibweise, andere Symbole/Scopes oder Annahmen | Keine falsche Zusammenführung und keine Übernahme fremder Prüfergebnisse | P04, P05 |
| Budget endet beim Öffnen, Matching, Schließen oder finalen Prüfen | Tatsächlichen Aufwand behalten; keine erfolgreiche Budgetbehauptung nach Überschreitung | P01–P03, P09 |
| Modell, Zielmetrik oder Regelbestand wechseln | Passende Cache-/Modellinvalidierung; kein stiller Weitergebrauch alter Autorität | P05, P07 |
| Zwischenschritte sind länger oder enthalten offene Verpflichtungen | Zulässige Brücken erhalten; partielle Pläne noch nicht als mathematische Kante ausgeben | P08–P10 |
| Lokale Schritte sehen unabhängig aus, beeinflussen aber Ziele oder Grenzen | Nicht reduzieren; unveränderten Suchmodus verwenden | P11 |

---

# 1. Eingebettete Spezifikation

## 1.1 Die fünf Richtungen

**A – Billige Wissensanwendung:** Kandidaten und ihre Nachweise erst bei Bedarf erzeugen. Typisierte Ausdrücke im heißen Pfad halten. Unveränderte Struktur, Merkmale und bereits geprüfte Sätze teilen.

**B – Gezielter Wissenserwerb:** Aus teuren Suchstellen und wiederkehrenden Hindernissen Trainingsfragen ableiten. Nur Wissen dauerhaft aktivieren, dessen zusätzlicher Nutzen gegen geeignete Kontrollen untersucht wurde.

**C – Gelernte Zwischenziele:** Strukturelle Entscheidungen, Bindungen und Verpflichtungen in den vorhandenen schematischen Plänen ausdrücken. Nicht lediglich konkrete lange Wege speichern.

**D – Unabhängige Reihenfolgen reduzieren:** Für ein ausdrücklich freigegebenes Fragment redundante Reihenfolgen gar nicht erst erzeugen. Unabhängigkeit der Schreibstellen allein genügt dafür nicht.

**E – Verfahren über Größen hinweg:** Als nachgelagerten Forschungsschritt wiederholbare Verfahren und Invarianten mit Basis- und Übergangsnachweis lernen. Endlich viele gelungene Beispiele ersetzen keine Induktion.

## 1.2 Zwei unterschiedliche Arbeitsaufträge

Der Ausführungsvertrag unterscheidet:

1. **Ausreichende Qualität mit minimaler Arbeit:** Eine vor der Suche festgelegte, überprüfbare Ergebnisbedingung erreichen. Ein Qualitätsgrenzwert ist kein verborgenes Zielpolynom.
2. **Beste erreichbare Qualität unter festem Budget:** Restbudget darf weiter genutzt werden; ein bereits ausreichendes Ergebnis darf nicht versehentlich als global optimal gelten.

Suchauswahl, Lernen, Trainingsexperimente und spätere Anwendung müssen jeweils denselben Vertrag verwenden. Ein Plan für Faktorisierung darf nicht allein nach kürzester Textdarstellung trainiert werden.

## 1.3 Erfolgsebenen

| Ebene | Erlaubte Aussage | Noch nicht daraus ableitbar |
|---|---|---|
| Mechanismus | Weniger Kandidaten erzeugt oder weniger redundante Fortsetzungen ausgeführt | Niedrigere Laufzeit oder amortisiertes Lernen |
| Ausführung | Gleiche geprüfte Qualität bei weniger gemessenem Aufwand im definierten Betrieb | Vorteil nach allen Erwerbskosten |
| Lebenszyklus | Erwerb, Speicherung/Laden, Auswahl, Anfragen und Prüfung zusammengenommen günstiger | Überlegenheit außerhalb der Testfamilien |
| Fähigkeit | Mehr getrennte Aufgaben unter identischem Gesamtbudget gelöst | Neue Mathematik oder allgemeine Vollständigkeit |

Als **vorgeschlagene, vor Auswertung festzuschreibende Entwicklungsziele** gelten: Faktor 2 weniger Gesamtaufwand auf einer deklarierten wiederkehrenden Aufgabenverteilung; als anspruchsvolles Ziel Faktor 10 weniger Sucharbeit auf mindestens einer anspruchsvollen, vorher festgelegten Familie. Beide Zahlen sind Zielwerte, keine Prognosen. Zeit, Arbeit und Speicher erhalten getrennte Aussagen. Ein kleinerer belastbarer Gewinn bleibt berichtenswert, erfüllt aber nicht den Faktor-2-Meilenstein.

## 1.4 Gesamtbilanz und Amortisation

Für einen Aufgabenstrom mit N Anfragen gilt:

```text
W_gelernt(N) = W_Trainingssuche
             + W_Regelbildung_und_Prüfung
             + W_Auswahltraining
             + W_Kompilierung
             + W_Laden_und_Wiederprüfung
             + Summe(W_Anfrage_i + W_Endprüfung_i + W_Ausgabe_i)
```

Die entsprechenden Grundkosten der Basis werden ebenfalls erfasst. Für Laufzeit wird der Lebenszyklus zusätzlich direkt gemessen; die Summe von CPU-Zeiten ist nicht automatisch die verstrichene Gesamtzeit. Persistenter Speicher, maximal belegter Heap, Allokationen und Prozessspeicher sind unterschiedliche Größen.

Ein Break-even-Punkt wird nur für den gemessenen/angegebenen Aufgabenstrom berichtet. Bei positiver angenommener konstanter Ersparnis d je Anfrage und zusätzlichen Aufbaukosten A ist `ceil(A/d)` lediglich eine Modellrechnung; ohne positive Ersparnis existiert in diesem Modell kein Break-even. Empirische Lebenszykluskurven haben Vorrang vor Extrapolation.

## 1.5 Faire Kontrollen

- **B0:** bisherige Referenz für historische Vergleichbarkeit; nicht die alleinige Lernkontrolle.
- **B1:** derselbe verbesserte Ausführungspfad, dieselben Standardverfahren, Prüfer und Budgets, jedoch ohne neu gelerntes Wissen.
- **L1:** B1 plus tatsächlich gelerntes, eingefrorenes Wissen und tatsächliche Auswahl.
- **L-oracle:** diagnostisch vorgegebene passende gelernte Anwendung. Nur zur Trennung von Auswahlkosten und Anwendungskosten; niemals ein Erfolgsbeleg des Gesamtsystems.
- Für gelernte Pläne zusätzlich feste generische Phasen, gültige zufällige Pläne und begrenzte Plangrammatik-Enumeration. Alle bezahlen ihre eigene Such- und Prüfungsarbeit.

Jeder Vergleich benennt sein Ziel: B0 gegen B1 misst Technik/Suchsteuerung; B1 gegen L1 misst zusätzliches Lernen. Abgewählte gelernte Regeln dürfen keinen Erfolg vortäuschen: Der tatsächliche Einsatz wird anhand des eingefrorenen Wissens-/Planbezeichners im ausgewählten Nachweis dokumentiert.

Für den Test **gleiches Gesamtbudget** gilt ein gemeinsames Limit G: Erwerb, Auswahl, Laden, alle Anfragen und deren Prüfungen müssen zusammen hineinpassen. Die Basis darf ihren nicht für Lernen ausgegebenen Anteil nach einem vorab gebundenen Ressourcenzuteiler für weitere Anfragearbeit nutzen. Gleiche Anfragebudgets plus kostenloses Training nur für L1 sind unzulässig. Gemeinsame Vorbereitungskosten, strikt getrennte Anfragegrenzen und eine mögliche Übertragung von Restbudget zwischen Anfragen werden vor dem Lauf festgelegt.

## 1.6 Informationsgrenzen

TRAIN, VALIDATION und FINAL TEST werden strukturell getrennt. Neue Variablennamen oder neue Koeffizienten derselben Schablone sind kein unabhängiger Familien-Holdout. Ein Normalform-/Strukturprüfer darf Ergebnisse nachträglich klassifizieren, aber keine Zielform an den Kandidatenerzeuger zurückgeben.

Sophie-Germain, Brahmagupta–Fibonacci und Cassini sind bereits bekannte Entwicklungsfälle. Sie bleiben aussagekräftige Charakterisierungen, sind aber nach dieser Vorgeschichte keine unberührten Finaltests. Für einen unabhängigen Fähigkeitsnachweis müssen zusätzlich neue, vor der finalen Auswertung gesperrte Familien/Generatoren verwendet werden.

## 1.7 Zustände, Belege und Daten

- **Strukturidentität:** konkrete typisierte Ausdrucksstruktur einschließlich Symbolbindung und Zahlensemantik.
- **Fortsetzungsidentität:** Strukturidentität plus Annahmen und tatsächlich zukunftsrelevanter Controller-/Verpflichtungszustand.
- **Pfadlabel:** Tiefe, primitive Arbeit, Theoriearbeit, Komplexitätsschuld und Nachweisreferenz; nicht pauschal Teil der mathematischen Identität.
- **Persistenzidentität:** deterministische versionierte Darstellung/Digest; niemals eine lokale Objekt-ID.

Dominanz darf nur auf dem ausdrücklich erfüllten Fortsetzungsvertrag beruhen. Verschiedene Darstellungen derselben mathematischen Größe können andere Regeln ermöglichen. Die vorhandenen E-Graph-Fähigkeiten sind deshalb kein Freibrief, ihre Äquivalenzklassen ungeprüft als Frontier-Identität zu verwenden.

---

# 2. Vorhandene Komponenten und Zuständigkeiten

Die folgenden Pfade sind Ausgangspunkte, keine Liste neu zu erstellender Subsysteme. Methoden und Grenzen müssen beim Beginn jeder Umsetzung am integrierten Head erneut abgeglichen werden.

| Bereich | Bestehende Anknüpfung |
|---|---|
| Frontier, Zustände, Qualitätsabschluss | `regelsuche-search/src/main/java/de/regelsuche/search/moves/MoveSearch.java`, `MoveState.java`, `MoveSearchVisits.java`, `MoveSearchObjective.java`, `SearchContinuationContract.java` |
| Typisierter Pfad | im selben Paket `TypedMoveSearch.java`, `TypedSourceOnlySearch.java` |
| Inkrementelle Anbieter | im selben Paket `IncrementalMoveProvider.java`, `IncrementalMovePicker.java`, `NativeIncrementalMoveProvider.java` |
| Cursor und Arbeitsquittung | `regelsuche-core/src/main/java/de/regelsuche/transform/TransformationCursor.java` |
| AST-Persistenzgrenze | `regelsuche-search/src/main/java/de/regelsuche/search/program/CompiledAstReplayCodec.java` |
| Regellernen | `regelsuche-learning/src/main/java/de/regelsuche/evolution/TraceRewriteStrategyLearner.java`, `CheckedLearnedSchemaModel.java`, `TypedLearnedMoveInventory.java` |
| Auswahltraining | `regelsuche-learning/src/main/java/de/regelsuche/inventory/TypedPolicySelection.java`, `TypedSourcePolicySelection.java` |
| Planrepräsentation und Auflösung | `regelsuche-learning/src/main/java/de/regelsuche/evolution/SchematicProofPlan.java`, `SchematicProofPlanResolution.java`, `ExactFinitePolynomialPlanResolver.java` |
| Exakte endliche Koeffizientenprobleme | `regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/equivalence/ExactFinitePolynomialHoleSolver.java` |
| Programmausführung | bestehende `RewriteProgram`-/`RewriteProgramInterpreter`-Implementierung im Suchmodul; Verträge in `docs/budgeted-rewrite-program-source.md` und `docs/budgeted-rewrite-program-composition.md` |
| Test-/Build-Autorität | `.github/workflows/gradle.yml`, `ciCheck`, vorhandene isolierte JMH-/SymPy-/Maven-Prüfungen |

**Issue-Zuordnung:** #696 besitzt Kandidatenerzeugung, Suchskalierung und Nutzung von Suchwissen; #661 besitzt kompakte Identität und Zustand-/Pfadtrennung; #874 besitzt schematische Pläne und Taktiktransfer; #745 bleibt Autorisierungsgrenze für Produktionsnutzung; #662 besitzt die bereits bestehende E-Graph-Linie. Dieser Plan koordiniert Teilstücke dieser Arbeiten, statt konkurrierende Sammelissues zu eröffnen.

**Wichtiger konkreter Befund:** `IncrementalMoveProvider` ist aktuell `sealed` und erlaubt nur `NativeIncrementalMoveProvider`. Seine Dokumentation schließt gelernte und Plugin-Anbieter aus. `TransformationCursor.Definition` enthält native `RuleDefinition`-Objekte. Die Integration gelernter Schemata erfordert daher eine versionierte Erweiterung von Zulassung, Definition, Quittung und Scheduling – nicht bloß einen Iterator über eine fertige Liste.

**Weiterer Befund:** `TypedMoveSearch` speichert derzeit kanonisches AST-JSON in der String-Frontier. Die geplante Objektübergabe ersetzt diesen internen Transport, nicht die exakte Symbol-/Zahlensemantik, die der Transport bisher schützt.

---

# 3. Abhängigkeiten und Meilensteine

```text
M0: #1047 -> #1048 qualifizieren und integrieren
                  |
                 P01  Abnahmevertrag und Gesamtbilanz
                  |
                 P02  Inkrementeller Zulassungs-/Schedulingvertrag
                  |
                 P03  Tatsächlich bedarfsgesteuerte gelernte Anwendungen
                  |
                 P04  Objektgebundene Zustände im vorhandenen Suchkern
                  |
                 P05  Inkrementelle Merkmale und geteilte Nachweise
                  |
                 P06  Gezielter Wissenserwerb aus Suchhindernissen
                  |
                 P07  Kleine kontextabhängige aktive Bibliothek
                  |
                 P08  Begrenzte strukturelle Pläne und Lückenauflösung
                  |
                 P09  Geprüfte Plananwendungen im vorhandenen Suchkern
                  |
                 P10  Tatsächlich gelernter Plantransfer
                  |
                 P11  Zertifizierte Unabhängigkeitsreduktion
                  |
                 P12  Rekurrenzinvarianten/Verfahren über Größen hinweg
```

P11 benötigt fachlich P04/P05 und den geklärten Controllervertrag aus P09, nicht das positive Ergebnis aller P10-Experimente. Zur Begrenzung gleichzeitiger Eingriffe bleibt die empfohlene Integrationsreihenfolge trotzdem linear. P12 baut auf der geprüften Plankette auf; ein negatives Leistungsergebnis in P11 blockiert ihn nicht. Parallel möglich sind unabhängige Testkorpus-Erstellung und externe Prüfung, nicht mehrere konkurrierende Umbauten desselben Frontier-Kerns.

| Meilenstein | Nachweis |
|---|---|
| M0 | Bestehende Grundlage integriert und tatsächlicher Main-Stand geprüft |
| M1 nach P03 | Nicht angeforderte gelernte Kandidaten verursachen keine vollständige Matching-/Anwendungs-/Belegerzeugung |
| M2 nach P05 | Gesamtausführung unter festgelegter Semantik nachweislich günstiger; kein bloß umbenannter Arbeitszähler |
| M3 nach P07 | Zusätzliches Lernen bringt einen gemessenen wirtschaftlichen Nutzen oder einen offen ausgewiesenen noch negativen Lebenszyklus |
| M4 nach P10 | Gelernte Zwischenentscheidungen wirken auf getrennten Aufgaben unter gleichem Gesamtbudget |
| M5 nach P11 | Reduktion erhält die ausdrücklich freigegebene Zielrelation und spart netto Arbeit |
| M6 nach P12 | Ein allgemeiner Basis-/Übergangsnachweis trägt Anwendungen außerhalb der Trainingsgrößen |

Ein Meilenstein enthält einen Nachweis, nicht nur neue Klassen oder grüne Unit-Tests. Ein korrektes, aber wirtschaftlich unvorteilhaftes Verfahren bleibt explizit optional und wird nicht als Standard-Verbesserung freigegeben.

---

# 4. Umsetzungspakete

## Task 0: M0 – Bestehende PRs abschließen, bevor neue Frontier-Arbeit beginnt

**Ziel:** Eine reproduzierbare Grundlage statt gestapelter halbqualifizierter Änderungen.

- [x] Für #1047 den tatsächlichen Head, offene Review-Threads, Diff zum aktuellen Main und alle erforderlichen aktuellen CI-Jobs lesen. Nicht aus der PR-Beschreibung auf einen grünen Stand schließen.
- [x] Ausschließlich zugehörige Fehler beheben; keine zusätzlichen Optimierungen in den Merge-Fix hineinziehen. Nicht zugehörige Probleme getrennt nachweisen und bearbeiten, ohne einen fehlgeschlagenen Pflichtcheck zu umgehen.
- [x] #1047 nur am qualifizierten Head integrieren. Danach #1048 mit dem neuen Main abgleichen; dessen eigene Änderungen klar vom Vorläufer trennen.
- [x] Für #1048 die neun neuen Tests, bestehende Modulprüfungen, finale Replay-Budgetgrenzen und sämtliche Pflichtprüfungen erneut ausführen/auswerten. Prüfen, dass Qualitätsmetrik und Fortsetzungsvertrag zwischen Training und Anwendung identisch bleiben.
- [x] Nach dem Merge den Main-Commit mit tatsächlichen Nachweisen als `baselineCommit` festhalten. Bereits integrierte PR-Karten nur schließen, keine alten Branches unnötig erneut mergen.

**Abnahme:** Keine unaufgelöste fachliche Review-Anforderung; erfolgreicher aktueller Pflichtlauf; gespeicherte Commit-/Workflow-/Artefaktidentitäten. Ein noch laufender Check ist weder rot noch grün und berechtigt nicht zur Erfolgsmeldung.

## Task 1: P01 – Versionierter Arbeits-, Qualitäts- und Vergleichsvertrag

**Ziel:** Jede spätere Änderung kann eindeutig zeigen, ob sie Kosten verlagert oder wirklich entfernt.

**Bestehende Dateien:** `MoveSearch.java`, `TypedSourceOnlySearch.java`, `TypedSourcePolicySelection.java` und die vorhandenen Vergleichs-/Artefaktvalidatoren erweitern, nicht ersetzen.

**Vorgeschlagene neue Dateien:** `regelsuche-learning/src/main/java/de/regelsuche/inventory/WorkReplacementExperiment.java`; `docs/research/work-replacement-evaluation-v3.md`; Tests `WorkReplacementAccountingTest` und `WorkReplacementManifestTest` im entsprechenden Lernmodul-Testpaket. Die Experimentklasse orchestriert bestehende Aufrufe; sie ist kein Optimierer.

**Ein-/Ausgabe:** Ein Manifest bindet Basiscommit, Ausführungsrevision, Regel-/Modellrevision, Informationsregime, Objektivdefinition, Saatwerte, Ressourcenlimits und Datenpartitionen. Ergebnis enthält unveränderte Rohquittungen plus ein separat versioniertes Lebenszykluskonto. Historische Quittungen werden nicht neu interpretiert.

- [x] Ein reproduzierbares Gegenbeispiel schreiben: erfolgreiche Online-Suche, deren finale Replay-Arbeit das Budget überschreitet. Erwartung: `withinBudget=false`, gesamte Arbeit erhalten, kein Budgeterfolg.
- [x] Kostenstufen getrennt erfassen: Acquisition, Selection, Compilation, Restore, Query, FinalCheck und Export. Werden Quittungen ineinander delegiert, darf jede Operation genau einmal gezählt werden.
- [x] B0, B1, L1 und L-oracle über dieselben Problem-/Prüfobjekte und frische Ausführungssitzungen betreiben. Die Oracle-Variante im Validator von Erfolgszusammenfassungen ausschließen.
- [x] Drei Betriebsprofile definieren: frischer Prozess pro Anfrage; einmal geladenes Modell über einen Strom; vorgebildetes bereitgestelltes Modell, dessen Erwerbskosten trotzdem separat im Bericht stehen.
- [x] Fehler, Zeitüberschreitungen, unlösbare Qualitätsgrenzen und ungültige Nachweise im Ergebnis halten. Laufzeitquotienten nur mit deklarierter Behandlung ungelöster Aufgaben ausgeben; nicht nur die erfolgreichen Schnittmengen als Gesamtergebnis berichten.
- [x] Profiling und ausgabearme Zeitmessung trennen. Beide Kontrollarme tragen denselben Beobachtungsmodus. Vollständige Diagnoseprotokolle dürfen gestreamt werden, ihre IO-Arbeit verschwindet nicht.

**Konkrete Regressionen:** Summe delegierter Kosten entspricht Kontosumme; Null-/Negativarbeit wird abgewiesen, wo nicht erlaubt; `long`-Überläufe führen zu explizitem Fehler statt Wraparound; vertauschte Prüf-/Modellrevisionen werden verworfen; FINAL-TEST-Quelle darf nicht nach TRAIN zurückfließen; unvollständiger Lauf darf keine fehlende Zeile verlieren.

**Prüfkommando:**

```bash
./gradlew --no-daemon :regelsuche-learning:test \
  --tests '*WorkReplacementAccountingTest' --tests '*WorkReplacementManifestTest'
```

**Abnahme:** Identische Kontrollläufe liefern identische mathematische Ausgänge und logische Quittungen; neue Konten können einen bekannten doppelten oder fehlenden Kosteneintrag nachweislich erkennen. Zeitdaten dürfen schwanken und werden nicht auf Identität getestet.

## Task 2: P02 – Inkrementelle Anbieter sicher für gelernte Ausführung öffnen

**Ziel:** Einen echten bedarfsgesteuerten Pfad ermöglichen, ohne native Schutzannahmen still zu entfernen.

**Bestehende Dateien:** `IncrementalMoveProvider.java`, `IncrementalMovePicker.java`, `NativeIncrementalMoveProvider.java`, `MoveSearch.java`, `TransformationCursor.java`.

**Vorgeschlagene neue Dateien:** im Paket `de.regelsuche.search.moves` die Klassen `RegisteredIncrementalMoveProvider.java` und `IncrementalProviderContract.java`; Tests `IncrementalProviderContractTest` und `StagedIncrementalProviderTest`.

**Schnittstelle:** Die vorhandene Operation `next(long workAllowance)` bleibt der gemeinsame Pull-Vertrag. Die neue Vertragsrevision unterscheidet native Regeldefinitionen und registrierte geprüfte Schemaanwendungen. Die zulassende Hülle liegt im Suchmodul; es entsteht keine Abhängigkeit des Suchmoduls auf den Lerner. Öffentliche Kandidatenbeschreibungen sind weiterhin untrusted und verleihen keine Proof-Autorität.

- [x] Den aktuellen Ausschluss gelernter Anbieter als Referenztest bewahren. Ein neuer Test verlangt die explizit registrierte, versionierte Variante, nicht eine pauschale Freigabe beliebiger Implementierungen.
- [x] Definitionen und Quittungen um eine deklarierte Anbieterart, Modell-/Semantikrevision und tatsächliche Anwendungskosten erweitern. Gelernte Schemata nicht als erfundene native Regeln kodieren.
- [x] Einen neuen, expliziten Schedulingvertrag für gestufte inkrementelle Anbieter im vorhandenen Picker einführen. Alte `STAGED`- und `INCREMENTAL_NATIVE_ORDER`-Aufrufe behalten ihren bisherigen Vertrag.
- [x] Native und gelernte Stufen kombinieren, ohne späteren Stufen schon beim Öffnen Kandidatenlisten abzunehmen. Parent-Fortsetzungen bleiben suspendierbar.
- [x] Zustände OPEN, READY, EXHAUSTED, LIMIT/INCONCLUSIVE, FAILED und CLOSED inklusive Restbudget und Abschlusskosten prüfen. Schließen darf idempotent sein, aber tatsächlich ausgeführte Arbeit nicht rückwirkend löschen.

**Konkrete Regressionen:** Öffnen mit Budget null erzeugt keinen Kandidaten; ein Pull gibt höchstens einen Kandidaten; erneuter Pull setzt fort und startet nicht neu; verzögerte native Stufe wird nach frühem Erfolg nicht geöffnet; falsche Modellrevision wird abgewiesen; atomarer Overrun bleibt sichtbar; alte native Resultate bleiben unverändert.

```bash
./gradlew --no-daemon :regelsuche-search:test \
  --tests '*IncrementalProviderContractTest' --tests '*StagedIncrementalProviderTest'
```

**Abnahme:** Anbietererweiterung, Scheduling und Arbeitsquittungen funktionieren unabhängig von der späteren konkreten Schemaimplementierung. Das reine Öffnen eines Anbieters wird nicht als kostenlos angenommen.

## Task 3: P03 – Gelernte Schemata wirklich erst bei Bedarf anwenden

**Ziel:** Nicht konsumierte Alternativen kosten nicht schon vollständige Anwendung und Belegerzeugung.

**Bestehende Dateien:** `CheckedLearnedSchemaModel.java`, `TypedLearnedMoveInventory.java`; die neue Hülle aus P02 verwenden.

**Vorgeschlagene neue Dateien:** `regelsuche-learning/src/main/java/de/regelsuche/evolution/CheckedSchemaCursor.java` und `CheckedSchemaMatcherPlan.java`; Tests `CheckedSchemaCursorTest`, `CheckedSchemaLazyIntegrationTest`.

**Schnittstelle:** Der Cursor hält Ausdruck, Vorkommens-Stack, relevanten Indexbereich, Matchposition und Quittung. Ein Treffer liefert über P02 einen normalen geprüften `SearchMove`; der bestehende Suchkern entscheidet über Fortsetzung und Abschluss.

- [ ] Ein echtes gelerntes Schema auf einen Ausdruck mit mehreren Anwendungspunkten anwenden. Der erste Treffer genügt dem Qualitätsvertrag. Der Test erwartet exakt eine vollständige Instanziierung und keine späteren Belege.
- [ ] Vorkommensbesuch, günstige Filter, Matching, Instanziierung, Domänenprüfung und Belegerzeugung getrennt suspendierbar machen, soweit die jeweilige Operation eine Unterbrechung erlaubt. Atomare Restkosten ausdrücklich quittieren.
- [ ] Matcherpläne für das eingefrorene Modell einmal aufbauen und deren Aufwand erfassen. Keine vollständige Modellkompilierung je Suchzustand.
- [ ] Bei Wiederaufnahme am selben Vorkommen/Index fortsetzen. Bereits geprüfte Nichttreffer nur unter unveränderter Annahmen-/Modell-/Semantikkonstellation wiederverwenden.
- [ ] Beim vollständigen Auslesen die gleiche deklarierte Kandidatenfolge wie beim bisherigen Batchanbieter erhalten. Für den Vergleich gelten identische Grenzen und Reihenfolgen; die Kosten dürfen sich unterscheiden.
- [ ] Kein `complete=true`, wenn ein Match-, Schema- oder Kandidatenlimit verbleibende Arbeit abgeschnitten hat. Ein unvollständiger Nichttreffer ist kein dauerhaft negativer Cacheeintrag.

**Regressionen:** Treffer an Wurzel und tiefem Vorkommen; mehrere mögliche Regeln; verschachtelte Substitution; nicht erfüllte Annahme; unsupported domain; Nichttreffer; Abbruch zwischen Match und Emit; frisch geladenes Modell; Budgetserie von klein bis ausreichend; vollständig ausgelesene Cursor-/Batch-Ergebnisse.

```bash
./gradlew --no-daemon :regelsuche-learning:test \
  --tests '*CheckedSchemaCursorTest' --tests '*CheckedSchemaLazyIntegrationTest'
```

**Abnahme M1:** Tatsächliche Lern-/Restore-Integration plus ein Diagnosefall mit mehreren realen Anwendungspunkten belegen, dass unerbetene Anwendungen und Belege nicht gebaut werden. Ein synthetisch teurer später Anbieter allein genügt nicht. Zusätzlich Warm-/Kaltmessung gegen B1.

## Task 4: P04 – Typisierte Zustände durch denselben Frontier-Kern führen

**Ziel:** Wiederholtes AST-JSON als internes Transportmittel entfernen, ohne Semantik oder alte Schnittstellen zu verlieren.

**Bestehende Dateien:** `MoveSearch.java`, `MoveState.java`, `MoveSearchVisits.java`, `TypedMoveSearch.java`, `CompiledAstReplayCodec.java`; AST-/Scope-Typen unverändert weiterverwenden.

**Vorgeschlagene neue Dateien:** `regelsuche-search/src/main/java/de/regelsuche/search/moves/SearchExpressionStore.java`, `SearchExpressionRef.java`, `SearchStateKey.java`; Tests `ObjectBackedMoveSearchTest`, `SearchExpressionIdentityTest`. Die Bezeichner sind vorgeschlagen; vor Erstellung mit dem dann integrierten #661-Stand abgleichen.

**Datenvertrag:** `SearchExpressionRef` gehört zu genau einer Sitzung. Dereferenzieren in einer fremden Sitzung ist ein Fehler. Ein Schlüssel enthält keine bloße Kurz-Hash-Behauptung; Hashkollisionen werden strukturell bestätigt. Der Eigentümer speichert typisierte Strukturen, keine als Expressions getarnten opaken String-IDs.

- [ ] Differentialtests für die vollständigen bisherigen Such-/Witness-Projektionen schreiben. Gleiche Syntax unter gleichen Fortsetzungsbedingungen muss gleich behandelt werden; unterschiedliche Scopes oder zulässige Fortsetzungen bleiben getrennt.
- [ ] Die interne Frontier-Repräsentation hinter bestehenden öffentlichen Fassaden vereinheitlichen. String- und typisierter Einstieg delegieren an denselben Algorithmus; keine Kopie von `MoveSearch`.
- [ ] Eingaben einmal importieren, Ergebnisbäume strukturell teilen und nur veränderte Vorfahren aufbauen. Ein erster Schritt darf ohne globale Internierung arbeiten; eine weitere Arena-Komprimierung wird nur mit gemessenem Bedarf erweitert.
- [ ] Direkt nutzbare Typobjekte für Matching, Wertung, Anwendung und Prüfung weiterreichen. Den Codec ausschließlich für tatsächliche Persistenz-/Exportgrenzen und alte explizite API-Aufrufe verwenden.
- [ ] Controllerzustand nur soweit zukunftsrelevant in den Schlüssel nehmen. Tiefe/Arbeit/Schulden als Pareto-Labels erhalten; keine alte History-Abhängigkeit heimlich als zustandslokal behandeln.
- [ ] Internierungs-/Speichergrenzen, Freigabe am Sitzungsende und Parallelisolation testen. Keine unbegrenzte globale starke Referenzsammlung.

**Regressionen:** Erzwungene Hashkollision; zwei gleich benannte verschiedene Symbole; gleiche Symbole in umbenannter Exportdarstellung; anders geklammerte Bäume; nichtkommutative Multiplikation; mathematisch äquivalente, strukturell andere Ausdrücke; fremde Sitzung; zweimaliger unabhängiger Suchlauf; Cacheeviktion.

```bash
./gradlew --no-daemon :regelsuche-search:test \
  --tests '*ObjectBackedMoveSearchTest' --tests '*SearchExpressionIdentityTest'
```

**Abnahme:** Im neuen typisierten Hot Path fallen nach Eingabeimport keine AST-JSON-Rundreisen je Frontier-Schritt mehr an. Export bleibt deterministisch und alte Artefakte bleiben lesbar. Gleiche deklarierte Ergebnisse, keine falsche Zustandszusammenführung; Speicherbilanz umfasst den Store selbst.

## Task 5: P05 – Merkmale und Nachweise strukturell teilen

**Ziel:** Unveränderte Daten nicht für jede Regelanwendung vollständig neu untersuchen.

**Bestehende Dateien:** `CheckedLearnedSchemaModel.java`, dessen Verifier, `CompiledAstReplayCodec.java`, bestehende Beleg-/Witness-Typen und Strukturwertung.

**Vorgeschlagene neue Dateien:** `regelsuche-search/src/main/java/de/regelsuche/search/moves/StructuralFactCache.java`; `regelsuche-learning/src/main/java/de/regelsuche/evolution/CheckedSchemaApplicationProof.java`; Tests `IncrementalStructuralFactsTest`, `SharedSchemaProofReplayTest`.

**Datenvertrag:** Ein Cacheeintrag ist gebunden an Ausdrucksidentität, Semantik-/Regelrevision, Assumptions und Art des Merkmals. Strukturmerkmale und tatsächlich bewiesene Eigenschaften bleiben verschiedene Kategorien. Der Anwendungsknoten referenziert geprüften Satz, Bindungen, Vorkommen und neue Wurzel; er enthält keine Kopie des gesamten gemeinsamen Nachweises.

- [ ] Tests schreiben, in denen nur ein tiefes Blatt geändert wird. Unveränderte Geschwister dürfen nicht erneut traversiert werden; von Annahmen abhängige Fakten müssen bei Annahmenwechsel verschwinden.
- [ ] Wurzelart, Operatorvorkommen, Knotenanzahl und exakte sicher berechenbare Merkmale bottom-up berechnen. Nach Änderung nur betroffene Vorfahren invalidieren. Überlauf/unsupported bleiben explizit.
- [ ] Nach Laden den allgemeinen Satz anhand seiner Semantik prüfen. Innerhalb der Sitzung seine geprüfte Autorität wiederverwenden; Digest alleine genügt beim Import nicht.
- [ ] Pro Anwendung Bindungen, Voraussetzungen und konkrete Ersetzung prüfen. Der Nachweisprüfer darf keine Kandidatenenumeration benötigen. Gemeinsame Nachweisknoten beim finalen DAG-Check einmal pro Prüfkontext validieren.
- [ ] Importierte Anwendungsknoten immer gegen echte Quellen/Bindungen prüfen. Quellen-, Ziel-, Pfad-, Binding- und Prämissenmutationen einzeln als negative Tests einbauen.
- [ ] Vollständigen Nachweis exportieren und in frischem Prozess ohne Generator-/Suchcaches prüfen. Primitive Expansion bleibt verfügbar, wo tatsächlich eine primitive Herleitung vorliegt; exakte Theorieschritte werden nicht mit erfundenen primitiven Schritten aufgefüllt.

**Regressionen:** Cachehit und Eviktion liefern gleichen Ausgang; Cache aus falschem Scope oder alten Annahmen wird nicht verwendet; gefälschter Proof-Digest; geänderte Bindung bei gleichem Regel-ID; Zyklus im Beleggraphen; mehrfach geteilte Unterbelege; beschädigtes Persistenzformat; Replays ohne ursprünglichen Lernerzustand.

```bash
./gradlew --no-daemon :regelsuche-search:test --tests '*IncrementalStructuralFactsTest'
./gradlew --no-daemon :regelsuche-learning:test --tests '*SharedSchemaProofReplayTest'
```

**Abnahme M2:** Weniger tatsächliche Objektaufbereitung/Allokationen und wiederholte Merkmalsarbeit bei gleicher geprüfter Qualität; finale unabhängige Prüfung weiterhin erfolgreich. Cold start, Warmbetrieb, retained heap und Exportkosten getrennt berichten. Diese Einsparung ist zunächst ein Technikgewinn, noch kein Lerngewinn.

## Task 6: P06 – Aus teuren Suchstellen gezielt Wissen erwerben

**Ziel:** Der vorhandene Lerner investiert bevorzugt in wiederkehrende Entscheidungen mit hohem vermeidbarem Aufwand.

**Bestehende Dateien:** `TraceRewriteStrategyLearner.java`, `CheckedLearnedSchemaModel.java`, vorhandene Beobachtungs-/Kosten-/Generalisierungsstrukturen und `TypedSourcePolicySelection.java`.

**Vorgeschlagene neue Dateien:** `regelsuche-learning/src/main/java/de/regelsuche/evolution/SearchBottleneckObservation.java`, `BottleneckTrainingScheduler.java`; Tests `BottleneckLearningTest`, `RuleMarginalUtilityTest`.

**Datenvertrag:** Eine Hindernisbeobachtung enthält strukturellen Kontext, erlaubte Fähigkeiten, Assumptions, bereits ausgegebenes Budget und Ergebnisstatus. Sie enthält kein nachträglich bekanntes TEST-Endergebnis. Der Scheduler gibt begrenzte TRAIN-Aufträge an denselben Lerner zurück.

- [ ] Einen Familienfall konstruieren, in dem viele ähnliche Wege an derselben Strukturentscheidung scheitern oder teuer erfolgreich sind. Die neue Beobachtung muss die bezahlte Engstelle benennen; Erfolg allein darf nicht die einzige Quelle sein.
- [ ] Erfolgreiche Spuren an Fähigkeitswechseln segmentieren: neue Regelgruppe anwendbar, Faktor/Quadrat sichtbar, Residual geschlossen oder exakter Solver nutzbar. Diese Marken sind durch einen überprüfbaren Effekt definiert, nicht bloß durch Textlabels.
- [ ] Budgetierte Trainingsfragen aus wiederkehrenden Segmenten bilden. Die Suche nach ihrer Abkürzung bleibt im vorhandenen Lerner; Bildung, Gegenbeispiele und Fehlschläge werden vollständig bezahlt.
- [ ] Kandidaten durch vorhandene Generalisierung und exakte Prüfung führen. Lange beobachtete Wege gegen kurze bekannte Alternativen und geeignete vorhandene Standardverfahren prüfen.
- [ ] Auf TRAIN/VALIDATION den marginalen Nutzen im aktuellen Wissensbestand messen: jeweils mit und ohne Kandidat bei gleichem Ausführungsvertrag. Synergistische kleine Gruppen gesondert zulassen; zwei zusammen nützliche Regeln nicht nur wegen isolierter Nutzlosigkeit verwerfen.
- [ ] Wissensstatus trennen: widerlegt, unsupported, begrenzt nicht nützlich, nützlich nur in Kontext K, geprüft aber noch unbewertet. Eine erfolglose begrenzte Suche ist kein globaler Minimalitäts- oder Unmöglichkeitsnachweis.

**Regressionen:** Gefundener 20-Schritt-Umweg verliert gegen vorhandenen 2-Schritt-Weg; kurze Regel mit großer vermiedener Suche kann gewinnen; teure irrelevante Regel bleibt inaktiv; komplementäre Regelpaare; kontaminierte Quelle abgewiesen; Gesamttraining über Grenzen endet mit sichtbarem Inconclusive statt Teilkostenverlust.

```bash
./gradlew --no-daemon :regelsuche-learning:test \
  --tests '*BottleneckLearningTest' --tests '*RuleMarginalUtilityTest'
```

**Abnahme:** Neu gewonnenes, geprüftes Wissen entsteht aus protokollierten Hindernissen und spart in der festgelegten Validierung netto Anfragearbeit. Erwerbskosten werden zusätzlich ausgewiesen. Ein schnellerer Anwendungspfad alleine erfüllt diese Abnahme nicht.

## Task 7: P07 – Kontextabhängige aktive Bibliothek und echte Modellwiederverwendung

**Ziel:** Mehr Wissen muss nicht bei jeder Anfrage mehr Auswahl-/Matchingarbeit verursachen.

**Bestehende Dateien:** `TypedSourcePolicySelection.java`, `TypedPolicySelection.java`, `CheckedLearnedSchemaModel.java`, `TypedLearnedMoveInventory.java`.

**Vorgeschlagene neue Dateien:** `regelsuche-learning/src/main/java/de/regelsuche/inventory/FrozenContextRulePolicy.java`, `KnowledgeActivationPolicy.java`; Tests `ContextRulePolicyTest`, `KnowledgeLifecycleTest`.

**Schnittstelle:** Eingabe sind billig verfügbare Struktur-/Auftragsmerkmale und ein unveränderlicher geprüfter Modellbestand; Ausgabe ist eine begrenzte geordnete Menge von Schema-/Planreferenzen plus ein expliziter Widening-/Fallbackvertrag. Keine mathematische Ausschlussautorität aus dem Score.

- [ ] Drei Aufgabentypen im Test: wiederkehrender Treffer, ähnliche Nichttreffer und einfache Aufgabe, die ohne Lernen sofort erledigt wird. Die Politik muss auch die leere aktive Menge zulassen.
- [ ] Aus P06-Beobachtungen eine kleine deterministische Kontextentscheidungstabelle lernen. Auswahltraining bezahlt alle verglichenen Profile; kein vollständiger Modelllauf pro Suchzustand.
- [ ] Relevante Schemaindizes und Matcher einmal je Modellgeneration aufbauen. Aktive Teilmenge nicht durch vollständiges Matching aller Regeln bestimmen.
- [ ] Unter deklariertem FAST-Budget stufenweise erweitern oder an die Basis zurückfallen. Fallback verbraucht das verbleibende Budget; es erhält keinen unsichtbaren Neustart mit vollem Budget. Die vollständige Referenz lässt keine Regel dauerhaft durch einen schlechten Rang verschwinden.
- [ ] Selektor, Zielmetrik, Kontextmerkmale, Regelbestand und Revisionen in einem ladbaren Descriptor binden. Ausführbare Prüf-/Bewertungsprofile über eine registrierte versionierte Implementierung auflösen; keine beliebigen Java-Lambdas serialisieren.
- [ ] Frische Sitzungen je Trainings-/Vergleichsfall erzeugen. Ein mutabler Provider aus dem ersten Profil darf den zweiten Lauf nicht begünstigen. Cross-query-Caches nur im ausdrücklich warmen Betriebsprofil und mit korrekter Lebenszyklusbilanz.
- [ ] Modellalterung und speicherbegrenzte aktive Indizes verwenden; inaktive geprüfte Sätze dürfen erhalten bleiben. Persistente Wissensmenge, residenter aktiver Speicher und Transpositionstabelle getrennt messen.

**Regressionen:** Gleiches geladenes Modell entscheidet deterministisch; geänderte Objektivrevision wird abgewiesen; falscher Assumption-Kontext missachtet keinen Guard; falsche Rangfolge findet über Widening noch nötige Regeln; kein Budgetrefund; alte Modellgeneration verwendet keine neuen Caches; uneindeutige Matcher-/Featurefälle fallen auf den sicheren Pfad zurück.

**Skalierung:** Zunächst 1, 8 und 32 tatsächlich geprüfte Schemata innerhalb der derzeitigen Modellgrenzen. Größere bestehende native Inventare separat messen. Tausende gelernte Regeln verlangen eine eigene ausdrücklich versionierte Gesamtbibliotheksgrenze; die bestehenden Sicherheitsgrenzen nicht nur für eine eindrucksvollere Kurve erhöhen. Im ungünstigen Fall können alle Regeln strukturell relevant sein; konstante Auswahlkosten sind keine allgemeine Garantie.

```bash
./gradlew --no-daemon :regelsuche-learning:test \
  --tests '*ContextRulePolicyTest' --tests '*KnowledgeLifecycleTest'
```

**Abnahme M3:** Ein eingefrorener, frisch wiederhergestellter Selektor benutzt tatsächlich geprüftes gelerntes Wissen auf getrennten Aufgaben. B1 und L1 verwenden denselben P02–P05-Unterbau. Erwerb und Nutzung werden über Aufgabenströme der Längen 1, 10, 100 und 1000 gemessen, soweit die vorab gesetzten Ressourcengrenzen reichen. Ein nicht erreichter Break-even wird als solcher berichtet.

## Task 8: P08 – Strukturelle Pläne und endliche Lückenauflösung vervollständigen

**Ziel:** Zwischenideen ausdrücken und mechanisch auflösen, ohne einen Ergebnis-Ausdruck vorzugeben.

**Bestehende Dateien:** `SchematicProofPlan.java`, `SchematicProofPlanResolution.java`, `ExactFinitePolynomialPlanResolver.java`, `ExactFinitePolynomialHoleSolver.java` und vorhandene Residualkomposition.

**Vorgeschlagene neue Dateien:** im Lernpaket `StructuralPlanSelector.java`, `PlanLandmark.java`; im Mathematikpaket `BoundedMonomialBasis.java`; Tests `StructuralPlanSelectorTest`, `BoundedMonomialBasisTest`, `PlanObligationIsolationTest`.

**Datenvertrag:** Ein Selector beschreibt strukturell gesuchte Stellen, nicht eine feste AST-Position des Trainingsbeispiels. Ein Landmark hat einen registrierten Prüfer, zulässiges Informationsregime und eine Ressourcenquittung. Es ist ein Fortschrittssignal, keine Gleichheitsautorität.

- [ ] Bereits vorhandene Hole-/Obligation-Arten und Solver wiederverwenden. Eine Testlücke bleibt offen, bis die zuständige Prüfung abgeschlossen ist; `UNKNOWN`, unsupported, false und Budgetabbruch bleiben getrennt.
- [ ] Strukturselektoren für relevante algebraische Teilstücke, vollständige disjunkte additive Überdeckungen und Bindungen definieren. Variablennamen, konkrete Trainingspositionen und historische Bezeichnungen dürfen nicht zum versteckten Schlüssel werden.
- [ ] Fehlende endliche Monombasen deterministisch erzeugen. Beispiel-Vertrag: Variablen `[u,v]`, Gesamtgrad höchstens 2 ergibt genau `1,u,v,u^2,u*v,v^2` in dokumentierter Ordnung. Grenze vor Enumeration prüfen; ein hartes Limit liefert Inconclusive, keine behauptete Vollständigkeit.
- [ ] Koeffizientenbedingungen aus Quelle plus eingefrorenem Ansatz herleiten. Vorhandene exakte lineare Verfahren verwenden, wenn anwendbar; sonst den deklarierten endlichen Suchpfad. Nichtlineare Erweiterungen bleiben innerhalb ausdrücklich freigegebener Solverprofile.
- [ ] Annahmen mechanisch erzeugen: beispielsweise quadratische Ergänzung mit Division durch den Leitkoeffizienten verlangt dessen Nichtnullnachweis oder einen getrennten Nullfall. Kein Koeffizientenguess umgeht diesen Guard.
- [ ] Temporäre Ausdrucksvergrößerung und offene Verpflichtungen getrennt budgetieren. Ein längerer Ausdruck wird nicht allein wegen seiner Länge aus dem Plankandidatenraum entfernt.

**Regressionen:** Nichtdisjunkte Stellen, unvollständige Quelleüberdeckung, gefälschter Residual, falsches Vorzeichen, fehlende Nichtnullannahme, Monombasisüberschreitung, mehrfach gebundene Hole-ID, Name-/Klammerungsvariationen und zulässige Nichtkommutativitätsgrenzen.

```bash
./gradlew --no-daemon :regelsuche-learning:test \
  --tests '*StructuralPlanSelectorTest' --tests '*PlanObligationIsolationTest'
./gradlew --no-daemon :regelsuche-math-algorithms:test --tests '*BoundedMonomialBasisTest'
```

**Abnahme:** Neue Fälle werden durch vorhandene allgemeine Solver und generische Selector-/Obligation-Verträge aufgelöst, nicht durch identitätsspezifische neue Regeln. Noch kein Anspruch auf autonom gelerntes Vorgehen.

## Task 9: P09 – Nur vollständig geprüfte Pläne in die bestehende Suche einführen

**Ziel:** Ein aufgelöster Plan wird ein normaler nutzbarer Schritt/Programm im vorhandenen Ablauf; unaufgelöste Vermutungen nicht.

**Bestehende Dateien:** vorhandener `RewriteProgram`-Interpreter samt budgetierten Source-/Kompositionspfaden, `MoveSearch.java`, `TypedMoveSearch.java` und bestehende Evidence-Adapter.

**Vorgeschlagene neue Dateien:** `regelsuche-learning/src/main/java/de/regelsuche/evolution/CheckedPlanCompiler.java`, `CheckedPlanMoveProvider.java`; Tests `CheckedPlanCompilerTest`, `PlanFrontierIntegrationTest`.

**Schnittstelle:** Eingang sind `SchematicProofPlan`, aufgelöste Bindungen und unabhängig bestätigte Verpflichtungen. Ausgang ist ein bestehendes Programm-/Evidence-Objekt mit Quellenbindung, Prämissen und voller Kostenquittung. Der Compile-Schritt akzeptiert nicht bloß das behauptete Wort `VALID` aus einem JSON-Dokument.

- [ ] Negativtest: vollständig gebundene Holes, aber eine offene/falsche Obligation. Kompilierung verweigert jeden mathematisch autorisierten Move.
- [ ] Bestehende `Sequence`, `Choice`, `FirstApplicable`, `Repeat` und `Require` wiederverwenden. Fokus, Landmarkabschluss und Hole-Solving nur dort als versionierte Primitive ergänzen, wo sich ihre Semantik nicht sauber durch vorhandene Knoten ausdrücken lässt.
- [ ] Vorwärtsabhängigkeiten, Wiederholungslimits, Assumptions und verfügbare Budgets vor Delegation prüfen. Quellen-/Binding-Veränderung nach Freigabe invalidiert das Ergebnis.
- [ ] Genau denselben Frontier-Kern benutzen. Planerzeugung und Hole-Suche sind budgetierte Anbieterarbeit; kein ungezählter innerer Vollsuchlauf. Falls ein suspendierter Plan zukünftige Aktionen verändert, wird sein Controllerzustand Bestandteil der Fortsetzungsidentität.
- [ ] Primitive Herleitung und exakte Theorieschritte getrennt bewahren. Eine abstrakte Planaktion darf ihre Rechenkosten nicht in einer künstlich kostenlosen Kante verstecken.
- [ ] Anwendung in frischem Prozess unabhängig prüfen. Produktionspromotionsregeln aus #745 bleiben eine zusätzliche explizite Grenze.

**Regressionen:** Gefälschte Status-/Hashfelder; andere Quelle; falsche Hole-Bindung; offener Residual; negativer Budgetrest; Loop ohne Fortschritt; gemischte primitive/Theoriepfade; wiederaufgenommener Plan; gleicher Ausdruck unter zwei nicht äquivalenten Controllerzuständen.

```bash
./gradlew --no-daemon :regelsuche-learning:test \
  --tests '*CheckedPlanCompilerTest' --tests '*PlanFrontierIntegrationTest'
./gradlew --no-daemon :regelsuche-search:test \
  --tests '*BudgetedTransformationSourceRewriteProgramTest' \
  --tests '*BudgetedRewriteProgramCompositionTest'
```

**Abnahme:** Ein allgemeiner geprüfter Plan beeinflusst die normale Suche und liefert einen eigenständig kontrollierbaren Nachweis. Die Anwendung ist technisch vorhanden, aber noch nicht als Lernerfolg gewertet.

## Task 10: P10 – Zwischenentscheidungen tatsächlich lernen und übertragen

**Ziel:** Abschnitt E von #874 in der realen Train–Freeze–Restore–Evaluate-Kette nachweisen.

**Bestehende Dateien:** `TraceRewriteStrategyLearner.java`, vorhandene Generalisierung und Planbildung, Selektor aus P07 und Anbieter aus P09.

**Vorgeschlagene neue Dateien:** im Lernpaket `CapabilityTraceSegment.java`, `LearnedPlanAbstraction.java`; Tests `LearnedPlanTransferTest`, `PlanAblationControlsTest`. Diese Klassen sind Lernhilfen des bestehenden Lerners, kein eigener Trainingsmotor.

- [ ] Spuren an den in P06 definierten Fähigkeitswechseln segmentieren. Gleichartige Segmente aus mehreren TRAIN-Familien verallgemeinern; temporäre Länge darf kein Ausschlusskriterium sein.
- [ ] Konkrete Namen, Koeffizienten und Fundstellen entfernen, aber Gleichheitsbeziehungen zwischen wiederholten Platzhaltern, Assumptions, Strukturselektoren, Verpflichtungstopologie und primitive Herkunft erhalten.
- [ ] Planvorschläge mit Gegenbeispielen und VALIDATION untersuchen. Die Wahl des Plans, seiner Grammatik und seiner Grenzen endet vor dem Finaltest. Jede erfolglose Hypothese bleibt Teil der Erwerbskosten.
- [ ] Eingefrorene Pläne und Auswahlbeschreibung speichern, in einem frischen Prozess laden und neu prüfen. Anwendung auf neuen Aufgaben muss den geladenen gelernten Plan identifizierbar im ausgewählten Nachweis enthalten.
- [ ] Gegen B1, feste generische Phasen, gültige Zufallspläne und begrenzte Enumeration vergleichen. Keine Kontrolle bekommt weniger Standardverfahren; keine Lernerseite bekommt versteckte Zielstrukturen oder zusätzliche Suchbudgets.
- [ ] Kausale Diagnose: den verwendeten Plan deaktivieren oder seine gelernte Auswahl durch feste Auswahl ersetzen. Unterschied in Kosten/Qualität messen, ohne eine bestimmte Richtung des Ergebnisses vorzutäuschen. Ein bloß geladener, nie verwendeter Plan erfüllt die Abnahme nicht.
- [ ] Zunächst bekannte algebraische Entwicklungsfamilien, anschließend gesperrte neue Strukturfamilien auswerten. Historische Korrespondenz erst nach Einfrieren des Kandidaten klassifizieren.

**Regressionen:** Neuer Kontext und andere Positionen; gleiche Struktur, andere Symbole; falsche trainingsspezifische Konstante; ähnlicher Nichttreffer; Aufgabenfamilie ohne Nutzen; notwendiger längerer Zwischenzustand; Modell ohne gelernten Plan; Zielinformationen als zusätzliche Eingabe werden im target-free Regime abgewiesen.

```bash
./gradlew --no-daemon :regelsuche-learning:test \
  --tests '*LearnedPlanTransferTest' --tests '*PlanAblationControlsTest'
```

**Abnahme M4:** Derselbe eingefrorene gelernte Plan trifft auf getrennten Aufgaben eine relevante strukturelle Zwischenentscheidung. Er erreicht mehr gültige Ergebnisse unter gleichem Lebenszyklusbudget oder dieselben Ergebnisse günstiger als B1 und die vorab benannten stärksten Kontrollen. Ein negatives Ergebnis ist vollständig zu berichten und führt zurück zur Engstellenanalyse, nicht zur Anpassung der Finaltests.

## Task 11: P11 – Nachweisbar unabhängige Reihenfolgen reduzieren

**Ziel:** Bestimmte redundante Verzweigungen vor ihrer Erzeugung vermeiden.

**Bestehende Dateien:** `MoveSearch.java`, `SearchContinuationContract.java`, die schrittweise Kandidatenerzeugung und Herkunfts-/Vorkommensnachweise.

**Vorgeschlagene neue Dateien:** `regelsuche-search/src/main/java/de/regelsuche/search/moves/MoveIndependenceContract.java`, `IndependentClosureScheduler.java`; Tests `MoveIndependenceTest`, `PartialOrderReductionDifferentialTest`.

**Erstes freigegebenes Fragment:** Explizite endliche lokale Abschlussaufgaben, bei denen alle bezeichneten Teilnormalisierungen erforderlich sind, die Schritte terminieren und weder Assumptions noch globale Bindungen ändern. Beliebige Zwischenzielsuche wird zunächst nicht reduziert.

- [ ] Positivfall mit mehreren disjunkten lokalen Reduktionen und Negativfälle mit überlappenden Stellen, globalen Guards oder Controlleränderungen anlegen.
- [ ] Lese-/Schreibabhängigkeiten, stabile Vorkommensbindung, Enablement und vertauschbare Auswirkungen prüfen. Zwei verschiedene AST-Pfade allein sind kein hinreichender Beweis.
- [ ] Nur unter einem Zielvertrag reduzieren, der die ausgelassenen Zwischenzustände nicht verlangt. Qualitäts-, Restbudget- und Komplexitätsschuld-Auswirkungen gehören zur Freigabe. Sind sie nicht invariant/begründet, auf unreduzierten Pfad zurückfallen.
- [ ] Die freigegebene Reihenfolge deterministisch wählen und Reduktionsbegründung protokollieren. Notwendige Zyklus-/Fortschrittsbedingungen erhalten. Keine Reduktion aus bloß gelernter Unabhängigkeitswahrscheinlichkeit.
- [ ] Auf vollständig enumerierbaren kleinen Räumen die erlaubten Endzustände, Nachweise und relevanten Kosten-/Zielgrenzen gegen die unveränderte Referenz prüfen. Es wird nicht behauptet, sämtliche ausgelassenen Zwischenzustände zu erhalten.
- [ ] Für 1, 2, 4 und 8 unabhängige Stellen tatsächliche erzeugte Zustände, Unabhängigkeitsprüfungen, Arbeitskosten und Laufzeit messen. Das kombinatorische k! ist keine gemessene Beschleunigung.

```bash
./gradlew --no-daemon :regelsuche-search:test \
  --tests '*MoveIndependenceTest' --tests '*PartialOrderReductionDifferentialTest'
```

**Abnahme M5:** Gleichheit der ausdrücklich freigegebenen Zielrelation im vollständigen endlichen Vergleich; intakte Prämissen und Replays; netto weniger Arbeit nach Kosten der Reduktionsprüfung. Für andere Verträge bleibt der bestehende vollständige Referenzmodus unverändert. Breitere Reduktion benötigt eine eigene Begründung statt automatischer Übertragung.

## Task 12: P12 – Forschungsschnitt: Verfahren und Rekurrenzinvarianten

**Ziel:** Nicht nur feste Schrittlängen komprimieren, sondern eine mathematisch geprüfte Regel für variable Problemgrößen gewinnen.

**Anknüpfung:** Abschnitt F von #874, vorhandene exakte Polynomverfahren und Plan-/Prüfgrenzen P08/P09. Allgemeine Quantorenlogik und ein kompletter neuer Beweisassistent gehören nicht in dieses Paket.

**Vorgeschlagene neue Dateien:** `regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/equivalence/PolynomialRecurrenceInvariantChecker.java`; im Lernpaket `RecurrenceInvariantPlan.java`; Tests `RecurrenceInvariantCertificateTest`, `InvariantTransferBeyondTrainingSizeTest`.

- [ ] Einen endlichen polynomialen Rekurrenzbereich festlegen: Zustandstupel, exakte Übergangsfunktion, maximale Grade, Verschiebungen, Koeffizienten-/Termzahlgrenzen und erlaubte Faktoren vor der Suche einfrieren.
- [ ] Invariantenansatz aus P08-Basis bilden. Triviale Nullinvariante ausschließen und äquivalente skalierte Koeffizientenvektoren kanonisieren. Koeffizienten mit vorhandenen exakten Verfahren bestimmen.
- [ ] Basisfall, allgemeine symbolische Übergangsidentität und zulässigen Gültigkeitsbereich als getrennte Verpflichtungen prüfen. Stichproben mehrerer n-Werte reichen ausdrücklich nicht aus.
- [ ] Einen spezialisierten Induktionsbeleg kompilieren, der aus Basis und Übergang die beanspruchte Familie trägt. Wiederholbare Taktiken benötigen zusätzlich Abbruch-/Fortschrittsmaß und explizite Grenzen der tatsächlichen Ausführung.
- [ ] Bekannte Rekurrenzen zunächst zur Charakterisierung nutzen. Für Transfer andere vorab zurückgehaltene Rekurrenzparameter/-strukturen und Größen außerhalb der Trainingsgrößen verwenden; keine mathematische Neuheit aus dem historischen Beispiel ableiten.
- [ ] Korrektheit, tatsächlich gelernte Koeffizienten/Struktur und vollständige Erwerbskosten getrennt berichten. Scheitert eine Grammatik am Budget, lautet das Ergebnis begrenzt unentschieden.

**Regressionen:** Richtiger Basisfall, falscher Übergang; richtiger Übergang, falscher Basisfall; Identität nur für n>0, aber behauptet n=0; leere Nullinvariante; Vorzeichenfehler; falsche Rekurrenzrevision; Koeffizientenüberlaufgrenze; nur zufällig passende Trainingswerte; frischer Zertifikatsprüfer ohne Suchhistorie.

```bash
./gradlew --no-daemon :regelsuche-math-algorithms:test --tests '*RecurrenceInvariantCertificateTest'
./gradlew --no-daemon :regelsuche-learning:test --tests '*InvariantTransferBeyondTrainingSizeTest'
```

**Abnahme M6:** Mindestens ein tatsächlich erzeugter nichttrivialer Ansatz wird durch Basis- und allgemeinen Übergangsnachweis getragen und außerhalb der Trainingsgrößen korrekt angewendet. Dies ist zunächst ein begrenzter Verfahrensnachweis; ein wirtschaftlicher Gewinn ist zusätzlich nach P01 zu belegen.

---

# 5. Gemeinsames Test- und Messprotokoll

## 5.1 Entwicklung jedes Pakets

1. Konkreten Regressionstest beziehungsweise Differentialtest schreiben; erwarteten Fehler am Ausgangsstand beobachten. Für neue APIs darf ein ausdrücklich nicht freigegebener Delegations-/Stub-Einstieg die Verhaltenslücke sichtbar machen; ein reiner Kompilierfehler genügt nicht als Verhaltensnachweis.
2. Kleinste fachlich vollständige Änderung implementieren; keine zusätzliche Optimierung außerhalb des Pakets.
3. Fokussierte Tests, betroffene Module, alte Referenz-/Artefaktprüfungen und anschließend vollständige Pflicht-CI ausführen.
4. Rohdaten, exakten Commit, Konfiguration und erfolglose Fälle behalten. Keine Entfernung unbequemer Messpunkte.
5. Unabhängiges Review nach Möglichkeit durch anderen Reviewer; Eigenprüfung nicht als unabhängige Freigabe bezeichnen.
6. PR am qualifizierten Head integrieren und den neuen Main nachprüfen. Folgepaket erst danach darauf aufbauen.

Die erste Ausführung erfolgt in isoliertem Branch/Worktree. Pro Arbeitspaket mindestens ein Test- und ein Implementierungs-/Dokumentationscommit, sofern dies den Review vereinfacht; absichtlich rote Zwischenstände bleiben ausdrücklich nicht mergefähig.

## 5.2 Pflichtmatrix

| Dimension | Fälle |
|---|---|
| Ausdruck | klein/groß, flach/tief, wiederholte Teilbäume, viele Vorkommen, Nichttreffer |
| Semantik | exakte Zahlen, getrennte Symbolscopes, Assumptions, nichtkommutative Negativkontrollen |
| Lernen | ohne Wissen, relevantes Wissen, irrelevantes Wissen, falsche Rangfolge, komplementäre Regeln |
| Prozess | kalt, warm, Save/Restore, neuer Prozess, alter Modellstand, Cacheeviktion |
| Budget | kein Rest, Grenze vor/nach Match, Anwendung, Abschluss und finaler Prüfung |
| Aufgabe | triviale Normalisierung, zusammengesetzte Einsetzungen, strukturelle Zwischenziele, variable Größe |
| Nachweis | primitive Pfade, exakte Theorieschritte, geteilte Belege, manipulierte Belege |
| Vergleich | B1 und L1 gleicher Unterbau; Oracle nur Diagnose; Plankontrollen informationsgleich |

## 5.3 Zeit, Arbeit, Tiefe und Speicher

**Arbeit:** Generation, Matching, Instanziierung, Zustandstransport, Frontier, Merkmale, Proof, innere Plansuche und externe exakte Solver. Neue Revisionen der logischen Bilanz erhalten neue Manifeste; historische Zahlen nicht direkt mit neu definierten Einheiten verrechnen.

**Zeit:** End-to-end-Anfrage und vollständiger Lebenszyklus; Prozessstart/-laden separat; CPU und Wall time nicht vermischen. JMH für isolierte Kerne, separater Durchlauf für volle Anfragen. Messreihen randomisiert/ausbalanciert ausführen; keine nachträgliche Auswahl nur günstiger Wiederholungen.

**Tiefe:** Makrotiefe, wirkliche primitive Schritte, Theoriearbeit und maximal erreichte verifizierte Struktur getrennt. Eine Planaktion zählt in der äußeren Suche möglicherweise als ein Schritt, ihre interne Arbeit bleibt sichtbar. Ein längerer Proof ist nicht automatisch eine bessere Lösung.

**Speicher:** Bytes je Anfrage allokiert, retained heap, maximaler Heap/Prozessspeicher, Modellgröße und persistente Wissensmenge. Eine größere Wissensbibliothek muss nicht weniger Gesamtspeicher benötigen; wichtig sind deklarierte Grenzen und ihr gemessener Gegenwert. Keine Behauptung geringeren Speicherbedarfs allein aus weniger besuchten Zuständen.

## 5.4 Statistische Auswertung

Vor der Endauswertung Maßzahl, Datenaufteilung und Entscheidungsregel festlegen. Rohwerte je Aufgabe, Erfolgsquote, Median und ein oberes Quantil der Laufzeit berichten; bei zu kleinen Stichproben keine scheinpräzisen Extremquantile behaupten. Wiederholungen desselben Ausdrucks sind keine unabhängigen Aufgaben. Unsicherheit auf Aufgaben-/Familienebene untersuchen; breite Intervalle ausdrücklich stehenlassen.

Ein Vorteil darf nicht ausschließlich aus dem Weglassen schwerer Aufgaben entstehen. Primär gilt die Zahl der gültigen, budgetkonformen Ergebnisse. Für Kostenvergleiche werden Aufwand auch erfolgloser Versuche und die vorab festgelegte Behandlung von Abbrüchen erfasst. Ein Modus kann für seine freigegebene Familie günstig sein und außerhalb davon beim Basis-/Fallbackpfad bleiben.

## 5.5 Integrationskommandos und CI

Fokussierte Tests in Abschnitt 4 sind Teil der künftigen Implementierung. Bestehende gemeinsame Modulprüfung:

```bash
java -version
./gradlew --no-daemon :regelsuche-core:test :regelsuche-search:test \
  :regelsuche-learning:test :regelsuche-math-algorithms:test
./gradlew --no-daemon --no-configuration-cache ciCheck
```

Java muss zum tatsächlichen Repository-Stand passen; der geprüfte Workflow verwendet Java 25. Ein lokaler anderer JDK-Lauf ersetzt dessen Qualifikation nicht. Die vollständige CI umfasst auch getrennte JMH-, SymPy- und Maven/Docker-Prüfungen. Beispielsweise sind `gradle/run-isolated-jmh-authority.sh` und `gradle/run-isolated-sympy-runtime-authority.sh` vorhandene Einstiegspunkte, deren aktuelle Voraussetzungen einzuhalten sind.

Weder ein einzelner grüner Moduljob noch ein Vergleichsartefakt ersetzt alle vorgeschriebenen Prüfungen des exakten PR-Heads.

---

# 6. Entscheidungsregeln bei negativen Ergebnissen

| Beobachtung | Konsequenz |
|---|---|
| Schon L-oracle ist teurer als B1 | Anwendung, Datenpfad, Prüfung oder Restfortsetzung untersuchen; kein größeres Rankingmodell bauen |
| L-oracle gewinnt, L1 verliert | Kontextauswahl, Index und Aktivierung bearbeiten; mathematische Regeln nicht dafür verbiegen |
| L1 gewinnt pro Anfrage, Lebenszyklus verliert | Erwerb fokussieren, Wiederverwendung und tatsächlichen Aufgabenstrom untersuchen; Break-even offen ausweisen |
| Nur wiederholte gleiche Ausdrücke gewinnen | Als Memoisierung benennen; strukturellen Transfer separat prüfen |
| Nur neue Koeffizienten derselben Schablone gewinnen | Als Instanztransfer benennen; keinen Familien-/Strategietransfer behaupten |
| B1 und L1 gewinnen gleich stark | Technikgewinn, nicht Lerngewinn |
| Nur handgeschriebene Phasen gewinnen | Nützliche Zwischenstruktur identifiziert, autonome Taktikbildung noch nicht erreicht |
| Unabhängigkeitsprüfung kostet mehr als sie spart | Reduktion standardmäßig deaktiviert lassen; Gültigkeits- und Einsatzfragment nicht künstlich erweitern |
| Größenübertragung besteht nur in numerischen Beispielen | Noch kein allgemeines Verfahren; Basis-/Übergangsbeleg verlangen |

Die Folge einer negativen Messung ist eine konkrete Diagnoseentscheidung. Keine zusätzliche Spezialdomäne nur zur Erzeugung eines positiven Diagramms, keine Abschwächung der Basis und keine nachträglich weicheren Erfolgsgrenzen.

---

# 7. Bewusste Nichtziele

Kein vollständiger neuer Logikbeweiser vor dem algebraischen Transfer. Kein neuer E-Graph neben dem vorhandenen Modul. Kein Wechsel des ganzen Systems auf ein fremdes Framework. Keine schwere neuronale Auswahl im Hot Path als erster Schritt. Keine unbewiesene globale Normalform für beliebige Regeln. Keine pauschale Zusammenführung semantisch äquivalenter, aber operational verschiedener Zustände. Keine parallele Ausführung als Ersatz für weniger Gesamtarbeit. Keine automatische Übertragung skalarer Identitäten auf Matrizen.

Standardverfahren zur Normalisierung oder linearen Algebra bleiben gleichberechtigt in B1 und L1. Falls sie eine Demo bereits sofort lösen, ist dies kein Grund, sie für die Basis zu entfernen: Dann muss der Lerner darüber hinaus einen echten Nutzen zeigen.

---

# 8. Quellen und Prüfgrundlage

Die Projektbezüge wurden über den verbundenen GitHub-Zugriff gelesen. Der Plan enthält daraus abgeleitete Vorschläge, keine Behauptung, dass diese Vorschläge bereits implementiert sind.

**Q1 – PR #1047:** Frontier-Dominanz, Online-Qualität und getrennte Nachweis-/Budgetbehandlung.

```text
https://github.com/carstenartur/Regelsuche/pull/1047
```

**Q2 – PR #1048:** Gemeinsamer Qualitätsvertrag für Auswahltraining und Anwendung.

```text
https://github.com/carstenartur/Regelsuche/pull/1048
```

**Q3 – Issue #696:** Index, native Cursor, Suchskalierung und vorhandene Zuständigkeiten.

```text
https://github.com/carstenartur/Regelsuche/issues/696
```

**Q4 – Issue #874:** Vorhandene Plan-IR, Residualkomposition, Solver, noch offene Taktikübertragung und Rekurrenzinvarianten.

```text
https://github.com/carstenartur/Regelsuche/issues/874
```

**Q5 – Geprüfte Code-/Dokumentdateien am Commit `ec1e5d40dde7b52f086621de704ac25b5a2b1c2a`:** `IncrementalMoveProvider.java`, `TransformationCursor.java`, `TypedMoveSearch.java`, `ExactFinitePolynomialPlanResolver.java`, `.github/workflows/gradle.yml`, `docs/budgeted-rewrite-program-source.md`. Ergänzend wurden die Suchergebnisse für `SchematicProofPlan`, den endlichen Polynomsolver und den noch offenen kompakten Arena-Teil gesichtet.

**Q6 – Technische Anregung, kein Framework-Wechsel:** Zhang et al., *Better Together: Unifying Datalog and Equality Saturation*, 2023. Die Arbeit motiviert inkrementelle Auswertung und gemeinsame Analysen. Ihre Leistungsergebnisse sind nicht auf Regelsuche übertragen.

```text
https://arxiv.org/abs/2304.04332
```

**Q7 – Sicherheitsrahmen für P11:** Katz und Lee, *K* and Partial Order Reduction for Top-Quality Planning*, 2023; Wehrle et al., *The Relative Pruning Power of Strong Stubborn Sets and Expansion Core*, 2013. Die Arbeiten unterstützen die Trennung zwischen zielabhängig zulässiger Reduktion und bloß syntaktisch verschiedenen Reihenfolgen. Der konkret zulässige Regelsuche-Vertrag muss eigenständig begründet und geprüft werden.

```text
https://ojs.aaai.org/index.php/SOCS/article/view/27293
https://ojs.aaai.org/index.php/ICAPS/article/view/13565
```

## Abschlussprüfung des Plans

- Alle fünf Gesprächsvorschläge besitzen Umsetzungspakete und Abnahmen.
- Vorhandene Cursor-/Plan-/Solverpfade sind Ausgangspunkt; keine zweite Suche oder zweiter Lerner vorgesehen.
- Für jede neue Reduktion sind Negativkontrollen und eine unveränderte Referenz vorgesehen.
- Lernkosten, tatsächliche Anwendung und Familiengrenzen erscheinen ausdrücklich in der Erfolgsmessung.
- P12 hat einen konkreten begrenzten Untersuchungsauftrag, keine behauptete bereits bewiesene allgemeine Lernfähigkeit.
- Die Reihenfolge beginnt mit Integration und realer Ausführung, nicht mit weiteren Spezialbenchmarks.
