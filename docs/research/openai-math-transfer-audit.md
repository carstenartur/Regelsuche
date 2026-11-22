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
