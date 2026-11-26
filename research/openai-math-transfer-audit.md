# Übertragbare Methoden aus OpenAI Math: Stand und offene Grenzen

**Nicht alles Ableitbare ist implementiert.** Regelsuche enthält ausgewählte,
begrenzte Methoden im gemeinsamen Java-Kern und typisierte SDK-Einstiege.
Dieser Audit vergleicht den Katalog und gezielt ausgewählte Quellen mit dem
[Ausgangsstand `c621a194`](https://github.com/carstenartur/Regelsuche/tree/c621a1949d29c391ceb6f1bdb5e79bf6e263ccf1).
Er ist keine Einzelprüfung aller Manuskripte und keine Behauptung, alle
möglichen Anwendungen der Sammlung ausgeschöpft zu haben.

## Referenz und Beweisumfang

Die Referenz des Erstaudits ist
[`openai/math@4eaee875`](https://github.com/openai/math/tree/4eaee87548250b2682affda7d05d947c05554bae).
Ihr [README](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/README.md)
nennt 721 Manuskripte in 373 Familien, unterschiedliche Verifikationsstände
und ungefähr 42 Prozent formalisierte Hauptergebnisse. Es bezeichnet die
Sammlung als Forschungsrohdaten. Der
[Katalog](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/CONTENTS.md)
ordnet die Ergebnisse ihren Familien zu.

Eine Familie, ein mathematischer Methodenbaustein und eine verwendbare
Java-Implementierung sind verschiedene Prüfgegenstände. Besonders deutlich
ist das bei Familie 266: Die
[Lean-Beschreibung](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/lean/docs/266.md)
grenzt die formalisierte obere Schranke von fünf Basen von der im Hauptmanuskript
behaupteten exakten Zahl drei ab. Der Regelsuche-Transfer betrifft die
linearen Zertifikatsmethoden des Begleitmanuskripts, nicht den vollständigen
Satz über gegenseitig unverzerrte Basen.

## Bereits vorhandene Übertragungen

| Bezug | Gemeinsamer Kern und SDK | Tatsächlicher Umfang |
| --- | --- | --- |
| Familie 266, exakte Substitutionen | `ModularKernel`, `ModularKernelVerifier`, `ModularKernelSdk`; optional `RecurrenceInvariantSynthesis.solveWithModularKernel` | Modulare Elimination, CRT und rationale Rekonstruktion schlagen Regeln vor. Ein unabhängiger exakter Prüfer unterscheidet Substitutionen für ausgewählte Originalzeilen von einer Basis des vollständigen Kerns. |
| Familie 119, rationale Boxen | `BernsteinPositivity`, `PolynomialPositivitySdk` | Ein- und zweidimensionale Bernstein-Netze, exakte Halbierung und unabhängige vollständige Blattprüfung. [Vertrag und Kontrollen](polynomial-positivity.md). |
| Familie 266, Gram-/Schur-Schritt | `GramPositivity`, `PolynomialPositivitySdk` | Exakte symmetrische Matrixprüfung einschließlich singulärer Nullpivots; ein Polynomurteil benötigt die vollständige Gram-Identität. Die Quellmatrizen des Manuskripts werden dadurch nicht automatisch zertifiziert. |
| Familie 187 als Architekturhinweis | `FiniteProofDag`, `FiniteProofDagSdk`, `FiniteProofDagJson` | Kleiner endlicher Regelsatz mit geprüften Coverage-/Hunter-/Hall-Blättern, Maximum und positiver Existenzfolgerung. Kein Import des Snaky-Spielbeweises. [Vertrag](hunter-finite-proofs.md). |
| Klassische Hunter-Schranke | `HunterCoverage`, `HunterCoverageSdk` | Exakte CRT-Paarschnitte und Spannbaumzertifikate für endliche Überdeckungen. Die mathematische Schranke stammt von Hunter (1976), nicht aus einem neuen OpenAI-Satz. |
| Familie 116, rationaler Trefferpunkt | `NoncommutativeFormula`, `NoncommutativeIdentity`, `NoncommutativeIdentitySdk` | Experimenteller Vergleich beschränkter, divisionsfreier geordneter Formelbäume über rationalen Koeffizienten in Charakteristik null. [Vertrag und Grenzen](noncommutative-identity.md). |
| Familie 021 als Anstoß zu endlichen Siebschranken | `CongruenceCoverage`, `CongruenceCoverageSdk` | Aktivierte Kongruenzen auf endlichen Progressionen, verallgemeinerter CRT, exakte Überdeckung und Bonferroni-Schranken. [Vertrag und Rechnung](congruence-coverage.md). |
| Klassische Matching- und Hall-Zertifikate | `BipartiteMatching`, `BipartiteMatchingSdk` | Endliche bipartite Graphen mit Kapazität eins, maximales Matching, maximale Hall-Defizienz und prüfbare Knotenüberdeckung. [Vertrag](bipartite-matching.md). Das ist keine Implementierung des allgemeinen Graph-Matching-Algorithmus aus Familie 120. |
| Montgomery-Anwendung des Zertifikatsprinzips | `MontgomerySchedule`, `MontgomeryReductionPlanner`, `MontgomeryScheduleSdk` | Wortgrenzen, Intervalle, Kongruenzen und kanonische Beobachtungen für endliche Pläne; zwei begrenzte Planungsschemata für Skalar- und Paarprodukte. [Vertrag](montgomery-schedules.md). Kein aus einer einzelnen OpenAI-Familie übernommener Kryptografiealgorithmus. |

Die unmittelbar verwendete Quelle aus Familie 266 ist das Lemma
„Exact substitutions“ in
[Abschnitt 5](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/Exact-Fourier-certificates-for-complex-Hadamard-matrices-of-order-six-September-24-2026/build/sections/05-verification.tex).
Familie 116 definiert ihren Formelvertrag in der
[Einleitung](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/One-Rational-Matrix-Hitting-Point-for-Noncommutative-Formulas-September-24-2026/build/introduction.tex).
Der alternierende Baum aus
[Familie 021, Abschnitt 3](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/A-quadratic-bound-for-Jacobsthals-function-September-25-2026/build/sections/tree.tex)
motiviert die Siebanwendung; die implementierten Bonferroni-Zertifikate
werden bereits durch die dokumentierte endliche Rechnung begründet.

Der Audit fand außerdem einen konkreten Fehler im modularen Suchpfad:
Eine ungünstige erste Primzahl konnte spätere brauchbare Pivotprofile
gleichen Rangs dauerhaft aussperren. Die
[Profilkorrektur](modular-kernel-profile-recovery.md) behebt diesen Fall im
gemeinsamen Kern, einschließlich unterschiedlicher Originalzeilen bei
gleichen Pivotspalten. Der exakte Zertifikatsprüfer bleibt unverändert.

## Wichtige zusätzliche Möglichkeiten

### Ergänzung vom 22. November 2026: begrenzte Suchfähigkeiten

Ausgehend von `98a60e30629f65bc12bb573e9beb46b04053e66c` ergänzt der aktuelle
Quellstand die zuvor nur vorgelegte Gram-Prüfung um [SOS- und Potentialsuche](sos-synthesis.md).
Exakte Koeffizientengleichungen bestimmen rationale Gram-Einträge und signierte
Gleichungsmultiplikatoren; ein endliches Gitter begrenzt freie Koordinaten.
Der unabhängige Prüfer rekonstruiert die Identität und prüft jede PSD-Matrix.
Potentiale binden zusätzlich die simultane Transition und den aktiven Bereich.

Die [allgemeine bilineare Katalogsuche](bilinear-program-search.md) erweitert
die vorhandenen Paarmuster: mehrere Ausgaben teilen frei gesuchte Produkte
aus vorgegebenen linearen Formen. Das Faltungsbeispiel braucht drei statt
vier variable Produkte, aber vier statt einer Addition und zwei explizite
Vorzeichenfaktoren. Dies ist ein Operationsvergleich, keine Laufzeitmessung.
Die [Erweiterungsspezifikation](../superpowers/specs/2026-11-22-mathematical-certificate-expansion.md)
grenzt diese Suche von optimalem Tensor-Rang und vollständigen asymptotischen Algorithmen ab.

Die Blockrealisierung aus Familie 116 verbindet nun jede Inverse im rationalen
Quellbaum mit einer Definitionsbedingung. Eine konkrete Matrixbelegung wird
durch eine direkte Auswertung des ursprünglichen Baums gegengeprüft.
[Realisierung rationaler Formeln](rational-formula-realization.md)

Die simultane Auswertung aus Familie 142 motiviert eine eigenständige
Quotientenrechnung. Erreichte Nulltests und Inverse können einen quadratfreien
Modul aufteilen. Ein Zertifikat deckt den ganzen Modul ab und weist auch
undefinierte Komponenten aus. [Dynamische Auswertung](dynamic-algebra-evaluation.md)

Die [lokale Constraint-Zählung](finite-constraint-counting.md) ergänzt nun
exakte gewichtete boolesche Faktoren, bedingte Anzahlen und eine unabhängig
geprüfte Eliminationsspur. Die Umsetzung nutzt gewöhnliche begrenzte
Variablenelimination; ihre Korrektheit hängt nicht von der allgemeinen
Komplexitätsbehauptung aus Familie 378 ab.

Für Familie 104 wurde der [endliche Energie-/Kreditnachweis](energy-game-certificates.md)
übertragen. Lokale Ungleichungen an ausgewählten Max- und allen Min-Kanten
begründen die Garantie auf zyklischen Pfaden. Die beschränkte Suche ist kein
Port des vollständigen quasipolynomiellen Mean-Payoff-Verfahrens.

| Möglichkeit | Abgrenzung zum vorhandenen Code |
| --- | --- |
| Weitere Varianten aus Familie 116 | Der Katalog enthält auch Trefferlisten für Formeln mit Inversen und Trefferpunkte in positiver Charakteristik. Der ältere Identitätsprüfer arbeitet mit divisionsfreien Formeln über rationalen Koeffizienten. Der neue Typ `RationalFormula` unterstützt Inverse und die Prüfung einer gegebenen rationalen Matrixbelegung. Die Konstruktion einer allgemeinen Trefferliste und der allgemeine Identitätstest für rationale Formeln bleiben offen. Die [Lean-Beschreibung](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/lean/docs/116.md) trennt die beiden formalisierten Formelklassen. |
| Deterministische Faktorisierung aus Familie 142 | Vorhanden ist [klassische Berlekamp-Faktorisierung](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/polynomial/FiniteFieldFactorization.java) mit Enumeration von Körperresten und einer [begrenzten Policy](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/polynomial/FiniteFieldFactorizationPolicy.java). Die Familie behauptet dagegen vollständige Faktorisierung in einer Bitkomplexität polynomial in Grad und `log(p)`; ihr [Manuskript](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/Deterministic-Polynomial-Factorization-over-Prime-Fields-October-4-2026/Deterministic-Polynomial-Factorization-over-Prime-Fields.pdf) ist damit nicht schon implementiert. |
| Allgemeine Graphen und Zählverfahren | Bipartites Matching deckt weder allgemeine Graph-Matchings und vorgeschriebene Gradfaktoren aus Familie 120 noch approximatives Matchingzählen aus Familie 113 ab. Diese sind eigene Algorithmus- und Verifikationsaufgaben. |
| Unbeschränkte bilineare Entdeckung | Der neue `BilinearProgramSearch` sucht gemeinsame Programme innerhalb expliziter rationaler Formenkataloge. Unbeschränkte Suche und allgemeiner minimaler Tensor-Rang bleiben offen; der begrenzte Befund steht [separat](bilinear-program-search.md). |

Diese verbleibenden Ansatzpunkte gehören nicht zur oben aufgeführten
Implementierung. Für einen weiteren Transfer braucht es einen begrenzten
Eingabevertrag, einen unabhängigen Prüfer und einen belegten Nutzen gegenüber
dem vorhandenen Verfahren. Asymptotische Ergebnisse über Matrixmultiplikation
(107), Ganzzahlmultiplikation (109) oder exakte Fouriertransformation (130)
begründen allein keinen praktischen Geschwindigkeitsgewinn im Java-Kern.

Der [Verfügbarkeitsstand](../mathematical-capabilities.md) ordnet die implementierten
Verfahren dem aktuellen Checkout, dem veröffentlichten SDK und den Demos zu.
Die Ergänzungen sind durch die [Umsetzungsspezifikation](../superpowers/specs/2026-11-20-exact-certificates-and-demos.md) begrenzt.

## Ergänzung vom 24. November 2026

Der [Folgestand `f570285`](https://github.com/openai/math/commit/f5702854040e97fa7675c21ba37a9608f5f92dd3)
ergänzt Familie 379. Die [Lean-Dokumentation](https://github.com/openai/math/blob/f5702854040e97fa7675c21ba37a9608f5f92dd3/lean/docs/379.md)
beschreibt die superpolynomielle Schranke für exakte arithmetische Schaltkreise. Rangargumente für
begrenzte bilineare Programme lassen sich direkt durch exakte lineare Algebra prüfen. Die
[automatische Formensuche](bilinear-program-search.md) benötigt eine Koeffiziententabelle und
ein Produktbudget. Ober- und Untergrenze werden gegen die Eingabe geprüft. Ein gelerntes
Produktpaar-Schema kann neue Ausgabegewichte erhalten. Der [Vergleich](../../research/benchmarks/bilinear-form-transfer/product-reuse.md)
enthält auch einen fest vorgegebenen Satz von Produkten als Kontrolle.

Für die lokale Tabellendarstellung aus Familie 378 bestimmt der [Zählplaner](finite-constraint-counting.md)
die Eliminationsfolge aus dem Faktorgraphen. Eine auf früheren Aufgaben trainierte Strategieauswahl
wird auf zurückgehaltenen Konfigurationsmodellen einschließlich Erwerbsaufwand verglichen.
Die [Messung](../../research/benchmarks/constraint-planning-transfer/README.md) enthält den
unabhängig bestätigten axTLS-Zählwert, feste heuristische Kontrollen und deren Grenzen.

## Ergänzung vom 25. November 2026: geprüfte Übernahmen und zurückgestellte Teile

Der Folgestand
[`3344da59991ed94f9ba711217b29c1d760badfa6`](https://github.com/openai/math/tree/3344da59991ed94f9ba711217b29c1d760badfa6)
enthält laut [README](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/README.md)
730 Manuskripte in 375 Familien. Er erweitert Familie 379 und ergänzt Familie 380.
Die folgende Prüfung umfasst alle im Folgeaudit als interessant ausgewählten
Ansätze. Sie ist keine vollständige Beweisprüfung sämtlicher Manuskripte.
Die Java-Tests ersetzen keinen Lean-Build; die vollständige vorgelagerte
Formalisierung wurde in diesem Transfer nicht neu ausgeführt. Platzhalter in
Comparator-Aufgaben wurden dabei nicht mit den eigentlichen Lösungstheoremen
verwechselt. Der Vergleich startet bei Regelsuche `44ce67ed`.

| Ansatz | Prüfung und Entscheidung | Übernommener Umfang oder konkrete Grenze |
| --- | --- | --- |
| 380: LP-Ausgänge und dünne Farkas-Zeugen | **Übernommen.** Exakte Originalgleichungen und Vorzeichen, danach Abhängigkeitsreduktion. | [LP-Zertifikate](linear-program-certificates.md): Unzulässigkeit, primal-duales Optimum, unbeschränkter Strahl; normalisierter Farkas-Träger innerhalb des Eingabeträgers mit höchstens `rank(A)+1` Einträgen. Kein allgemeiner LP-Löser und keine minimale Zeugenmenge. |
| 379: Prädiktor plus exakte Reparatur | **Übernommen.** Endliche rationale Matrizen mit expliziter gemeinsamer Eigenbasis oder eingereichter Faktorisierung. | [Operatorpläne](operator-residual-repair.md): unabhängige Identität `F=P+UV` und vollständige deklarierte Vektorkosten. Dichte Rang-eins-Kontrolle: 15 statt 28 Operationen; Involutionskontrolle: 438 statt 120, daher direkte Quelle gewählt. |
| 379: belegten Hilfsspeicher verwenden und wiederherstellen | **Übernommen.** Allgemeine endliche Scherfolgen auf drei disjunkten Wertregistern. | Derselbe [Vertrag](operator-residual-repair.md) prüft den ganzen Blockoperator für beliebige Anfangswerte. Die rechteckige Kontrolle benötigt 32 statt 14 Operationen und leiht zwei belegte Werte; kein JVM-Speicher- oder Laufzeitgewinn behauptet. |
| 116: rationale nichtkommutative Identitäten | **Übernommen, mit Voraussetzung.** Gegebenes gemeinsames Skalarzentrum, exakter erreichbarer Unterraum, separater Invarianzprüfer. | [Rationale Formelvergleiche](rational-formula-realization.md): Gleichheit auf dem gemeinsamen Definitionsbereich für alle Matrixordnungen oder ein direkt geprüfter Dreiecksmatrix-Gegenzeuge. Fehlendes oder undefiniertes Zentrum bleibt offen. |
| 142: vollständige Primkörperfaktorisierung | **Übernommen, begrenzt.** Vielfachheitsschichten, Ableitung/GGT und Frobenius-Wurzeln; vorhandene Berlekamp-Zerlegung bleibt der Teilerzeuger. | [Vielfachheiten](prime-field-multiplicities.md): Einheit, irreduzible Faktoren und Exponenten mit separater Produkt- und Irreduzibilitätsprüfung; alte quadratfreie und Hensel-Verträge bleiben bestehen. |
| 378/379: endliche Constraints mit echter Greedy-Chronologie | **Im Consumer übernommen.** Die vorhandene CNF-Zählung erhält eine primzahlspezifische Übersetzung. | [Primachsenraum](https://github.com/carstenartur/primachsenraum): jede Entscheidung verwendet nur frühere Belegungen; der bekannte Gegenmodellversuch scheitert bei 86. Das liefert keine uniforme Frist. |
| 379: Sunflowers in Sperrnachbarschaften | **Geprüft, kein stärkerer Hall-Satz.** Für disjunkte Petalen gilt exakt `t−|C|−Σ|P_i|`. | Bei durchweg nichtleeren Petalen ist die Defizienz höchstens `−|C|`. Die vier vorhandenen kleinen Graphformen behalten Defizite `1,1,0,1`. Gleiche Nachbarschaften dürfen nur unter Erhaltung der Anzahl ihrer linken Knoten zusammengefasst werden. |
| 380: Optimierung durch höchstens zwei Zulässigkeitsaufrufe | **Zurückgestellt.** Die Reduktion setzt einen vollständigen Zulässigkeitslöser samt Zeugenrückgewinnung voraus. | Der Kern besitzt jetzt die unabhängig prüfbaren Ausgabeformen. Er besitzt noch nicht den spezifizierten stark polynomialen Orakelalgorithmus; ein weiterer Wrapper würde diese Lücke nicht schließen. |
| 380: partiell korrekte Vorzeichen und Online-Kompression | **Voraussetzungen geprüft, nicht als generische Heuristik übernommen.** | Vorzeichentreue wird an allen gebundenen maximierenden Positionen verlangt. Jede Antwortfolge muss zu einer einzigen globalen, auch auf unbefragten Minoren definierten Belegung passen. Gewöhnliches Caching oder beliebig fehlerhafte Heuristiken erfüllen diesen Vertrag nicht. |
| 380: Hirsch-Schranken, quadratische Programme und Spiele | **Zurückgestellt.** Die Diameteraussage setzt eine konkrete Polytopeinbettung voraus; die algorithmischen Folgerungen benötigen den vollständigen LP-Unterbau. | Kein übertragbarer Nachweis über beliebige Rewrite-Graphen oder eine Verbesserung der vorhandenen endlichen Energiezertifikate allein aus dem Titel. |
| 116: allgemeine Trefferlisten und positive Charakteristik | **Größen und Geltungsbereich geprüft, zurückgestellt.** | Bereits `n=s=1` erzeugt in der betrachteten rationalen Konstruktion Matrixordnung 32.768 und ein dreidimensionales Gitter mit Seitenlänge 452.984.833. Die andere Charakteristik verlangt einen eigenen divisionsfreien Vertrag. Kein praktischer Consumer rechtfertigt diesen vollständigen Import. |
| 142: großer Primmodulus in polynomialer Bitkomplexität | **Abhängigkeiten und Konstanten geprüft, zurückgestellt.** | Für unsere `int`-Primzahlen liegt bereits der riesige kleine-Primzahl-Zweig der Quelle vor. Der große Zweig benötigt Erweiterungskörper, Funktionenkörper-/Normkonstruktionen und zusätzliche analytische Voraussetzungen; die vorhandene Enumerationsgrenze bleibt explizit. |
| 379: universelle Tensorpreise, FFT und Multiplikationsexponenten | **Endliche Bausteine geprüft, vollständiger Port zurückgestellt.** | Spektralbasis, Ausnahmekorrektur, alle Abfragen und dichte Mischungen kosten Arbeit. Ein aus dem Manuskript übernommener Stufenname ist keine einzelne billige Java-Operation. Für die bestehenden Kryptografie-Verbraucher liegt daraus kein neuer Gesamtlaufzeitgewinn vor. |

Die neuen Verfahren liegen in `regelsuche-math-algorithms`; die SDK-Klassen
delegieren. Jede erfolgreiche Suche oder Konstruktion wird gegen die gebundene
Originalquelle nachgerechnet. Grenzen und Abbruch bleiben explizite unvollständige
Ergebnisse. Die jeweiligen Fachseiten enthalten Testeingaben, Gegenproben,
Kosten und Reproduktionsbefehle. Die Arbeitseinheiten verschiedener APIs sind
keine untereinander normierten Laufzeitmessungen. Aufbau, neue Suche und Prüfung
müssen vor einem behaupteten Verbrauchernutzen zusätzlich amortisiert werden.

### Anschließende Implementierung der empfohlenen Suchverfahren

Auf Basis von Regelsuche `d4784fcde5f92e9dc222e760af4febccc30e9f4e`
ergänzt der nächste Schritt die oben beschriebenen Prüfer um tatsächliche
begrenzte Erzeuger. Die vorangehende Tabelle dokumentiert den ersten Transfer;
insbesondere ihre Aussagen „kein allgemeiner LP-Löser“ und zum damaligen
`int`-Primkörper beschreiben diesen früheren Stand.

| Empfehlung | Jetzt implementiert | Weiterhin geltende Grenze |
| --- | --- | --- |
| LP-Belege selbst finden und verwenden | [Exakter Zwei-Phasen-Simplex und affine Folgerungen](linear-program-search.md), einschließlich konkreter Gegenpunkte und separatem Prüfer | Ressourcenbegrenzter Standard-Simplex, kein stark polynomialer Algorithmus der Familie 380 |
| Größere Primmoduli praktisch erschließen | [Typisierter BigInteger-Körper, SFD/DDF/EDF und unabhängiger Komplettprüfer](large-prime-factorization.md) | Große Primannahme bleibt im Status sichtbar; randomisierte Suche, keine deterministische Bitkomplexität der Familie 142 |
| Reparaturpläne selbst entdecken und wiederverwenden | [Quellengesteuerte Prädiktoren, exakte Rangzerlegung und vorbereitete Ausführung](operator-residual-repair.md) | Endliche rationale Matrizen; vollständige Erwerbskosten, starke Direkt- und Spezialkontrollen in einer lokalen Messung |
| Gemeinsames Skalarzentrum automatisch finden | [Konfigurierbare endliche Gitterpolicy](rational-formula-realization.md#ein-gemeinsames-skalarzentrum-automatisch-suchen) unter einem gemeinsamen Budget | Originaldomänen bleiben erhalten; ausschließlich an Matrizen definierte Formeln können offen bleiben |
| Frühere Greedy-Bedingungen zu neuen Folgerungen verbinden | Separater [Primachsen-Verbraucher](https://github.com/carstenartur/primachsenraum) mit authentifiziertem Ausgangspräfix und unabhängigem Belegprüfer | Neue Herleitung von $T_{751}=47.796$ aus kleineren Quellen, kein neuer empirischer Zahlenwert und keine uniforme Frist |

Die generischen Verfahren liegen in den vorhandenen Kern- und
Mathematikmodulen; das SDK delegiert. Primzahlspezifische Voraussetzungen und
Chronologie bleiben im Consumer. Es wurde kein fremder Bibliothekscode in
die neuen Implementierungen kopiert und keine zusätzliche Bibliothek
eingebunden. Der vollständige Funktionenkörper-Unterbau der Familie 142,
universelle rationale Trefferlisten und ein allgemeiner Fristsatz bleiben
die ausdrücklich zurückgestellten Forschungsaufgaben.

Die vollständigen betroffenen Java-Modulsuiten laufen nach der Erweiterung
mit 3.507 erfassten Tests: 1.232 Core, 181 E-Graph, 811 Search, 45 Validation,
878 Mathematik und 360 SDK. Es gibt keine Fehler oder Fehlschläge; ein
bereits optionaler Metrikexport bleibt ohne Exportkonfiguration übersprungen.
Alle 35 geänderten Java-Quellen einschließlich Tests und Messprogramm bestehen
zusätzlich `javac -Xlint:all -Werror`. Die 72 Java-Tests des Primachsen-Consumers
bestehen auch gegen diesen neuen SDK-Quellstand. Ein vollständiger lokaler
SDK-Paketbau ist erfolgreich; der vollständige Gesamtprodukt-Build wird
zusätzlich in CI geprüft.

Frische unabhängige Reviews kontrollierten die mathematischen Verträge und
eigene Vergleichsorakel. Zwei dabei gefundene generische Ressourcenlücken sind
geschlossen: die kumulative Reservierung einer ausgewerteten und anschließend
rangbedingt verworfenen Prädiktormatrix sowie Abbruchpunkte innerhalb großer
dichter Polynomkonvertierungen. Deterministische Regressionen prüfen beide
Korrekturen. Die neue Default-Methode des Arbeitssinks bleibt auch für einen
gegen die alte Schnittstelle kompilierten Anbieter kompatibel.

### Ausgeführte Integration des ersten Transfers

Die lokalen vollständigen Tests der betroffenen Module umfassen 826 Tests im
Mathematikmodul, 355 im SDK und 72 im Primachsen-Consumer: keine Fehler oder
Fehlschläge. Ein bereits bedingter Metrikexport-Test bleibt ohne seine
Ausgabekonfiguration übersprungen. Geänderte Java-Quellen und Tests werden
zusätzlich mit `-Xlint:all -Werror` geprüft. Drei getrennte Fachreviews
untersuchten LP/Chronologie, Operatorpläne sowie rationale Formeln/Primkörper.
Eine dabei gefundene fehlende Reservierung weiterlebender Anfragezellen im
Chronologie-Wrapper wurde mit einem zunächst fehlschlagenden Grenztest behoben.

Die Bibliotheksmodule lassen sich mit Java 25 aus diesem Checkout prüfen:

```sh
mvn -B -ntp -pl regelsuche-math-algorithms,regelsuche-optimization-sdk -am test
```

Der Primachsen-Consumer hat einen separaten gepinnten Quellbuild und eigene
Tests. Der gemeinsame lokale Lauf ersetzt keine Aussage über den gesamten
Regelsuche-Reaktor, einen Maven-Release oder den späteren CI-/Merge-Status.

Primärstellen des Folgeaudits:

- [380: ausgewiesener Formalisierungsumfang](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/lean/docs/380.md),
  [LP-Reduktion](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/preprints/Strongly-polynomial-linear-programming-November-24-2026/build/lp.tex),
  [Vorzeichen](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/preprints/Strongly-polynomial-linear-programming-November-24-2026/build/prices.tex),
  [Kompression](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/preprints/Strongly-polynomial-linear-programming-November-24-2026/build/compression.tex).
- [379: ausgewiesener Formalisierungsumfang](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/lean/docs/379.md),
  [Reparatur](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/preprints/The-logarithmic-exponent-of-finite-Fourier-transforms-November-24-2026/build/repair.tex),
  [belegter Speicher](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/preprints/The-logarithmic-exponent-of-finite-Fourier-transforms-November-24-2026/build/layers.tex),
  [Sunflower-Manuskript](https://github.com/openai/math/blob/3344da59991ed94f9ba711217b29c1d760badfa6/preprints/An-exponential-bound-for-sunflowers-November-24-2026/build/main.tex).
- Die Quellen, Detailrechnungen und Grenzen zu 116 und 142 stehen in
  [rational-formula-realization.md](rational-formula-realization.md) und
  [prime-field-multiplicities.md](prime-field-multiplicities.md).

## Verbraucher und Abschlusskriterien

Die [akzeptierte Umsetzung](../superpowers/plans/2026-11-20-mathematical-transfer.md)
umfasst auch Bouncy Castle und Sandbox. Die oben genannten SDK-Klassen sind
Quellcode-Einstiege; daraus folgt weder eine veröffentlichte SDK-Version
noch eine abgeschlossene Verbraucherintegration. Dieser Audit behauptet
keinen separat ungeprüften Release-, CI-, Merge- oder Upstream-Status.

Für eine Java-Umschreibung muss der Consumer zusätzlich die tatsächlichen
Eingabebereiche, Aufrufer, Aliase, Effekte und die eindeutige abschließende
Beobachtung binden. Für Bouncy Castle gehören vollständige Tests aller
Parameterfamilien und Messungen ganzer Kryptoperationen dazu. Diese
Anwendungsnachweise dürfen nicht durch erfolgreiche algebraische Kerntests
ersetzt werden. Die Mathematik bleibt im gemeinsamen Kern; das SDK delegiert.

Auch die universelle Greedy-Frist des Primachsenraums bleibt offen.
Endliche Hall-Defizite und Siebschranken liefern keine Aussage über sämtliche
Primzahlen oder alle unendlichen Fortsetzungen einer Greedy-Folge.
