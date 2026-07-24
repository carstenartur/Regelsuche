# Replay and Reports

Die Discovery-UX besteht aus zwei Schichten:

1. **interaktives Browser-Replay** in der Web-Workbench
2. **automatische Report-Artefakte** für CI, Dokumentation und Reproduktion

## Replay

Das Replay zeigt pro Pfad:

- Vorher/Nachher-Schritte mit KaTeX/Layout
- Regelbegründung und Diff-Hervorhebung
- Domänenhinweise (z. B. Vergleichszeichen-Flip, Matrixkarte)
- Makrozüge inklusive atomarer Expansion und unterstützender Pfad-IDs
- kompakte Dashboard-Metriken (`searchSpaceSize`, `macroMoveUsage`,
  `counterexampleStats`) direkt im Replay-Panel

## Reports

Automatisch erzeugte Artefakte:

- `discovery-report.json`
- `discovery-report.html`
- `discovery-report.md`
- `discovery-replay.json`
- `discovery-summary.png`
- `discovery-replay.gif`

Die HTML-/Markdown-/Replay-JSON-Reports enthalten zusätzlich:

- `searchSpaceSize`
- `matchStats`
- `macroMoveUsage`
- `memoryUsage`
- `counterexampleStats`
- `proofSuccessRate`
- `artifactCounts`

Discovery-Zeilen tragen zusätzlich die zentrale Klassifikation
`DiscoveryResultKind` und eine einheitliche Summary-Tabelle mit:

| Spalte | Inhalt |
|--------|--------|
| `expression` | Eingabeausdruck |
| `operator` | beteiligter Hypothesenoperator |
| `resultKind` | `NO_CANDIDATE`, `HYPOTHESIS_ONLY`, `BRIDGE_FOUND`, `TRANSFORMED` oder `FALSE_POSITIVE` |
| `bridge?` | ob ein Bridge-Zustand im Replay erreicht wurde |
| `simplified/factored?` | ob ein transformiertes Ziel erreicht wurde |
| `learnedMacro?` / `macroReused?` | Makro-Lern- und Wiederverwendungsstatus |
| `proofStatus` | Validierungs-/Counterexample-Status |
| `rulePath` | echte Regel-IDs aus dem Replay |
| `notes` | kurze Zusammenfassung |

Die generierte Discovery-Gallery im Markdown-Report verwendet ausschließlich
vorhandene Replay-Pfade, Rule-Paths, `GalleryDiscoveryDescriptor`-Eligibility und
den bestehenden Mermaid-Export. Wenn ein Lauf keinen passenden Descriptor erfüllt,
wird keine Gallery-Demo erfunden.
Aktuelle Descriptoren decken Sophie-Germain-Replays, gelernte Makro-Reuse-Evidenz
und Telescoping-Fraction-Replays ab; Telescoping erscheint nur bei
`hypothesis_telescoping_fraction`, validierter Evidence und einem Replay-Pfad mit
`1/u - 1/(u + 1)`-Struktur.

Browser-E2E und Doku-Assets:

```bash
./gradlew :app:e2eTest --tests de.regelsuche.e2e.BrowserDemoFlowTest
./gradlew :app:e2eTest -Pregelsuche.recordDocs=true
```

Siehe auch:

- [docs/replay-mode.md](replay-mode.md)
- [docs/testing.md](testing.md)
- [docs/demo-gallery.md](demo-gallery.md)

## Convergent reports

`ConvergentDiscoveryReport` summarizes cases where the same canonical
expression is reached by at least two distinct rule paths. A path is distinct
only when its rule sequence or non-normalization rule family differs; duplicate
normalization-only variants are rejected so the gallery cannot imply fake
convergence.

The lightweight `RuleFamilyClassifier` labels rules for readable graph edges:
expansion/distribution, complete square, hidden structure, factorization,
learned macro, telescoping, rationalization, normalization, or other.

Gallery eligibility requires at least two convergent paths and at least two
distinct non-normalization families. Generated snippets include input, target,
path count, path families, shortest path, most didactic path, macro shortcut
path when present, validation status, and source replay ids. Mermaid graphs are
rendered from report data by `ConvergentDiscoveryMermaidWriter`.

## Discovery profiles

Reports entstehen aus Discovery-Läufen, deren Engine über `DiscoveryOptions` und
`DiscoveryProfile` zusammengesetzt wird:

| Profil | Zweck |
|--------|-------|
| `PURE_REWRITE` | deterministische Baseline ohne Hypothesenoperatoren und ohne gelernte Makros |
| `HYPOTHESIS_ONLY` | Hidden-Structure-Experimente ohne Makro-Lernen oder Makro-Reuse |
| `MACRO_REUSE_ONLY` | Prüfung eines aktivierten Makroregel-Inventars ohne neue Hypothesengenerierung |
| `HYPOTHESIS_AND_MACRO_REUSE` | Engine-Profil mit Hypothesenoperatoren und Wiederverwendung bereits gelernter Makros |
| `RESEARCH_DISCOVERY_PIPELINE` | Orchestrierungsprofil mit Hypothesen, Makro-Reuse, optionalem Makro-Lernen/Promotion und Gallery |

Die `HypothesisOperatorRegistry` liefert stabile Rule-IDs, Display-Namen, Familien
und Tags für Reports und hält die Operator-Reihenfolge zentral.
`DiscoveryEngineFactory` dokumentiert und erzwingt
die deterministische Komposition **base rewrite → hypothesis operators → learned
macro moves**. Lernen und Promotion gehören zur Workflow-Orchestrierung und werden
über `DiscoveryLearningOptions` gesteuert, nicht über die Engine-Factory.

Eine neue Gallery wird nur ergänzt, wenn ein echtes Replay mit passenden Rule-IDs
vorliegt. Für neue Operatoren gilt: erst `HypothesisOperator` implementieren, dann
in die Registry aufnehmen, Corpus- und False-Positive-Tests ergänzen und nur bei
nachweisbarem Replay eine Gallery-Darstellung aktivieren.

Der `ConservativeCompleteSquareHypothesisOperator` ist ein bounded conservative
square-completion Operator: Er emittiert nur Kandidaten mit Rest `0` oder
negativem perfekten Quadrat und deckt nicht alle algebraisch möglichen
quadratischen Ergänzungen ab.

`TelescopingFractionHypothesisOperator` und `RationalizationHypothesisOperator`
sind ebenfalls bewusst begrenzt. Near-Misses wie `1/(n*(n+2))`,
`1/(sqrt(x)+sqrt(y))` oder `1/(sqrt(x)+y)` werden im ersten Schritt nicht als
validierte Discoveries berichtet.
