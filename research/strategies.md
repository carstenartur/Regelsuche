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
