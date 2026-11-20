# Strategien für Suche, Lernen und Rechenpläne

Dieser Index hält Hypothesen, Befunde und Entscheidungen aus der Entwicklung
fest. Die Versuchsdokumente enthalten Protokoll, Auswertung und Rohdaten.
Die Codeverweise führen zum aktuellen Checkout. Commit-Links halten
historische Berichte und Rohdaten unverändert auffindbar. Den Quellstand
eines Versuchs nennt sein Protokoll. Das [wissenschaftliche Manuskript](../../paper/README.md)
führt die belegten Aussagen und ihre Reproduktion.

Für die Aufrufpfade in Java, Workbench und SDK gibt es einen eigenen Abschnitt
in der [Architekturübersicht](../architecture.md#suche-lernen-und-geprüfte-rechenpläne).

Der **Umsetzungsstand** beschreibt, wo eine Strategie ausführbar ist. Der
**Befund** beschreibt die Wirkung im genannten Versuch und unter dessen
Bedingungen. Beide Angaben bei einer Änderung im selben PR aktualisieren.
Bei wiederaufgenommenen Strategien bleiben frühere Befunde über ihre
Versuchslinks auffindbar. Die [Pflegeanleitung](../documentation-conventions.md#codeverweise-im-strategien-index)
erklärt, wie sich die Zeilenverweise neu erzeugen lassen.

## Unabhängige Prüfung nativer Polynom-Beweisobjekte

- **Hypothese.** Ein begrenzter Java-Prüfer kann native Polynomgleichheitsbeweise unabhängig vom externen Solver nachrechnen, wenn er jede Umschreibung exakt prüft und die tatsächlich verwendeten Annahmen erhält.
- **Umsetzungsstand.** Der bestehende Z3-Replaypfad prüft zusammengesetzte reelle Ringterme, exakte Dezimalkoeffizienten, ganzzahlige Exponenten 1–20, Kongruenz und die benötigten booleschen Äquivalenzen. Exponent null bleibt wegen der nativen `0^0`-Semantik ausgeschlossen. Vor der Normalisierung gelten konservative Grenzen von jeweils 4.096 Bit für Koeffizientenzähler und gemeinsamen Nenner, auch bei Konstanten in variablenabhängigen Termen. Annahmeabhängige Kongruenzschritte müssen eine veränderte, noch nicht abgedeckte Argumentposition begründen; annahmefreie Koerzionsreflexivität bleibt erlaubt. Polynomblätter verwenden das vorhandene Normalform-Backend ohne Annahmen. Die optionale Prüfung ändert weder externe Solverstatus noch deren Capabilities.
- **Befund.** Die festgehaltenen Z3-4.8.12-Objekte für Distributivität, Addition von null und Dezimalkoeffizienten werden nachgeprüft; frische Ausführungen prüfen zusätzlich eine kubische Entwicklung. Das native bedingte Produktbeispiel lässt seine Annahme aus dem Beweisobjekt weg und wird abgewiesen. Gegenproben verändern Aussage, Koeffizienten, Annahmen und Beweisdatei. Zunächst fehlgeschlagene Regressionstests bestätigen nach der Korrektur die Ablehnung von Nullpotenzen, überschrittenen Koeffizientengrenzen und zusätzlichen Kongruenzannahmen ohne neu begründete Argumentänderung. [Vertrag, Grenzen und Reproduktion](../proof-bridge.md#optional-independent-polynomial-proof-replay) · [Native Eingaben und Belegdateien](../../regelsuche-solver-portfolio/src/test/resources/z3-proof-replay/README.md)
- **Folgerung.** Für diesen begrenzten Belegtyp eine zusätzliche unabhängige Prüfung anbieten. Nicht unterstützte Theorie-Lemmata und ausgeschöpfte konservative Zulassungsgrenzen bleiben ohne Replay. Allgemeine SMT-Beweisprüfung, Bindersemantik, minimale Annahmenmengen und Leistungsüberlegenheit sind damit nicht belegt; #1066 bleibt offen.

- [`Z3EqualityProofReplay.java`](../../regelsuche-solver-portfolio/src/main/java/de/regelsuche/solver/portfolio/Z3EqualityProofReplay.java#L19) <!-- strategy-code: regelsuche-solver-portfolio/src/main/java/de/regelsuche/solver/portfolio/Z3EqualityProofReplay.java | public final class Z3EqualityProofReplay { -->
- [`Z3PolynomialProofRewrites.java`](../../regelsuche-solver-portfolio/src/main/java/de/regelsuche/solver/portfolio/Z3PolynomialProofRewrites.java#L18) <!-- strategy-code: regelsuche-solver-portfolio/src/main/java/de/regelsuche/solver/portfolio/Z3PolynomialProofRewrites.java | final class Z3PolynomialProofRewrites { -->
- [`Z3EqualityProofReplayTest.java`](../../regelsuche-solver-portfolio/src/test/java/de/regelsuche/solver/portfolio/Z3EqualityProofReplayTest.java#L16) <!-- strategy-code: regelsuche-solver-portfolio/src/test/java/de/regelsuche/solver/portfolio/Z3EqualityProofReplayTest.java | class Z3EqualityProofReplayTest { -->
- [`Z3SmtSolverBackendTest.java`](../../regelsuche-solver-portfolio/src/test/java/de/regelsuche/solver/portfolio/Z3SmtSolverBackendTest.java#L35) <!-- strategy-code: regelsuche-solver-portfolio/src/test/java/de/regelsuche/solver/portfolio/Z3SmtSolverBackendTest.java | class Z3SmtSolverBackendTest { -->

## Endliche Überdeckungen durch aktivierte Kongruenzen

- **Hypothese.** Eine normalisierte Vereinigung von Kongruenzklassen kann große endliche Progressionen exakt zählen und ihren kleinsten freien Wert bestimmen, ohne die Progression punktweise zu durchlaufen.
- **Umsetzungsstand.** Der mathematische Kern stellt aktivierte Kongruenzen, allgemeine positive Schrittweiten, verallgemeinerten CRT, vorzeichenbehaftete Vereinigungen, Ressourcenbegrenzung und quellengebundene Zertifikatsprüfung bereit. Das Optimierungs-SDK reicht Anfrage, Budget und Abbruchsignal weiter. Anwendungen verantworten Herkunft und Vollständigkeit ihrer Regeln.
- **Befund.** Der Prüfvergleich enumeriert kleine zufällige Anfragen unabhängig vom CRT-Verfahren. Die großen Kontrollen enthalten einen weit entfernten freien Wert unter einer Billion Indizes, ein überdecktes Intervall mit 10^100 Indizes und periodische Termine mit einer Aktivierungslücke. Geänderte Quellen, Terme, Anzahlen und Zeugen sowie ausgeschöpfte Budgets werden abgewiesen. [Mathematik, Vergleichsverfahren, Grenzen und Reproduktion](congruence-coverage.md)
- **Folgerung.** Als gemeinsamen Rechenkern für modulare Siebe und periodische Sperren einsetzen. Greedy-Sättigungsdaten und Vermutungen bleiben im Primachsenraum-Consumer. Eine endliche Überdeckung begründet keine Aussage über alle Primzahlen; ungünstige Regelfamilien können die Termgrenze ausschöpfen.

- [`CongruenceCoverage.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/modular/CongruenceCoverage.java#L31) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/modular/CongruenceCoverage.java | public final class CongruenceCoverage { -->
- [`CongruenceCoverageTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/modular/CongruenceCoverageTest.java#L16) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/modular/CongruenceCoverageTest.java | class CongruenceCoverageTest { -->
- [`CongruenceCoverageSdk.java`](../../regelsuche-optimization-sdk/src/main/java/de/regelsuche/sdk/optimization/modular/CongruenceCoverageSdk.java#L9) <!-- strategy-code: regelsuche-optimization-sdk/src/main/java/de/regelsuche/sdk/optimization/modular/CongruenceCoverageSdk.java | public final class CongruenceCoverageSdk { -->
- [`CongruenceCoverageSdkTest.java`](../../regelsuche-optimization-sdk/src/test/java/de/regelsuche/sdk/optimization/modular/CongruenceCoverageSdkTest.java#L13) <!-- strategy-code: regelsuche-optimization-sdk/src/test/java/de/regelsuche/sdk/optimization/modular/CongruenceCoverageSdkTest.java | class CongruenceCoverageSdkTest { -->

## Restklassen zu ganzzahligen Polynomen

- **Hypothese.** Eine begrenzte Enumeration mit modularer Horner-Auswertung kann für kleine positive Moduln alle passenden Parameterreste bestimmen, auch bei mehrfachen Nullstellen und zusammengesetzten Moduln.
- **Umsetzungsstand.** Der mathematische Kern löst und prüft quellengebundene Kongruenzen beliebigen Grades innerhalb der Ressourcenlimits. Das Optimierungs-SDK reicht Anfrage, Budget und Abbruchsignal weiter.
- **Befund.** Für 250 reproduzierbar erzeugte Anfragen stimmt die Wurzelmenge mit einer unabhängigen Vollenumeration über exakte Potenzsummen überein. Kontrollen decken mehrfache Wurzeln, zusammengesetzte Moduln, leere Mengen und manipulierte Lösungen ab. [Vertrag, Vergleich und Reproduktion](polynomial-congruences.md)
- **Folgerung.** Für vollständige Restmengen bei kleinen Moduln einsetzen. Die Arbeit wächst mit Modulus und Koeffizientenzahl. Ausgeschöpfte Budgets liefern keine Teilmenge als Ergebnis.

- [`PolynomialCongruence.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/modular/PolynomialCongruence.java#L13) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/modular/PolynomialCongruence.java | public final class PolynomialCongruence { -->
- [`PolynomialCongruenceTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/modular/PolynomialCongruenceTest.java#L20) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/modular/PolynomialCongruenceTest.java | class PolynomialCongruenceTest { -->
- [`PolynomialCongruenceSdk.java`](../../regelsuche-optimization-sdk/src/main/java/de/regelsuche/sdk/optimization/modular/PolynomialCongruenceSdk.java#L9) <!-- strategy-code: regelsuche-optimization-sdk/src/main/java/de/regelsuche/sdk/optimization/modular/PolynomialCongruenceSdk.java | public final class PolynomialCongruenceSdk { -->
- [`PolynomialCongruenceSdkTest.java`](../../regelsuche-optimization-sdk/src/test/java/de/regelsuche/sdk/optimization/modular/PolynomialCongruenceSdkTest.java#L16) <!-- strategy-code: regelsuche-optimization-sdk/src/test/java/de/regelsuche/sdk/optimization/modular/PolynomialCongruenceSdkTest.java | class PolynomialCongruenceSdkTest { -->

## Quellenbindung der zurückgehaltenen Darstellungsmatrix

- **Hypothese.** Eine geprüfte Bindung an den Versuchsplan und die eingefrorenen Kandidaten verhindert, dass drei bytegleiche Kopien eine fachlich falsch zugeordnete Auswertung bestätigen.
- **Umsetzungsstand.** Der Matrixläufer prüft die Bindung vor der Qualifikation. Der Reproduktionsprüfer gleicht den Plan mit der Präregistrierung und jede Auswertung mit dem eingefrorenen Lauf ab.
- **Befund.** Im bestehenden Panel mit 144 Zeilen weist der Prüfer neu gehashte Kopien mit veränderter Formation, einem fremden Arbeitsnachweis oder einem abweichenden Kandidatenausdruck ab. [Daten, Prüfgrenzen und Reproduktion](../target-free-held-out-container-reproduction.md)
- **Folgerung.** Den Prüfpfad bei weiteren Vergleichsläufen wiederverwenden. Für die historische Kalibrierung fehlen weiterhin eingefrorene Nachweise für Archiv- und Rangstufen.

- [`TargetFreeHeldOutMatrixRunner.java`](../../regelsuche-discovery/src/main/java/de/regelsuche/discovery/representation/TargetFreeHeldOutMatrixRunner.java#L44) <!-- strategy-code: regelsuche-discovery/src/main/java/de/regelsuche/discovery/representation/TargetFreeHeldOutMatrixRunner.java | public final class TargetFreeHeldOutMatrixRunner { -->
- [`TargetFreeHeldOutContainerReproductionVerifierTest.java`](../../regelsuche-discovery/src/test/java/de/regelsuche/discovery/representation/TargetFreeHeldOutContainerReproductionVerifierTest.java#L20) <!-- strategy-code: regelsuche-discovery/src/test/java/de/regelsuche/discovery/representation/TargetFreeHeldOutContainerReproductionVerifierTest.java | class TargetFreeHeldOutContainerReproductionVerifierTest { -->

## Gelernte Brücke zur Faktorisierung

- **Hypothese.** Eine aus zwei Koeffizientenreihen gelernte Brücke spart Sucharbeit in zurückgehaltenen Aufgabenfamilien.
- **Umsetzungsstand.** Formation und gepaarter Transfervergleich im Hauptzweig.
- **Befund.** Im öffentlichen Track D ergaben 53 Matcher-Aufrufe null Anwendungen. Die native Faktorisierung erreichte alle vier positiven Ziele. [Protokoll und Wiederholung](../track-d-public-family-transfer.md) · [Gebundener Lauf](https://github.com/carstenartur/Regelsuche/blob/0d8c6545478cbb8eca50e539124c8118939055aa/research/benchmarks/track-d-public-transfer/transfer-result.json)
- **Folgerung.** Erneut prüfen, sobald eine verallgemeinerte Brücke auf den zurückgehaltenen Fällen überhaupt anwendbar ist und die native Suche dort messbare Arbeit übrig lässt.

- [`PublicFamilyTransferComparison.java`](../../regelsuche-learning/src/main/java/de/regelsuche/mining/PublicFamilyTransferComparison.java#L37) <!-- strategy-code: regelsuche-learning/src/main/java/de/regelsuche/mining/PublicFamilyTransferComparison.java | public final class PublicFamilyTransferComparison { -->
- [`PublicFamilyTransferComparisonTest.java`](../../regelsuche-learning/src/test/java/de/regelsuche/mining/PublicFamilyTransferComparisonTest.java#L13) <!-- strategy-code: regelsuche-learning/src/test/java/de/regelsuche/mining/PublicFamilyTransferComparisonTest.java | class PublicFamilyTransferComparisonTest { -->

## Zusammengesetzte Faktorisierungsregel

- **Hypothese.** Ein geprüftes Faktorisierungsmuster erspart der Suche Zwischenzustände.
- **Umsetzungsstand.** Öffentliche Qualifikation im Hauptzweig.
- **Befund.** Bei einer Regel und zwölf positiven Fällen sanken die insgesamt erkundeten Zustände von 423 auf 32. Alle zwölf Negativkontrollen bestanden. [Protokoll und Reproduktion](../autopilot-controller-comparison-results.md#qualification-after-handoff) · [Versionierte Qualifikation](https://github.com/carstenartur/Regelsuche/blob/7b3827d3ba7bd7eeb720a7b28d892b76738a50e2/research/benchmarks/track-f-stage-allocation/qualification.json.gz)
- **Folgerung.** Als positiven Referenzfall für eingesparte Suchzustände verwenden. Transfer und Gesamtkosten mit weiteren Familien prüfen.

- [`TrackFQualificationComparison.java`](../../regelsuche-release/src/main/java/de/regelsuche/release/TrackFQualificationComparison.java#L18) <!-- strategy-code: regelsuche-release/src/main/java/de/regelsuche/release/TrackFQualificationComparison.java | public final class TrackFQualificationComparison { -->
- [`TrackFQualificationComparisonTest.java`](../../regelsuche-release/src/test/java/de/regelsuche/release/TrackFQualificationComparisonTest.java#L17) <!-- strategy-code: regelsuche-release/src/test/java/de/regelsuche/release/TrackFQualificationComparisonTest.java | class TrackFQualificationComparisonTest { -->

## Gelernte Zwischenform für Faktorisierung

- **Hypothese.** Derselbe gelernte Plan lässt sich auf anders geschriebene univariate Polynome übertragen und spart gegenüber direkter Faktorisierung Arbeit.
- **Umsetzungsstand.** Ausführbarer Versuch im Hauptzweig.
- **Befund.** Der Plan erschloss zwei weitere Eingabeformen. Die Anwendung kostete 3.042 Koeffizientenprüfungen gegenüber 2.535 bei direkter Faktorisierung. Die direkte Variante löste auch den Fall mit ungeradem Linearkoeffizienten. [Versuch, Kosten und Reproduktion](https://github.com/carstenartur/Regelsuche/blob/9e072ef3bb4375f17adb107a340b22bfa328e89e/docs/trace-derived-polynomial-plans.md#übertragung-auf-andere-eingabeformen)
- **Folgerung.** Eine Zwischenform erneut prüfen, wenn sie einen folgenden Suchschritt ersetzt oder eine direkt unerreichbare Lösung erschließt.
- [`TracePlanTransferTest.java` im Versuchsstand](https://github.com/carstenartur/Regelsuche/blob/9e072ef3bb4375f17adb107a340b22bfa328e89e/regelsuche-learning/src/test/java/de/regelsuche/evolution/TracePlanTransferTest.java)

Lerner und Strategieauswahl im aktuellen Checkout

- [`ExactFinitePolynomialTraceLearner.java`](../../regelsuche-learning/src/main/java/de/regelsuche/evolution/ExactFinitePolynomialTraceLearner.java#L36) <!-- strategy-code: regelsuche-learning/src/main/java/de/regelsuche/evolution/ExactFinitePolynomialTraceLearner.java | public final class ExactFinitePolynomialTraceLearner { -->
- [`FinitePolynomialStrategySearch.java`](../../regelsuche-learning/src/main/java/de/regelsuche/evolution/FinitePolynomialStrategySearch.java#L35) <!-- strategy-code: regelsuche-learning/src/main/java/de/regelsuche/evolution/FinitePolynomialStrategySearch.java | public final class FinitePolynomialStrategySearch { -->

## Vorbereitetes Aufgabenwissen über mehrere Anfragen

- **Hypothese.** Vorbereitetes und geprüftes Wissen reduziert Wiederherstellungs- und Prüfarbeit und amortisiert eine neue Wissensgeneration.
- **Umsetzungsstand.** Persistenz, Sitzungen und Vergleich im Hauptzweig.
- **Befund.** Vorbereitete Wiederverwendung vermeidet erneute Vorbereitung gegenüber wiederholtem Laden. Gegenüber der schon persistenten Sitzung blieb der Gesamtkostenvorteil im gemischten Versuch unbelegt. [Lebenszyklusvergleich und Reproduktion](knowledge-lifecycle-improvement-2026-11-07.md#complete-observed-costs)
- **Folgerung.** Die Wissensübernahme nach der gemessenen Aufgabenmischung und gegen die persistente Sitzung bewerten.

- [`TaskKnowledgeSession.java`](../../regelsuche-learning/src/main/java/de/regelsuche/evolution/TaskKnowledgeSession.java#L9) <!-- strategy-code: regelsuche-learning/src/main/java/de/regelsuche/evolution/TaskKnowledgeSession.java | public final class TaskKnowledgeSession implements AutoCloseable { -->

- [`TaskKnowledgeSelfImprover.java`](../../regelsuche-learning/src/main/java/de/regelsuche/evolution/TaskKnowledgeSelfImprover.java#L17) <!-- strategy-code: regelsuche-learning/src/main/java/de/regelsuche/evolution/TaskKnowledgeSelfImprover.java | public final class TaskKnowledgeSelfImprover { -->
- [`TaskKnowledgeSelfImproverTest.java`](../../regelsuche-learning/src/test/java/de/regelsuche/evolution/TaskKnowledgeSelfImproverTest.java#L7) <!-- strategy-code: regelsuche-learning/src/test/java/de/regelsuche/evolution/TaskKnowledgeSelfImproverTest.java | class TaskKnowledgeSelfImproverTest { -->

## Automatische Wahl bewiesener Rekurrenzgesetze

- **Hypothese.** Der bestehende Wissenskatalog kann einen passenden Beweis für neue Übergänge wählen und gegenüber der direkten Suche Arbeit sparen.
- **Umsetzungsstand.** Automatische Auswahl, exakte Prüfung von Verschiebung oder Vorzeichenwechsel und Neuprüfung beim Laden sind in der regulären Wissenssitzung verfügbar.
- **Befund.** Bei 80 Anfragen werden alle 48 passenden Gesetze übertragen. Einschließlich Erwerb, Laden und Fehlversuchen sinkt der Aufwand von 70.336 auf 39.774 Einheiten. Bei fünf Anfragen übersteigen die Gesamtkosten die direkte Suche. [Vergleich und Reproduktion](recurrence-proof-selection.md)
- **Folgerung.** Die beobachtete Mischung und die Anzahl nachfolgender Anfragen bestimmen, wann sich die Aufbereitung lohnt.

- [`RecurrenceKnowledge.java`](../../regelsuche-learning/src/main/java/de/regelsuche/evolution/RecurrenceKnowledge.java#L19) <!-- strategy-code: regelsuche-learning/src/main/java/de/regelsuche/evolution/RecurrenceKnowledge.java | public final class RecurrenceKnowledge { -->
- [`RecurrenceProofSelectionComparisonTest.java`](../../regelsuche-learning/src/test/java/de/regelsuche/evolution/RecurrenceProofSelectionComparisonTest.java#L31) <!-- strategy-code: regelsuche-learning/src/test/java/de/regelsuche/evolution/RecurrenceProofSelectionComparisonTest.java | class RecurrenceProofSelectionComparisonTest { -->

## Inkrementelle Zustände für Zählerbeobachter

- **Hypothese.** Beobachtungen und erlaubte Änderungen reichen aus, um einen kleineren endlichen Zustand herzuleiten.
- **Umsetzungsstand.** Syntaxgebundene Ableitung, unabhängiger Prüfer und Tests im Hauptzweig.
- **Befund.** Die kürzeste Periode liefert für begrenzte Zähler mit Einfügungen und Löschungen die minimale Zahl der Tabellenzustände. Die dokumentierte Minimalität bezieht sich auf den Tabellenzustand und legale Änderungen. [Herleitung, Grenzen und Reproduktion](../derived-incremental-counter-states.md)
- **Folgerung.** Als Referenz verwenden, wenn eine Strategie Zustände aus Beobachtung und Änderungsgesetz ableitet.

- [`CounterStateSynthesis.java`](../../regelsuche-search/src/main/java/de/regelsuche/search/incremental/CounterStateSynthesis.java#L13) <!-- strategy-code: regelsuche-search/src/main/java/de/regelsuche/search/incremental/CounterStateSynthesis.java | public final class CounterStateSynthesis { -->
- [`CounterStateSynthesisTest.java`](../../regelsuche-search/src/test/java/de/regelsuche/search/incremental/CounterStateSynthesisTest.java#L9) <!-- strategy-code: regelsuche-search/src/test/java/de/regelsuche/search/incremental/CounterStateSynthesisTest.java | class CounterStateSynthesisTest { -->

## Blockzustände für Punktänderungen an geordneten Summen

- **Hypothese.** Aus der Abhängigkeit zwischen zwei Teilblöcken lassen sich die nötigen Zustandsgrößen ableiten und bei einzelnen Wertänderungen zusammenführen.
- **Umsetzungsstand.** Exakte Synthese und Prüfung im Java-Kern und über das SDK für unbeschränkte geordnete Tupel.
- **Befund.** Der hergeleitete Plan stimmt für acht Punktänderungen auf 100.000 Zeilen mit der linearen Delta-Referenz überein. Jedes Update benötigt 17 Blockkombinationen. Im Dreieraufgaben-Vergleich spart die wiederverwendete Teilidentität 77 von 7.980 Arbeitseinheiten einschließlich Lernen und Prüfung. [Prüfverfahren, Daten und Reproduktion](ordered-block-reductions.md)
- **Folgerung.** Den Beitrag gelernter Regeln zur Ableitung der Blockgrößen auf unabhängig erstellten Aufgaben messen.

- [`OrderedReductionSearch.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/OrderedReductionSearch.java#L24) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/OrderedReductionSearch.java | public final class OrderedReductionSearch { -->
- [`OrderedReductionKnowledge.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/OrderedReductionKnowledge.java#L23) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/OrderedReductionKnowledge.java | public final class OrderedReductionKnowledge { -->
- [`OrderedBlockReductionComparisonTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/OrderedBlockReductionComparisonTest.java#L23) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/OrderedBlockReductionComparisonTest.java | class OrderedBlockReductionComparisonTest { -->

## Geprüfter Paarzustand für geordnete Dreiersummen

- **Hypothese.** Ein aus einer Paarsumme übernommener Zustand erspart eine erneute Ableitung der benötigten Paarstatistiken in Dreiersummen.
- **Umsetzungsstand.** Exakte Übertragung auf ein Paar am Anfang oder Ende einer Dreierformel. Die Herleitung der gleichen Gruppierung direkt aus der Formel dient als Vergleich.
- **Befund.** Sechs passende Aufgaben verwenden den Paarzustand wieder. Inklusive Wissenserwerb und der beiden nicht passenden Kontrollen sinkt der Planungsaufwand von 11.200 auf 10.889 Arbeitseinheiten gegenüber direkt hergeleiteter Gruppierung, wenn der Paarplan bereits vorliegt. Beim Kaltstart braucht die Lernvariante 85 Einheiten mehr. Die Baumkosten sind gleich. [Daten und Reproduktion](pair-state-transfer.md)
- **Folgerung.** Die Wiederverwendung auf unabhängigen Aufgaben mit vorgeschalteter oder nachfolgender Paarabhängigkeit messen.

- [`OrderedPairStateProjection.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/OrderedPairStateProjection.java#L25) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/OrderedPairStateProjection.java | public record OrderedPairStateProjection(boolean pairFirst, List<Term> pairTerms, List<RowTerm> rowTerms) { -->
- [`OrderedPairStateTransferTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/OrderedPairStateTransferTest.java#L16) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/OrderedPairStateTransferTest.java | class OrderedPairStateTransferTest { -->

## Gemeinsamer Faktor für quadratische und kubische Nenner

- **Hypothese.** Für zwei monische Nenner (X = D^2) und (Y = D^3) lässt sich (D) durch die Division (Y / X) herleiten und anhand beider Potenzen prüfen.
- **Umsetzungsstand.** Der Java-Kern stellt die Herleitung, die gesonderte Koeffizientenprüfung und den Vorschlag eines exakten Modulus für reelle Koeffizienten über das Optimierungs-SDK bereit. Für den Modulusvorschlag begrenzt die Herleitung die Bitlänge der ganzzahligen Zwischenwerte.
- **Befund.** Die Herleitung stimmt mit den ausmultiplizierten Referenzkoeffizienten für einen 381-Bit-Modulus und dessen quadratische Erweiterung überein. Tests verwerfen falsche Nenner, auch wenn die Division aufgeht, und prüfen den Abbruch bei ausgeschöpftem Arbeitsbudget. Bei ganzzahligen Resten liefert ihr größter gemeinsamer Teiler einen für genau diesen Modulus nachgeprüften Vorschlag. Die Tests führen beide Rechenwege mit `mvn -pl regelsuche-math-algorithms -am -Dtest=RationalDenominatorReductionTest -Dsurefire.failIfNoSpecifiedTests=false test` aus.
- **Folgerung.** Bei Java-Quellen die Herkunft der Nenner und den Laufzeitmodulus prüfen. Die Laufzeitwirkung gegen beide ursprünglichen Nennerauswertungen messen.

- [`RationalDenominatorReduction.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/polynomial/RationalDenominatorReduction.java#L15) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/polynomial/RationalDenominatorReduction.java | public final class RationalDenominatorReduction { -->
- [`RationalDenominatorReductionTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/polynomial/RationalDenominatorReductionTest.java#L16) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/polynomial/RationalDenominatorReductionTest.java | class RationalDenominatorReductionTest { -->

## Vorgegebener Zustand als Kontrolle für eingesparte Suche

- **Hypothese.** Ein vorgegebener, prüfbarer Zwischenzustand ersetzt bei gleicher Ergebnisqualität mindestens 20 Prozent bezahlte Arbeit.
- **Umsetzungsstand.** Vorab festgeschriebenes Panel mit Java-Reduktionen und historischen Rekurrenzen. Für homogene Rekurrenzen wurde eine stärkere direkte Gradaufteilung als zusätzliche Kontrolle ausgeführt.
- **Befund.** Die Java-Läufe sparen 24 von 7.412 Einheiten. Die direkte Gradaufteilung senkt den Rekurrenzaufwand von 25.930 auf 13.968 Einheiten. Auf dieser Basis spart der vorhandene Hinweis 4,27 Prozent. Einschließlich seiner Eingangsprüfung und Extraktion kostet er 17.078 Einheiten. [Protokoll, Arbeitsbelege und Reproduktion](state-choice-oracle.md)
- **Folgerung.** Gradgetrennte direkte Suche als Vergleichsgrundlage verwenden. Für Lernversuche unabhängig ausgewählte, gekoppelte Aufgaben heranziehen, bei denen dieser direkte Vergleich erhebliche Sucharbeit übrig lässt.

- [`RecurrenceInvariantSynthesis.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/linalg/RecurrenceInvariantSynthesis.java#L28) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/linalg/RecurrenceInvariantSynthesis.java | public final class RecurrenceInvariantSynthesis { -->
- [`OrderedStateChoiceExperimentTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/OrderedStateChoiceExperimentTest.java#L25) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/OrderedStateChoiceExperimentTest.java | class OrderedStateChoiceExperimentTest { -->
- [`RecurrenceStateChoiceExperimentTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/RecurrenceStateChoiceExperimentTest.java#L32) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/RecurrenceStateChoiceExperimentTest.java | class RecurrenceStateChoiceExperimentTest { -->

## Gradaufteilung in der vollständigen Rekurrenzsuche

- **Hypothese.** Bei linearen Übergängen ohne Verschiebung lassen sich die Gradblöcke unabhängig lösen und anschließend in der bisherigen Reihenfolge ausgeben.
- **Umsetzungsstand.** Der reguläre Aufruf durchsucht alle nichtkonstanten Grade für beide Vorzeichen. Affine Übergänge mit Verschiebung behalten den gekoppelten Ansatzraum.
- **Befund.** Ein Budget von 10.000 Einheiten genügt für alle fünf Gesetze des dreidimensionalen Vergleichsfalls. Der frühere Aufruf benötigt 11.686 Einheiten.
- **Folgerung.** Die vollständige Gradaufteilung dient als Referenz für Vergleiche zur weiteren Reduktion von Koeffizienten- und Prüfarbeit. [Festgelegte gekoppelte Aufgaben](../../config/benchmarks/coupled-recurrence-v1.json)

- [`RecurrenceInvariantSynthesisTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/RecurrenceInvariantSynthesisTest.java#L20) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/RecurrenceInvariantSynthesisTest.java | class RecurrenceInvariantSynthesisTest { -->

## Direkte Koeffizientenmatrix und Übernahme bewiesener Rekurrenzgesetze

- **Hypothese.** Typisierte Monombilder verringern die Bildung der Koeffizientensysteme. Ein exakt passender Übergangswechsel erlaubt die Wiederverwendung des Beweises.
- **Umsetzungsstand.** Der reguläre Aufruf bildet die Matrix direkt und prüft jedes Ergebnis unabhängig. Verschiebung und Vorzeichenwechsel wurden mit festen Ankerzuordnungen im Test ausgewertet.
- **Befund.** Die Gesamtarbeit der sechs gekoppelten Aufgaben sinkt von 57.709 auf 23.068 Einheiten. Der Transferversuch kommt bei denselben neun Gesetzen auf 16.225 Einheiten einschließlich der Anker und der erfolglosen Zuordnungen. [Aufgaben, Protokoll und Reproduktion](coupled-recurrence-synthesis.md)
- **Folgerung.** Die Kosten automatischer Ankerzuordnung gegen die direkte Matrixbildung prüfen. Für konkrete Beobachtungen den minimalen Rekurrenzzustand aus den Monombildern bestimmen.

- [`CoupledRecurrenceExperimentTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/CoupledRecurrenceExperimentTest.java#L28) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/CoupledRecurrenceExperimentTest.java | class CoupledRecurrenceExperimentTest { -->
- [`CoupledRecurrenceTransferExperimentTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/CoupledRecurrenceTransferExperimentTest.java#L32) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/CoupledRecurrenceTransferExperimentTest.java | class CoupledRecurrenceTransferExperimentTest { -->

## Wiederverwendete Beobachtungspläne bei wechselndem Anfangszustand

- **Hypothese.** Die einmalige geprüfte Herleitung kann sich bei mehreren Anfangszuständen einer festen Beobachtung amortisieren.
- **Umsetzungsstand.** Vor dem Lauf festgeschriebener Java-Vergleich auf der Land-of-Oz-Markov-Kette und drei veröffentlichten Leslie-Matrizen. Die direkte Rechnung verwendet vorbereitete Beobachtungszeilen über die Starts hinweg.
- **Befund.** In 30 vollständigen Strömen liefern alle drei Verfahren je 1.380 gleiche exakte Werte. Wiederverwendung senkt die Arbeit von 206.268 auf 116.208 Einheiten gegenüber erneuter Herleitung. Die direkte Rechnung benötigt insgesamt 42.564 Einheiten und ist in jedem Strom günstiger. Beim ersten verstreuten Schönwetterstapel liegt der Plan der Ordnung zwei vorn. [Protokoll, Arbeitsbelege, Laufzeit und Allokationen](external-recurrence-observations.md)
- **Folgerung.** Bei wiederholten Indizes über verschiedene Starts muss der Vergleich die direkte Wiederverwendung von Matrixprojektionen einrechnen. Der Befund betrifft zwei veröffentlichte Modellfamilien und feste lineare Beobachtungen.

- [`DirectLinearObservation.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/DirectLinearObservation.java#L13) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/DirectLinearObservation.java | final class DirectLinearObservation { -->
- [`ExternalRecurrenceObservationStudyTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/ExternalRecurrenceObservationStudyTest.java#L41) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/ExternalRecurrenceObservationStudyTest.java | class ExternalRecurrenceObservationStudyTest { -->

## Minimale Rekurrenz für eine Beobachtung

- **Hypothese.** Ein kleiner Krylov-Raum der Zielbeobachtung kann den Aufwand für weit entfernte Abfrageindizes senken.
- **Umsetzungsstand.** Der Java-Kern bestimmt und prüft die universell minimale Ordnung für rationale affine Übergänge mit bis zu drei Koordinaten und Beobachtungen bis Gesamtgrad zwei. Der geprüfte Plan kann für verschiedene Anfangszustände gebunden werden und dieselben vorbereiteten Indizes und Beobachtungsformen wiederverwenden.
- **Befund.** Alle acht Läufe liefern dieselben exakten Werte wie binäre affine Potenzierung mit Wiederverwendung im Stapel. Die Beobachtung der Ordnung eins spart bei 24 Abfragen 89,3 Prozent. Bei den drei anderen Aufgaben kostet direkte Rechnung weniger. [Vergleich und Reproduktion](recurrence-observation-krylov.md)
- **Folgerung.** Die automatische Wahl und das Teilen der Indexarbeit anhand der erwarteten Anfragen auswerten.

- [`RecurrenceObservationKrylov.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/linalg/RecurrenceObservationKrylov.java#L32) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/linalg/RecurrenceObservationKrylov.java | public final class RecurrenceObservationKrylov { -->
- [`RecurrenceObservationComparisonTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/RecurrenceObservationComparisonTest.java#L33) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/RecurrenceObservationComparisonTest.java | class RecurrenceObservationComparisonTest { -->

## Geprüfte Modelle aus gespeicherten Folgenkandidaten

- **Hypothese.** Ein gespeicherter Folgenkandidat lässt sich unter seiner ursprünglichen Quellenbindung in ein explizites Modell überführen, dessen Rekurrenz und einzelne Werte unabhängig geprüft werden können.
- **Umsetzungsstand.** Die beiden vorhandenen Folgendomänen sind über identisches Quellen-Replay, eine getrennte endliche Datenprüfung und eine begrenzte affine Abbildung mit dem bestehenden Krylov-Prüfer verbunden. Die Workbench speichert den Beleg getrennt vom unveränderten Export und reproduziert ihn vor dem erneuten Download.
- **Befund.** Die Kontrollen prüfen konstante, lineare, quadratische und kubische Differenzmodelle sowie rationale und unsymmetrische Rekurrenzen. Für das aus `1,4,9,16` mit Holdout `25,36` gebundene Modell ergibt sich an Index 100 exakt `10201`. Der Beweis gilt für das explizite Modell; eine eindeutige unendliche Fortsetzung der ursprünglichen endlichen Daten ist damit nicht bewiesen. [Ablauf und Grenzen](../domain-model-proof.md)
- **Folgerung.** Der Modellbeleg ermöglicht nachvollziehbare Folgeabfragen. Neuheit, Promotion, Release-Freigabe und gemeinsame vergleichende Arbeitsbewertung bleiben eigenständige Anforderungen von #1020.

- [`DomainModelProof.java`](../../regelsuche-discovery/src/main/java/de/regelsuche/discovery/domain/DomainModelProof.java#L20) <!-- strategy-code: regelsuche-discovery/src/main/java/de/regelsuche/discovery/domain/DomainModelProof.java | public final class DomainModelProof { -->
- [`DomainModelProofTest.java`](../../regelsuche-discovery/src/test/java/de/regelsuche/discovery/domain/DomainModelProofTest.java#L17) <!-- strategy-code: regelsuche-discovery/src/test/java/de/regelsuche/discovery/domain/DomainModelProofTest.java | class DomainModelProofTest { -->

## Automatisch hergeleitete Zustände für wechselnde Übergänge

- **Hypothese.** Der unter allen erlaubten Übergängen abgeschlossene Beobachtungsraum liefert in zwei Aufgabenfamilien mindestens 20 Prozent weniger Gesamtarbeit als direkte Auswertung.
- **Umsetzungsstand.** Der Java-Kern leitet aus rationalen affinen Übergängen und Beobachtungen eine minimale affine Zustandszusammenfassung ab. Er prüft jeden Übergang und jede Beobachtung exakt und verwendet den Plan für beliebige Ereignisfolgen und Anfangszustände.
- **Befund.** Bei 1.024 Ereignissen sinkt die Dimension für Markov-Transport von sechs auf drei und für den Schwerpunkt eines Federsystems von vier auf zwei. Einschließlich Herleitung, Prüfung, Bindung und Ausgaben sinkt die Arbeit um rund 68 beziehungsweise 28 Prozent. Bei einem Ereignis und in den beiden Kontrollen ist die direkte Rechnung günstiger. [Versuchsaufbau und Rohdaten](switched-observation.md)
- **Folgerung.** Die Amortisierung auf unabhängig kuratierten Anwendungen prüfen und vor der Herleitung mit direkter Koordinatenabhängigkeit und erwarteter Abfragelänge abschätzen.

- [`AffineObservationReduction.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/linalg/AffineObservationReduction.java#L26) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/linalg/AffineObservationReduction.java | public final class AffineObservationReduction { -->
- [`AffineObservationReductionTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/AffineObservationReductionTest.java#L21) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/AffineObservationReductionTest.java | class AffineObservationReductionTest { -->
- [`AffineObservationComparisonTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/AffineObservationComparisonTest.java#L36) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/AffineObservationComparisonTest.java | class AffineObservationComparisonTest { -->

## Budgetierte Wahl zwischen direkter Auswertung und Zustandsreduktion

- **Hypothese.** Die Abhängigkeiten der Beobachtung und die erwartete Zahl der Ereignisse oder Abfragen reichen aus, um manche kostspieligen und wenig ergiebigen Herleitungen zu vermeiden.
- **Umsetzungsstand.** Der Java-Kern wählt für affine Beobachtungen zwischen direkter Rechnung und geprüfter Reduktion. Bei festen indizierten Abfragen teilt er direkte Matrixprojektionen oder Rekurrenzreste über Anfangszustände.
- **Befund.** Auf drei kurzen und einem langen Lauf mit wechselnden Übergängen benötigt die automatische Wahl 7.565 Arbeitseinheiten. Durchgängig direkte Rechnung benötigt 14.774 und durchgängige Zustandsreduktion 8.674. Alle Ausgaben stimmen exakt überein. [Aufgabenprofil und Reproduktion](recurrence-observation-krylov.md#automatische-wahl-für-lineare-beobachtungen)
- **Folgerung.** Die Auswahlkosten auf verschiedenen Arbeitslasten prüfen, insbesondere bei unbekanntem Anfragehorizont und wenig reduzierbaren Zuständen.

- [`AffineObservationEvaluation.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/linalg/AffineObservationEvaluation.java#L29) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/linalg/AffineObservationEvaluation.java | public final class AffineObservationEvaluation { -->
- [`AffineObservationEvaluationTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/AffineObservationEvaluationTest.java#L26) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/linalg/AffineObservationEvaluationTest.java | class AffineObservationEvaluationTest { -->

## Gewichtete projektive Übergaben

- **Hypothese.** Haben die Nenner eines rationalen Zustands eine gemeinsame Basis, kann ein nachfolgender Rechenschritt Zähler und Basis übernehmen. An einer affinen Ausgabegrenze reicht eine Inversion für alle Koordinaten.
- **Umsetzungsstand.** Der Java-Kern sucht eine Basis in expliziten Produkten und Potenzen der Ausgangsnenner und prüft auch vorgegebene Kandidaten. Er prüft die Identitäten und die gemeinsame Einheitenbedingung mit exakten ganzzahligen Polynomnormalformen. Der SDK-Einstieg übernimmt Budget und Abbruchsignal.
- **Befund.** Im [Vektormodell](projective-handoffs.md) mit drei Koordinaten und Gewichten 1, 2 und 4 liefert die affine Ausgabe mit einer Inversion dieselben exakten Werte wie drei einzelne Divisionen. Die modellierte Kette spart vier von fünf Inversionen und kostet zusätzlich 13 Multiplikationen und zwei Einheitenprüfungen.
- **Folgerung.** Inversionsersparnis und Gesamtkosten getrennt bewerten. Für eine Übernahme über Methodengrenzen muss der Aufrufer zeigen, dass der folgende Schritt den projektiven Zustand verarbeitet und der ursprüngliche Singularitätspfad erhalten bleibt.

- [`ProjectiveHandoff.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/ProjectiveHandoff.java#L29) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/ProjectiveHandoff.java | public final class ProjectiveHandoff { -->
- [`ProjectiveHandoffSdk.java`](../../regelsuche-optimization-sdk/src/main/java/de/regelsuche/sdk/optimization/iteration/ProjectiveHandoffSdk.java#L11) <!-- strategy-code: regelsuche-optimization-sdk/src/main/java/de/regelsuche/sdk/optimization/iteration/ProjectiveHandoffSdk.java | public final class ProjectiveHandoffSdk { -->
- [`ProjectiveHandoffTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/ProjectiveHandoffTest.java#L19) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/ProjectiveHandoffTest.java | class ProjectiveHandoffTest { -->

## Zustandslokale Wiederverwendung von Suchfortsetzungen

- **Hypothese.** Gleiche Ausdrücke, Annahmen und Fähigkeiten können bei zustandslokalen Regeln dieselbe Fortsetzung verwenden. Bei unterschiedlichen Pfadbudgets bleiben nichtdominierte Pfade erhalten.
- **Umsetzungsstand.** Die optionale FAST-Suche prüft vor jedem Indexvergleich das verbleibende Arbeitsbudget und kann dominierte Einträge gemeinsam ersetzen.
- **Befund.** Die Graphkontrolle behält denselben erreichbaren Ausdrucksbestand mit 43 statt 84 Expansionen. Bei 15 Einheiten für die Ein-Schritt-Kontrolle ist die Verifikation abgeschlossen. Der Index meldet Budgetende. Die frühere Variante gab noch zwei Einheiten für Aufnahme und weitere Einplanung aus. Mit ausreichendem Budget bleibt das Ziel in einem Primitivschritt erreichbar. [Kontrollen und Reproduktion](work-replacing-search.md#development-diagnostics)
- **Folgerung.** Zustandslokale Wiederverwendung bleibt an den Fortsetzungsvertrag gebunden. Weitere Aussagen zum Nutzen erfordern einen Vergleich auf realen Aufgaben mit vielen wiederholten Zuständen.

- [`MoveSearchVisits.java`](../../regelsuche-search/src/main/java/de/regelsuche/search/moves/MoveSearchVisits.java#L13) <!-- strategy-code: regelsuche-search/src/main/java/de/regelsuche/search/moves/MoveSearchVisits.java | final class MoveSearchVisits<S extends SearchExecution.Position<?>> implements de.regelsuche.retention.RetainedGraph.View { -->
- [`MoveSearchDominanceTest.java`](../../regelsuche-search/src/test/java/de/regelsuche/search/moves/MoveSearchDominanceTest.java#L13) <!-- strategy-code: regelsuche-search/src/test/java/de/regelsuche/search/moves/MoveSearchDominanceTest.java | class MoveSearchDominanceTest { -->

## BestFirst-Fortsetzungen bei dauerhaftem Suchgedächtnis

- **Hypothese.** Dauerhafte Wertbeobachtungen dürfen keine Fortsetzung abschneiden, deren Zulässigkeit von der im aktuellen Lauf verwendeten Anwendungshistorie abhängt.
- **Umsetzungsstand.** BestFirst zeichnet dauerhafte Beobachtungen für alle Zielmodi auf; über erneute Besuche entscheidet der bestehende typisierte lokale Schlüssel aus Wert, Regelanwendungen und Annahmen. Migration älterer Identitäten und Annahmengruppierung bleiben erhalten. Direkte Nutzer des Transposition-Gates behalten ihr bisheriges Verhalten.
- **Befund.** Mit der eingebauten Potenz-von-Potenz-Regel erreicht die Suche ohne Gedächtnis aus `((x^2)^2)^2` über `(x ^ 4) ^ 2` den Ausdruck `x ^ 8`. Die frühere Variante mit frischem Gedächtnis verlor diese Fortsetzung; wiederholte und vorgewärmte Läufe konnten beim Wurzelausdruck stehen bleiben. Nach der Trennung stimmen die vollständigen erkundeten Zustände und der konkrete Pfad in den Regressionen mit der Suche ohne Gedächtnis überein, einschließlich fortgesetzter Wertzielsuchen und A*. [Vertrag, Vergleich und Grenzen](../search-intelligence.md#continuation-regression-and-limits)
- **Folgerung.** Dauerhafte Wertstatistik getrennt von einer begründeten Wiederverwendung von Fortsetzungen behandeln. Der Befund betrifft eine reduzierte Syntax und einen besseren Score, keine neu erkannte Wertgleichheit. Allgemeine Vollständigkeit, Nutzen beim Skalieren und die Sicherheit anderer Suchstrategien sind damit nicht belegt; Issue #696 bleibt offen.

- [`BestFirstSearchStrategy.java`](../../regelsuche-search/src/main/java/de/regelsuche/search/strategy/BestFirstSearchStrategy.java#L32) <!-- strategy-code: regelsuche-search/src/main/java/de/regelsuche/search/strategy/BestFirstSearchStrategy.java | public class BestFirstSearchStrategy implements SearchStrategy { -->
- [`BestFirstMemoryContinuationTest.java`](../../regelsuche-search/src/test/java/de/regelsuche/search/strategy/BestFirstMemoryContinuationTest.java#L15) <!-- strategy-code: regelsuche-search/src/test/java/de/regelsuche/search/strategy/BestFirstMemoryContinuationTest.java | class BestFirstMemoryContinuationTest { -->
- [`BestFirstSearchTelemetryTest.java`](../../regelsuche-search/src/test/java/de/regelsuche/search/strategy/BestFirstSearchTelemetryTest.java#L26) <!-- strategy-code: regelsuche-search/src/test/java/de/regelsuche/search/strategy/BestFirstSearchTelemetryTest.java | class BestFirstSearchTelemetryTest { -->
- [`GoalAwareSearchTest.java`](../../regelsuche-search/src/test/java/de/regelsuche/search/strategy/GoalAwareSearchTest.java#L28) <!-- strategy-code: regelsuche-search/src/test/java/de/regelsuche/search/strategy/GoalAwareSearchTest.java | class GoalAwareSearchTest { -->
- [`TranspositionTableIntegrationTest.java`](../../regelsuche-search/src/test/java/de/regelsuche/search/memory/TranspositionTableIntegrationTest.java#L13) <!-- strategy-code: regelsuche-search/src/test/java/de/regelsuche/search/memory/TranspositionTableIntegrationTest.java | class TranspositionTableIntegrationTest { -->
