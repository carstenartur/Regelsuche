# Gelernte Darstellungswahl: öffentlicher Transfervergleich

Der Vergleich verbindet die [automatische Darstellungswahl](automatic-linear-representations.md)
mit einem begrenzten Strategielerner. Er lernt die Bedingung, ab welcher
Variablenzahl unabhängige Blöcke getrennt gelöst werden. Die mathematischen
Verfahren und die Abfolge Zerlegen → Lösen → Zusammensetzen sind vorgegeben.
Allgemeine Beweistaktiken, neue mathematische Verfahren oder neue Regeln werden
in diesem Experiment **nicht** gelernt.

Sechs Trainingssysteme erzeugen 18 tatsächlich ausgeführte und unabhängig
auditierte Beobachtungen (jeweils direkt, vollständige Matrix und Blöcke).
Die feste Grammatik enthält die Mindestgrößen 2, 4, 6, 8, 12 und 17. Der Fit
bevorzugt verifizierte Abschlüsse und anschließend geringere Arbeitskosten.
Die private, unveränderliche Policy wird vor der Auswertung eingefroren.
Aufgabenfamilien, Soll-Lösungen und Auswertungsergebnisse sind keine Eingaben
des Lerners. Die Zufallskontrolle verwendet eine veröffentlichte feste Formel
aus Quellgrößen mit Seed 730.

Der Auswertungssatz enthält 42 Aufgaben: je acht Fälle für Koeffizientenvergleich,
Rekurrenzblöcke, redundante Bedingungen, freie Parameter und verbundene Systeme
sowie zwei negative Kontrollen (nichtlinear und außerhalb der rationalen Domäne).
Rekurrenzblöcke haben drei statt der in den zerlegbaren Trainingsfällen
vorkommenden zwei Variablen je Block. Das ist ein begrenzter Transfer
innerhalb rationaler linearer Algebra, kein domänenübergreifender Beweisfund.
Sortierte exakte Alpha-Polynomidentitäten der einzelnen Gleichungen schließen
umbenannte und umsortierte Trainingskopien aus. Dieses konservative Kriterium
entscheidet keine allgemeine semantische Äquivalenz ganzer Systeme.

Dies ist ein **öffentlicher Entwicklungssatz**, kein unangetasteter abschließender
Holdout und keine externe Preregistrierung. Die feste AUTO-Schwelle wurde anhand
von Entwicklungsfällen gewählt. Die folgenden Zahlen beschreiben den aktuellen
ausgeführten Stand; sie dürfen nicht als blinder Leistungsnachweis ausgegeben werden.

| Profil | Anwendung einschließlich Audit | Einmaliges Training eingerechnet | Verifizierte Abschlüsse |
|---|---:|---:|---:|
| DIRECT | 83.508 | 83.508 | 40 |
| MATRIX | 83.508 | 83.508 | 40 |
| BLOCKS | 79.748 | 79.748 | 40 |
| FIXED_AUTO | 78.460 | 78.460 | 40 |
| RANDOM | 81.592 | 81.592 | 40 |
| LEARNED | 78.544 | 91.229 | 40 |

## Welche Baseline beantwortet welche Frage?

`DIRECT` ist die statische Strategie ohne gelernte Darstellungswahl. Gegen sie
spart `LEARNED` auf den 42 Auswertungsfällen **4.964 Arbeitseinheiten bzw.
5,94 %** der Anwendungsarbeit. Betrachtet man nur die Konstruktion, beträgt die
Einsparung **17,8 %** (23.268 statt 28.316 Einheiten). Lernen bringt in diesem
Versuch also sehr wohl einen messbaren Vorteil bei der späteren Anwendung.

`FIXED_AUTO` ist dagegen **keine ungelernte Baseline**. Es ist eine
handgeschriebene, auf Entwicklungsfällen abgestimmte Expertenregel. Sie schaltet
bei derselben Schwelle 8 auf die Blockdarstellung um, die der Lerner aus seinen
Trainingsbeobachtungen ebenfalls als beste Schwelle findet. Deshalb wählen
`FIXED_AUTO` und `LEARNED` auf diesem Datensatz dieselben Wege. Die Differenz von
84 Einheiten (78.544 gegenüber 78.460) sind exakt die mitgezählten zwei
Auswahleinheiten pro Auswertungsfall. Daraus lässt sich kein Nachteil des
Lernens ableiten; der Vergleich zeigt vielmehr, dass der Lerner die bereits
von Hand eingebaute Heuristik reproduziert.

