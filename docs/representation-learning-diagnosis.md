# Warum der Darstellungslerner keinen zusätzlichen Gewinn zeigt

Der aktuelle Versuch untersucht **die Auswahl bereits implementierter Verfahren**.
Er prüft nicht, ob Regelsuche durch neu gelerntes mathematisches Wissen mehr
Aufgaben lösen kann. Diese Unterscheidung ist für die ursprüngliche Forschungsfrage
entscheidend; die Umsetzung von #952 deckt davon nur einen engen Teil ab.

## Drei nachgewiesene Ursachen

**Das mathematische Wissen ist schon vor dem Training vorhanden.** Alle Profile
verwenden handgeschriebene exakte Solver. Blockerkennung, Zerlegung, Lösung und
Zusammensetzen sind implementiert. `RepresentationStrategyLearner` darf lediglich
eine Mindestvariablenzahl aus `2, 4, 6, 8, 12, 17` wählen. Das Training kann weder
eine neue Darstellung noch einen neuen Umformungsschritt, ein Zwischenziel oder
einen neuen Lösungsalgorithmus erzeugen. Das Profil ohne Training entspricht
deshalb keinem Menschen ohne Mathematikkenntnisse.

**Die feste Vergleichsregel schöpft den vorhandenen Auswahlspielraum bereits aus.**
Eine erst nach der Auswertung berechnete Rückschau wählt für jeden Fall den
günstigsten tatsächlich verifizierten DIRECT-, MATRIX- oder BLOCKS-Lauf. Auf allen
40 gelösten Fällen ist FIXED_AUTO genauso günstig. Beide negativen Kontrollen
haben auch in dieser Rückschau keine verifizierte Lösung. Ein Lerner, der nur
zwischen diesen Verfahren auswählt, kann auf diesen Beobachtungen gegenüber
FIXED_AUTO somit **null zusätzliche Arbeit sparen und null weitere Fälle lösen**.
Das gilt für diese Verfahren, Budgets und Aufgaben; es beweist keine globale
Optimalität. Die feste Regel wurde anhand von Entwicklungsfällen gewählt und
ist eine legitime Expertenreferenz, aber keine Kontrolle ohne gelerntes Wissen.

**Ein vollständiges zweites Lösungsverfahren dominiert die Kosten.** Die Zerlegung
der tatsächlich ausgeführten Arbeit ergibt:

| Profil | Konstruktion | Unabhängiges Audit | Auswahl | Summe ohne Training |
|---|---:|---:|---:|---:|
| DIRECT | 28.316 | 55.192 | 0 | 83.508 |
| FIXED_AUTO | 23.268 | 55.192 | 0 | 78.460 |
| LEARNED | 23.268 | 55.192 | 84 | 78.544 |

Die Konstruktion spart gegenüber DIRECT rund **17,8 %**. Das zusätzliche Audit
löst das gesamte System erneut und macht rund **70,3 %** der Anwendungsarbeit
von LEARNED aus. Die Gesamtersparnis der festen Auswahl sinkt dadurch auf rund
6,0 %. DIRECT und MATRIX haben dieselbe Gesamtsumme, weil jeweils das andere
Verfahren zusätzlich auditiert; ihre Konstruktionskosten sind keineswegs gleich.
Die 12.685 einmaligen Lerneinheiten kommen weiterhin vollständig hinzu.

## Was daraus folgt

Der Versuch belegt einen begrenzten Nutzen der Repräsentationswahl und deren
Erlernbarkeit. Er liefert keine Evidenz gegen den Nutzen mathematischen Lernens.
Auch eine längere Trainingsphase oder ein aufwendigerer Auswahllerner würde den
fehlenden Spielraum gegenüber dieser festen Regel nicht beseitigen. Mehr
Anwendungsfälle können Trainingskosten nur dann amortisieren, wenn gegenüber
der jeweiligen Referenz eine wiederkehrende Ersparnis existiert.

