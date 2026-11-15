# Strategien für Suche, Lernen und Rechenpläne

Dieser Index hält Hypothesen, Befunde und Entscheidungen aus der Entwicklung
fest. Die Versuchsdokumente enthalten Protokoll, Auswertung und Rohdaten.
Die Codeverweise führen zum aktuellen Checkout. Commit-Links halten
historische Berichte und Rohdaten unverändert auffindbar. Den Quellstand
eines Versuchs nennt sein Protokoll. Das [wissenschaftliche Manuskript](../../paper/README.md)
führt die belegten Aussagen und ihre Reproduktion.

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

- [`OrderedReductionSearch.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/OrderedReductionSearch.java#L23) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/OrderedReductionSearch.java | public final class OrderedReductionSearch { -->
- [`OrderedReductionKnowledge.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/OrderedReductionKnowledge.java#L23) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/OrderedReductionKnowledge.java | public final class OrderedReductionKnowledge { -->
- [`OrderedBlockReductionComparisonTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/OrderedBlockReductionComparisonTest.java#L23) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/OrderedBlockReductionComparisonTest.java | class OrderedBlockReductionComparisonTest { -->

## Geprüfter Paarzustand für geordnete Dreiersummen

- **Hypothese.** Ein aus einer Paarsumme übernommener Zustand erspart eine erneute Ableitung der benötigten Paarstatistiken in Dreiersummen.
- **Umsetzungsstand.** Exakte Übertragung auf ein Paar am Anfang oder Ende einer Dreierformel. Die Herleitung der gleichen Gruppierung direkt aus der Formel dient als Vergleich.
- **Befund.** Sechs passende Aufgaben verwenden den Paarzustand wieder. Inklusive Wissenserwerb und der beiden nicht passenden Kontrollen sinkt der Planungsaufwand von 11.200 auf 10.889 Arbeitseinheiten gegenüber direkt hergeleiteter Gruppierung, wenn der Paarplan bereits vorliegt. Beim Kaltstart braucht die Lernvariante 85 Einheiten mehr. Die Baumkosten sind gleich. [Daten und Reproduktion](pair-state-transfer.md)
- **Folgerung.** Die Wiederverwendung auf unabhängigen Aufgaben mit vorgeschalteter oder nachfolgender Paarabhängigkeit messen.

- [`OrderedPairStateProjection.java`](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/OrderedPairStateProjection.java#L25) <!-- strategy-code: regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/iteration/OrderedPairStateProjection.java | public record OrderedPairStateProjection(boolean pairFirst, List<Term> pairTerms, List<RowTerm> rowTerms) { -->
- [`OrderedPairStateTransferTest.java`](../../regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/OrderedPairStateTransferTest.java#L16) <!-- strategy-code: regelsuche-math-algorithms/src/test/java/de/regelsuche/math/algorithms/iteration/OrderedPairStateTransferTest.java | class OrderedPairStateTransferTest { -->
