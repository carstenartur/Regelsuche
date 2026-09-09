# Was der Darstellungslerner tatsächlich zeigt

Der aktuelle Versuch untersucht **die Auswahl bereits implementierter Verfahren**.
Er prüft nicht, ob Regelsuche durch neu gelerntes mathematisches Wissen mehr
Aufgaben lösen kann. Diese Unterscheidung ist für die ursprüngliche Forschungsfrage
entscheidend; die Umsetzung von #952 deckt davon nur einen engen Teil ab.

Die Rohdaten zeigen dabei keinen generellen Nachteil des Lernens: Gegen die
statische DIRECT-Strategie sinkt die Anwendungsarbeit von 83.508 auf 78.544
Einheiten, also um **4.964 Einheiten bzw. 5,94 %**. Das scheinbar schlechtere
Ergebnis entsteht nur, wenn der Lerner mit einer bereits handoptimierten
Expertenregel verglichen und zusätzlich der gesamte einmalige Trainingsaufwand
auf den ersten kleinen Auswertungsbatch gebucht wird.

## Drei Ursachen für den scheinbar fehlenden Gewinn

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
ist eine legitime **Expertenreferenz**, aber keine Kontrolle ohne Lernen oder
ohne mathematisches Wissen. Sie enthält mit Schwelle 8 bereits genau die
Entscheidungsgrenze, die der Lerner aus den Trainingsdaten wiederfindet.

**Ein vollständiges zweites Lösungsverfahren dominiert die Kosten.** Die Zerlegung
der tatsächlich ausgeführten Arbeit ergibt:

| Profil | Konstruktion | Unabhängiges Audit | Auswahl | Summe ohne Training |
|---|---:|---:|---:|---:|
| DIRECT | 28.316 | 55.192 | 0 | 83.508 |
| FIXED_AUTO | 23.268 | 55.192 | 0 | 78.460 |
| LEARNED | 23.268 | 55.192 | 84 | 78.544 |

Die Konstruktion spart gegenüber DIRECT rund **17,8 %**. Das zusätzliche Audit
löst das gesamte System erneut und macht rund **70,3 %** der Anwendungsarbeit
von LEARNED aus. Dadurch erscheint die Verbesserung im Aggregat kleiner. DIRECT
und MATRIX haben dieselbe Gesamtsumme, weil jeweils das andere Verfahren
zusätzlich auditiert; ihre Konstruktionskosten sind keineswegs gleich.

Die 84 Einheiten Differenz zwischen FIXED_AUTO und LEARNED sind exakt zwei
gezählte Auswahleinheiten pro 42 Auswertungsfälle. Beide Profile wählen hier
dieselben mathematischen Wege. Die Differenz ist daher kein Beleg dafür, dass
der gelernte Weg schlechter wäre.

## Einmalige Lernkosten korrekt amortisieren

Die 12.685 Lerneinheiten fallen beim Training einmal an. Werden sie vollständig
auf die ersten 42 Anwendungen gebucht, steigt deren Gesamtbilanz für LEARNED auf
91.229 Einheiten. Das beantwortet nur die Frage, ob sich das Training bereits
in diesem ersten kleinen Batch bezahlt gemacht hat.

Gegen DIRECT spart LEARNED im gemessenen Satz durchschnittlich etwa 118,19
Arbeitseinheiten pro Anwendung. Bei derselben mittleren Einsparung ist der
12.685-Einheiten-Aufwand nach ungefähr **108 vergleichbaren Anwendungen**
amortisiert. Diese Zahl ist eine transparente Break-even-Rechnung, keine
Extrapolation der zukünftigen Aufgabenverteilung. Entscheidend ist die Trennung
von **einmaliger Lernarbeit** und **wiederkehrender Anwendungsarbeit**.

## Was daraus folgt

Der Versuch belegt einen begrenzten Nutzen der Repräsentationswahl und deren
Erlernbarkeit. Er liefert **keine Evidenz gegen den Nutzen mathematischen
Lernens** und auch keine Evidenz dafür, dass Lernen kontraproduktiv wäre. Gegen
eine statische Strategie ist der gelernte Dispatcher bereits günstiger; gegen
eine Expertenregel, die dieselbe Entscheidung von Hand vorwegnimmt, kann er
naturgemäß keinen zusätzlichen algorithmischen Vorteil erzeugen.

Regelsuche hat daneben bereits andere Lernbausteine, insbesondere
[aus Spuren abgeleitete Polynompläne](trace-derived-polynomial-plans.md),
[gelernte Regelfolgen](trace-strategy-transfer.md) und die
[generationenübergreifende Regelgewinnung](generational-rule-mining.md). Die
Diagnose dieses Darstellungsversuchs darf nicht auf diese Lernkomponenten
übertragen werden.

Für die stärkere Forschungsfrage existiert sogar bereits ein expliziter
Regressionstest: `GenerationalRuleMiningCampaignTest` verlangt, dass das
Basisinventar ein Ziel unter dem festgelegten Budget **nicht** erreicht, während
das über Generationen angesammelte gelernte Regelinventar es erreicht und damit
eine vorher unerreichbare Form neu erreichbar macht. Das ist genau die Art von
Fähigkeitsgewinn, die bei echtem mathematischem Lernen erwartet wird.

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
Die Diagnose implementiert noch keinen stärkeren Lerner. Sie verhindert, dass
wir einen bereits ausgeschöpften Expertenvergleich als Test dieser
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