Die 12.685 Lerneinheiten sind **einmalige Trainingskosten**. Sie vollständig
auf nur den ersten Satz von 42 Anwendungen zu schlagen beantwortet die Frage
„Hat sich das Training bereits im ersten kleinen Batch amortisiert?“, nicht
„Ist Lernen nützlich?“. Bei der im Auswertungssatz gemessenen mittleren
Einsparung gegenüber `DIRECT` liegt der rechnerische Break-even nach ungefähr
**108 vergleichbaren Anwendungen**. Danach überwiegt die eingesparte
Anwendungsarbeit die einmaligen Trainingskosten. Diese Extrapolation ist keine
Behauptung, dass zukünftige Aufgaben dieselbe Verteilung haben; sie verhindert
lediglich, einmalige und wiederkehrende Kosten begrifflich zu vermischen.

Die 40 Abschlüsse umfassen vollständige exakte Lösungsmengen; die beiden
negativen Kontrollen bleiben in jedem Profil erhalten. Die Null bei
`regressions` zählt verlorene verifizierte Abschlüsse gegenüber DIRECT, nicht
einzelne Fälle mit höheren Arbeitskosten.

Die [Ursachenanalyse](representation-learning-diagnosis.md) zeigt außerdem, dass
die handgeschriebene Expertenwahl auf allen 40 gelösten Fällen bereits dem
günstigsten tatsächlich verifizierten Verfahren entspricht. Ein reiner
Auswahllerner hat dort keinen zusätzlichen Spielraum. Das unveränderte zweite
Lösungsverfahren zur Prüfung dominiert zudem die Gesamtarbeit. Dieser Versuch
ist deshalb ausdrücklich **kein Vergleich mit und ohne mathematisches Wissen**.

## Wo echtes mathematisches Lernen gemessen wird

Für die Frage, ob erworbenes mathematisches Wissen die Fähigkeiten von
Regelsuche erweitert, ist die [generationenübergreifende Regelgewinnung](generational-rule-mining.md)
der passende Versuchsaufbau. Dort startet die Suche mit einem Basisinventar,
aktiviert nur überprüfte neu gefundene Regeln in der jeweils nächsten
Generation und prüft anschließend unter demselben Suchbudget die Erreichbarkeit
einer tieferen Form. Der Regressionstest `GenerationalRuleMiningCampaignTest`
fordert ausdrücklich, dass die Baseline das Ziel **nicht** erreicht, das
angesammelte gelernte Regelwissen es dagegen erreicht und der Fall dadurch neu
unter dem Budget erreichbar wird. Das ist eine echte Fähigkeitsverbesserung
durch gelerntes mathematisches Wissen und eine andere Forschungsfrage als die
hier untersuchte Darstellungswahl.

Die Bilanz dieses Darstellungsversuchs umfasst alle Trainingstrials,
Fitvergleiche, Anwendung, Auswahl, Darstellungsvorbereitung, Komposition und
zusätzliche algorithmische Audits. Das Konstruktionsbudget beträgt 20.000, das
getrennte Auditbudget 200.000. Parsing, Hashing, Identitätsbildung für den Split
und die Bitkomplexität rationaler Arithmetik liegen außerhalb der mechanischen
Einheiten. Diese Zahlen sind weder CPU-Messungen noch eine
Gesamtkomplexitätsanalyse.

`RepresentationTransferExperiment.main(outputDirectory)` schreibt zuerst das
feste Protokoll und anschließend den vollständigen Bericht, die Zusammenfassung
und ein SHA-256-Manifest. Das Manifest bindet auch alle Bytes des vollständigen
Berichts, einschließlich fehlgeschlagener Versuche. Referenzdateien:
[Protokoll](generated/representation-transfer-protocol.json),
[Zusammenfassung](generated/representation-transfer-summary.json),
[Manifest](generated/representation-transfer-manifest.json).
Die zusätzlich erzeugte [Diagnose](generated/representation-transfer-diagnosis.json)
zerlegt die Kosten und quantifiziert nachträglich den verbleibenden Auswahlspielraum.
Die [Browserdemo](representation-transfer-demo.md) führt die aktuelle Studie aus
und exportiert den vollständigen Bericht.