Regelsuche hat daneben bereits andere Lernbausteine, insbesondere
[aus Spuren abgeleitete Polynompläne](trace-derived-polynomial-plans.md) und
[gelernte Regelfolgen](trace-strategy-transfer.md). Die Diagnose dieses
Darstellungsversuchs darf nicht auf alle Lernkomponenten übertragen werden.
Der Polynomplanlerner abstrahiert bislang vor allem Koeffizienten in festen
Syntaxformen und benötigt anschließend neue Solver- und Prüfläufe. Diese
Bausteine sind im hier gemessenen Schwellenwertlerner nicht integriert.

## Nötige Änderung des Lernexperiments

1. **Wissenszuwachs getrennt messen.** Ein fester gemeinsamer Vorrat atomarer
   Regeln mit derselben Suche und denselben Budgets läuft mit und ohne die
   tatsächlich gelernten Regeln oder Programme. Vollständige handgeschriebene
   Mathematikspezialisten bleiben als eigene Expertenreferenz erhalten.
   Die Basissuche wird nicht absichtlich verschlechtert; identische Regeln,
   Aufgaben, Ziele und Kosten werden vor der Auswertung festgelegt.
2. **Struktur lernen, die neue Anwendungen ermöglicht.** An die vorhandenen
   Spurenlerner anknüpfen: wiederverwendbare Termlücken, Auswahl des passenden
   Teilausdrucks, Vorbereitung und Fortsetzungen. Ein konkreter nächster Test
   wäre die Wiederverwendung einer gelernten quadratischen Strategie unter
   einer strukturellen Substitution wie `u = x^2`. Das ist zunächst Transfer
   durch Ausdruckskomposition, noch kein beliebiger Familienwechsel. Die
   Vorbereitung darf nicht unbemerkt die fertige Ziellösung vorgeben.
3. **Prüfbare Bausteine wiederverwenden.** Ein gelernter Baustein braucht eine
   begründete Anwendbarkeitsbedingung und einen Nachweis, dessen Instanz und
   Zusammensetzung geprüft werden können. Ein Prüfflag oder ein Hash genügt
   nicht. Für lineare Systeme muss eine günstigere Prüfung weiterhin die
   vollständige affine Lösungsmenge einschließlich Rang, freier Parameter
   und Widersprüchen absichern; bloßes Einsetzen einer Lösung reicht nicht.
4. **Den Gewinn nicht durch die Metrik herstellen.** Grundschritte expandieren
   und zählen, beobachtete Umwege vor der Bildung gelernter Folgen verkürzen,
   erfolglose Suche, Training, Auswahl und Prüfung in der Bilanz behalten.
   Neue Auswertungsaufgaben erst nach dem Einfrieren von Lerner und Inventar
   verwenden. Größere Suchräume allein sind noch kein Nutzennachweis.

Diese Änderungen am eigentlichen mathematischen Lernen sind **offene Arbeit**.
Die neue Diagnose implementiert noch keinen stärkeren Lerner. Sie verhindert,
dass wir einen bereits ausgeschöpften Auswahlvergleich als Test dieser
weitergehenden Forschungsfrage interpretieren.

## Reproduktion und Grenzen der Diagnose

`RepresentationTransferExperiment.main(outputDirectory)` erzeugt zusätzlich
`representation-transfer-diagnosis.json`. Dieselbe Diagnose ist im Studienexport
und in der Browserdemo enthalten; das Manifest bindet ihren Hash. Das ursprüngliche
Trainings-/Auswertungsprotokoll, die eingefrorene Policy und alle bisherigen
Kostensummen bleiben unverändert. Die Diagnose entsteht ausschließlich **nach**
der Auswertung und wird dem Lerner nicht zugeführt.

Der Vergleich zählt nur bestätigte Lösungen als mögliche Verbesserung. Eine
billigere erfolglose oder budgetierte Suche ersetzt keinen erfolgreichen Lauf.
Fehlende Profile, doppelte Beobachtungen oder unterschiedliche Aufgaben und
Konstruktionsbudgets werden abgewiesen. Die Diagnose ist eine Auswertung der
Java-Studie, keine zusätzliche unabhängige mathematische Prüfautorität.

Referenz: [vollständige Diagnose](generated/representation-transfer-diagnosis.json).
