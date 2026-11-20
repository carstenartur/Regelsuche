# Übertragbare Methoden aus OpenAI Math: Stand und offene Grenzen

**Nicht alles Ableitbare ist implementiert.** Regelsuche enthält ausgewählte,
begrenzte Methoden im gemeinsamen Java-Kern und typisierte SDK-Einstiege.
Dieser Audit vergleicht den Katalog und gezielt ausgewählte Quellen mit dem
[Ausgangsstand `c621a194`](https://github.com/carstenartur/Regelsuche/tree/c621a1949d29c391ceb6f1bdb5e79bf6e263ccf1).
Er ist keine Einzelprüfung aller Manuskripte und keine Behauptung, alle
möglichen Anwendungen der Sammlung ausgeschöpft zu haben.

## Referenz und Beweisumfang

Die feste Referenz ist
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

| Möglichkeit | Abgrenzung zum vorhandenen Code |
| --- | --- |
| Weitere Varianten aus Familie 116 | Der Katalog enthält auch Trefferlisten für Formeln mit Inversen und Trefferpunkte in positiver Charakteristik. Der aktuelle Formeltyp hat keine Inversen und ist auf rationale Koeffizienten beschränkt. „Rational“ bezeichnet hier die Koeffizienten, nicht rationale Ausdrücke mit inversen Variablen. Die [Lean-Beschreibung](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/lean/docs/116.md) trennt die beiden formalisierten Formelklassen. |
| Exakte Positivitäts- und Ungleichheitszertifikate aus Familie 266 | Abschnitt 5 prüft positive Matrizen durch rationale Schur-Komplemente und anschließend eine strikte Schranke aus konstantem Term und absoluter Koeffizientensumme. Ein eigener allgemeiner Java-Prüfer für diese Gram-/Schur-Zertifikate wurde in den untersuchten mathematischen Modulen nicht gefunden. Die lineare Kernsubstitution allein deckt diesen Schritt nicht ab. |
| Deterministische Faktorisierung aus Familie 142 | Vorhanden ist [klassische Berlekamp-Faktorisierung](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/polynomial/FiniteFieldFactorization.java) mit Enumeration von Körperresten und einer [begrenzten Policy](../../regelsuche-math-algorithms/src/main/java/de/regelsuche/math/algorithms/polynomial/FiniteFieldFactorizationPolicy.java). Die Familie behauptet dagegen vollständige Faktorisierung in einer Bitkomplexität polynomial in Grad und `log(p)`; ihr [Manuskript](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/Deterministic-Polynomial-Factorization-over-Prime-Fields-October-4-2026/Deterministic-Polynomial-Factorization-over-Prime-Fields.pdf) ist damit nicht schon implementiert. |
| Allgemeine Graphen und Zählverfahren | Bipartites Matching deckt weder allgemeine Graph-Matchings und vorgeschriebene Gradfaktoren aus Familie 120 noch approximatives Matchingzählen aus Familie 113 ab. Diese sind eigene Algorithmus- und Verifikationsaufgaben. |
| Allgemeine bilineare Entdeckung | Die vorhandenen Montgomery-Vorschläge prüfen zwei konkrete, allgemein parametrisierte Schemata. Eine Suche nach beliebigen Bilinearformen oder minimalem Tensor-Rang folgt nicht aus einem linearen Kernlöser; die [akzeptierte Transferspezifikation](../superpowers/specs/2026-11-20-mathematical-transfer-design.md) lässt diese nichtlineare Aufgabe ausdrücklich offen. |

Das sind konkrete Ansatzpunkte, keine Liste bereits zugesagter zusätzlicher
Implementierungen. Für einen weiteren Transfer braucht es einen begrenzten
Eingabevertrag, einen unabhängigen Prüfer und einen belegten Nutzen gegenüber
dem vorhandenen Verfahren. Asymptotische Ergebnisse über Matrixmultiplikation
(107), Ganzzahlmultiplikation (109) oder exakte Fouriertransformation (130)
begründen allein keinen praktischen Geschwindigkeitsgewinn im Java-Kern.

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
