# Promotion exakt bewiesener gelernter Pattern-Regeln

**Implementierungsstand: 23. August 2026**

Diese Seite beschreibt den ersten realen Promotionsadapter zwischen der
evolutionären Kandidatenschicht und dem kuratierten Regelinventar. Der Adapter
ist absichtlich eng. Er zeigt, wie eine gelernte Pattern-Regel nach einem
exakten Identitätsnachweis dieselbe Vorbereitungs- und Replay-Infrastruktur wie
eine handgeschriebene Regel verwenden kann.

Er autorisiert **keinen** allgemeinen oder öffentlichen Promotionsclaim. Der
maschinengebundene Capability-Status `PROMOTION` bleibt `NOT_EVALUATED`, bis ein
realer Produktionskandidat mit vollständig verifizierten Evidence-Artefakten
und dem vorgesehenen Release-/Qualification-Lifecycle promoviert wurde.

## Ausführungsgrenze

Rohe Genome werden weiterhin nicht zu vertrauenswürdigen Regeln erklärt:

```text
EvolutionGenome
  -> EvolutionGenomeValidator
  -> CompiledGenomeRule
  -> isEquivalencePreservingByConstruction() == false
```

Der neue `LearnedPatternRulePromoter` erzeugt eine neue Regelidentität nur über
den folgenden, fehlersicher sperrenden Pfad:

```text
akzeptiertes TRAIN-Genome und ausgewähltes RewriteGene
  -> vollständiger Genome-Preflight
  -> unterstütztes assumption-free Patternfragment
  -> exakter Polynom-Identitätsnachweis
  -> Bindung von Evidence-Root-Identitäten und Repository-Revision
  -> neuer PatternRewriteRule mit eigener Herkunft
  -> RewriteApplicabilitySchema
  -> PromotionReceipt
```

Die promovierte Regel ist ein neuer, content-addressed Ausführungsgegenstand.
Sie ersetzt weder das ursprüngliche Genome noch den rohen kompilierten Kandidaten.

## Unterstütztes mathematisches Fragment

`ExactPolynomialPatternIdentityVerifier` behandelt Platzhalter und literale
Variablen als unabhängige, kommutierende Unbestimmte. Unterstützt werden:

- exakte ganzzahlige Koeffizienten;
- Addition und Subtraktion;
- Multiplikation;
- begrenzte nichtnegative ganzzahlige Potenzen;
- assumption-free Identitäten im kommutativen Polynomring.

Beide Pattern werden innerhalb expliziter Grenzen in kanonische
Polynomnormalformen überführt. Nur identische exakte Normalformen ergeben
`PROVED`. Numerische Stichproben werden nicht als Ersatz verwendet.

Der Verifier unterscheidet:

```text
PROVED
NOT_EQUIVALENT
UNSUPPORTED
BUDGET_EXCEEDED
```

Der Proof-Hash bindet insbesondere:

- Verifier-ID;
- Quell- und Zielpattern;
- beide kanonischen Normalformen;
- vollständiges Verifikationsbudget;
- besuchte Knoten und erzeugte Terme;
- Terminalstatus und Detailcode.

Ein anderes Pattern oder Budget erhält deshalb eine andere Proof-Identität.

## Der erste charakterisierte Kandidat

Die Tests verwenden eine aus einem gültigen Genome stammende Differenz-von-
Quadraten-Regel:

```text
?A^2 - ?B^2
  ->
(?A - ?B) * (?A + ?B)
```

Nach erfolgreicher Promotion kann dieselbe Regel durch die allgemeine lokale
Vorbereitung auf einen strukturell verdeckten Fall angewendet werden:

```text
((x^2 * a) / a) - y^2
  -> x^2 - y^2                  unter a != 0
  -> (x - y) * (x + y)
```

Die primitive Lineage bleibt:

```text
ast_cancel_division_factor
learned.promoted.<hash>.difference-squares
```

Damit ist belegt, dass eine promoted gelernte Pattern-Regel grundsätzlich die
gleiche unscharfe Match- und AST-Vorbereitung wie eine deklarierte Regel
verwenden kann. Das Beispiel ist eine Architektur- und Korrektheits-
charakterisierung, kein Nachweis, dass die Regel autonom neu entdeckt oder im
Flagship-Experiment ausgewählt wurde.

## Evidence- und Identitätsbindung

`PromotionEvidence` verlangt Identitäten für:

- semantische Validierung;
- Counterexample Search;
- Holdout-Auswertung;
- Leakage-Audit;
- exakte Repository-Revision.

Der aktuelle v1-Promoter **bindet diese SHA-256-Identitäten, lädt oder
verifiziert die referenzierten Artefakte aber nicht selbst**. Die semantische
Prüfung dieser Evidence-Roots bleibt Aufgabe des übergeordneten Qualification-
und Release-Lifecycles. Eine frei erfundene, formal gültige Hashzeichenfolge ist
also noch keine ausreichende Promotionsevidence.

Das Receipt
`regelsuche.learned-pattern-rule-promotion-receipt/v1` bindet zusätzlich:

- Genome- und Alpha-Strukturhash;
- Gene-ID und Preflight-Hash;
- exakten Proof-Hash;
- Promotionsmaterial-Hash;
- neue Regel-ID und Regel-Content-Hash;
- Applicability-Schema-Hash.

Der strukturelle JSON-Vertrag steht unter
[`regelsuche-learned-pattern-rule-promotion-receipt-v1.schema.json`](schemas/regelsuche-learned-pattern-rule-promotion-receipt-v1.schema.json).
Schema-Validität allein ersetzt keine semantische Receipt-Prüfung.

## Bewusst ausgeschlossene Fälle

Promotion v1 lehnt ab:

- nicht äquivalente Pattern;
- Funktionen, Division und andere Ausdrücke außerhalb des exakten Fragments;
- nicht ganzzahlige Koeffizienten;
- über Budget liegende Normalformen;
- Regeln mit Annahmentemplates;
- Genome mit Preflight-Blockern;
- komplette `RewriteProgram`s mit `Choice`, `Sequence`, `Repeat` oder mehreren
  Eintrittspfaden.

Bedingte gelernte Regeln benötigen einen Nachweis, der Annahmen, Definitions-
bereiche und Discharge-Evidence explizit bindet. Gelernte `RewriteProgram`s
benötigen ein programmbasiertes Applicability-/Replay-Schema statt der
Täuschung, sie seien eine einzelne Pattern-Regel.

## Prüfung aus dem Checkout

Die fokussierten Tests laufen mit:

```bash
./gradlew :regelsuche-learning:test \
  --tests de.regelsuche.evolution.LearnedPatternRulePromoterTest
```

Der vollständige Repositoryvertrag bleibt:

```bash
./gradlew --no-configuration-cache ciCheck
```

## Siehe auch

- [Evolutionary Search](evolutionary-search.md)
- [Sicherer Regelvorbereitungskoordinator](safe-rule-preparation-coordinator.md)
- [Rule-directed Preparation Planning](rule-directed-preparation-planning.md)
- [Discovery- und Forschungsstand](discovery-status.md)
- [Unterstützte Grenzen](limits.md)
