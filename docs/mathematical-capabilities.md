# Mathematische Funktionen: Quelle, Release und Oberfläche

Stand: 22. November 2026. Diese Seite beschreibt die **Verfügbarkeit** der
genannten Funktionen. Mathematische Verträge und ausgeführte Vergleiche stehen
in den verlinkten Fachseiten; der [Strategien-Index](research/strategies.md)
führt Hypothesen, Befunde und Folgerungen.

Der veröffentlichte Maven-Einstieg ist
`io.github.carstenartur:regelsuche-optimization-sdk:0.5.3`.
Dieser Checkout entwickelt `0.5.4-SNAPSHOT`. Ein Quellcode-Einstieg, eine
vorhandene Demo und ein veröffentlichter Binärstand sind getrennte Aussagen.
Ein eigener Build dieses Checkouts ist für die neuen APIs erforderlich.

## Neuere Verfahren in diesem Checkout

| Fähigkeit | Java-Kern | Typisierter SDK-Zugang | In Release 0.5.3 | Workbench in diesem Checkout |
| --- | --- | --- | --- | --- |
| Aktivierte Kongruenzen, CRT, exakte Überdeckung und Bonferroni | `CongruenceCoverage` | `CongruenceCoverageSdk` | nein | Java-/SDK-Beispiele; keine eigene Eingabemaske |
| Hunter-Survivor-Untergrenze | `HunterCoverage` | `HunterCoverageSdk` | nein | ausführbares Java-Beispiel |
| Maximales bipartites Matching und Hall-Zeuge | `BipartiteMatching` | `BipartiteMatchingSdk` | nein | Live-Zertifikatsdemo |
| Endliche Polynomkongruenzen | `PolynomialCongruence` | `PolynomialCongruenceSdk` | nein | Java-/SDK-Beispiele |
| Modulare Kernsubstitution mit exakter Prüfung | `ModularKernel`, `ModularKernelVerifier` | `ModularKernelSdk` | nein | Java-/SDK-Beispiele |
| Beschränkte nichtkommutative Identitätsprüfung | `NoncommutativeIdentity` | `NoncommutativeIdentitySdk` | nein | experimenteller Java-Einstieg |
| Endliche Montgomery-Pläne | `MontgomerySchedule`, `MontgomeryReductionPlanner` | `MontgomeryScheduleSdk` | nein | Live-Zertifikatsdemo |
| Rationale Bernstein-Schranken und Positivitätsbeweise | `BernsteinPositivity` | `PolynomialPositivitySdk` | nein | Java-/SDK-Beispiele |
| Exakte Gram-Matrix und Polynomidentität | `GramPositivity` | `PolynomialPositivitySdk` | nein | Java-/SDK-Beispiele |
| Begrenzte SOS- und polynomielle Potentialsuche | `SosSynthesis`, `SosVerifier`, `PolynomialPotential` | `SosSynthesisSdk` | nein | ausführbares Java-Beispiel; keine eigene Eingabemaske |
| Gemeinsame bilineare Programme aus Formenkatalogen | `BilinearProgramSearch`, `BilinearProgramVerifier` | `BilinearProgramSdk` | nein | ausführbares Java-Beispiel; keine eigene Eingabemaske |
| Rationale nichtkommutative Formeln auf gegebenen Matrizen | `RationalFormulaRealization`, `RationalFormulaVerifier` | `RationalFormulaSdk` | nein | Java-Beispiel ohne eigene Eingabemaske |
| Dynamische Auswertung über quadratfreien Primkörperquotienten | `DynamicAlgebraEvaluation`, `DynamicAlgebraVerifier` | `DynamicAlgebraSdk` | nein | Java-Beispiel ohne eigene Eingabemaske |
| Quellengebundener endlicher Beweis-DAG | `FiniteProofDag` | `FiniteProofDagSdk`, `FiniteProofDagJson` | nein | versionierter JSON-Transport; Java-/SDK-Beispiele |

Die bestehenden [Paarsummen-Demos](demo-gallery.md) ergänzen ihre kleine
direkte Gegenrechnung nun um eine tatsächlich ausgeführte Reduktion über
100.000 Werte. Die angezeigten 4.999.950.000 Paare bleiben die berechnete
Arbeitsmenge des quadratischen Referenzverfahrens; dieser große Referenzlauf
wird nicht ausgeführt und es wird daraus keine gemessene Zeitersparnis abgeleitet.

## Verträge und Reproduktion

- [Kongruenzüberdeckung](research/congruence-coverage.md),
  [Hunter und endliche Beweis-DAGs](research/hunter-finite-proofs.md),
  [Hall-Zertifikate](research/bipartite-matching.md).
- [Polynompositivität](research/polynomial-positivity.md),
  [SOS- und Potentialsuche](research/sos-synthesis.md),
  [bilineare Programmsuche](research/bilinear-program-search.md),
  [modulare Kernrekonstruktion](research/modular-kernel-profile-recovery.md),
  [Polynomkongruenzen](research/polynomial-congruences.md).
- [Rationale Matrixformeln und Definitionsbedingungen](research/rational-formula-realization.md),
  [dynamische Quotientenalgebren](research/dynamic-algebra-evaluation.md).
- [Montgomery-Pläne](research/montgomery-schedules.md),
  [nichtkommutative Identitäten](research/noncommutative-identity.md),
  [gemeinsame bilineare Vorschläge](research/bilinear-core-proposals.md).
- [Demo-Galerie](demo-gallery.md), [SDK-Referenz](../regelsuche-optimization-sdk/README.md)
  und [Release-Einstieg](java-sdk-quickstart.md).

## Verbraucherstand

Sandbox bindet derzeit den veröffentlichten SDK-Stand 0.5.3 ein; die
[Qualifikationsdatei](https://github.com/carstenartur/sandbox/blob/18694d92537c1008fcb35f7cddb63b565da3d601/sandbox_math_cleanup/lib/qualification.json)
bindet den ausgelieferten JAR-Stand. Die neuen Funktionen in der Tabelle sind
damit noch keine gelieferten Sandbox-Refactorings. Eine neue SDK-Version und
deren Consumer-Qualifikation sind eigene Integrationsschritte.

Die Bouncy-Castle-Optimierungen besitzen eigene Quellpatches und Messungen.
Die Montgomery-Demo prüft den endlichen mathematischen Plan. Sie ersetzt
weder den Review eines Kryptografiepatches noch Tests vollständiger
Kryptoperationen oder eine Prüfung des zeitlichen Seitenkanalverhaltens.

Primachsenraum verwendet für die neuen endlichen Vergleiche einen fest
gebundenen Quellbuild. Die Anwendung verantwortet die Herkunft der kleineren
Primzahlregeln, die Greedy-Chronologie und die Vollständigkeit möglicher
Blockierer. Positive endliche Zertifikate beweisen keine universelle
Primzahlfrist und keine unendliche Faktorisierungsvermutung.

## Dokumentationspflege

Eine neue Fähigkeit benötigt im selben Änderungsstand den Eingabevertrag,
ihren unabhängigen Prüfweg, Ressourcen- und Abbruchverhalten, mindestens einen
reproduzierbaren Vergleich mit Gegenproben und einen Strategieeintrag.
Ein Release ändert die entsprechende Spalte erst nach tatsächlicher
Veröffentlichung. Ein Consumer-Eintrag ändert sich nach dessen eigener
Qualifikation. Historische Korpuszahlen bleiben an ihren jeweiligen Quell-
und SDK-Stand gebunden.
