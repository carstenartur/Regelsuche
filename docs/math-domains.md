# Math-Domains

Regelsuche unterscheidet vier semantische Mathematik-Domänen, die alle
durch denselben Suchkern laufen, aber jeweils eigene Replay-Karten,
Demo-Buttons und Discovery-Tags besitzen.

| Domäne | Demo-ID | Beispiel | Replay-Karte |
| --- | --- | --- | --- |
| Gleichungen | `math-equation` | `x + 3 = 7 → x = 4` | Lösungsweg-Schulform |
| Ungleichungen | `math-inequality` | `-2·x < 4 → x > -2` | „Vergleichszeichen gedreht"-Warnung |
| Analysis | `math-derivative` | `d/dx x³ → 3·x²` | Regelkarte (Potenz-/Summen-/Produktregel) |
| Lineare Algebra | `math-matrix` | `A·(B+C) → A·B + A·C` | `\begin{bmatrix}`-Vorschau |

## Produktintegration

Alle vier Demos werden im
[`UnifiedMathDomainWorkbench`](../app/src/main/java/de/regelsuche/demo/UnifiedMathDomainWorkbench.java)
ausgeführt; der `DemoService` delegiert lediglich. Demo-JSON enthält
deshalb für jede Math-Domain-Demo:

* `expressionType` — `EQUATION`, `INEQUALITY`, `DERIVATIVE`, `MATRIX`,
* `LaTeX` für vorher/nachher,
* `proofOutcome` (`OBSERVED`, `VALIDATED_BY_EXAMPLES`,
  `SYMBOLICALLY_VERIFIED`, `FORMALLY_PROVED`),
* `comparatorFlipped: true|false` bei Ungleichungen.

Sichtbar wird das in der Demo-Hero-Sektion der Landing-Page:

* Ungleichungs-Demo: roter Hinweis **„Vergleichszeichen wurde gedreht"**
  sowohl im Summary als auch auf der zugehörigen Replay-Karte
  (CSS-Klasse `replay-flip-notice`),
* Ableitungs-Demo: dedizierte Regelkarte mit Titel
  „Potenzregel" / „Summenregel" / „Produktregel" und kurzer
  Erklärung,
* Matrix-Demo: `bmatrix`-LaTeX-Block sowohl im Summary
  (`math-matrix-panel`) als auch im Replay
  (`replay-matrix-card`),
* Gleichungs-Demo: Lösungsweg als Schulform-Tabelle
  (`math-equation-panel`).

## Discovery+ mit Domain-Tags

Beim Mining werden Makroregeln automatisch mit dem zugehörigen
Domain-Tag versehen. Siehe
[`RuleCandidateMiner`](../app/src/main/java/de/regelsuche/mining/RuleCandidateMiner.java)
und das Feld `domain` in den exportierten Regeln. Erlaubte Werte:

* `equations`
* `inequalities`
* `calculus`
* `linear-algebra`

Die Tags fließen ins Benchmark-Dashboard, das Math-Demos nach Domäne
gruppiert anzeigt (Algebra / Gleichungen / Ungleichungen / Analysis /
Lineare Algebra).

## Tests

* Architektur-Tests: `app/src/test/java/de/regelsuche/demo/UnifiedMathDomainWorkbenchTest.java`
* Browser-Tests:
  [`BrowserDemoFlowTest`](../app/src/e2eTest/java/de/regelsuche/e2e/BrowserDemoFlowTest.java)
  enthält für jede Domäne mindestens einen Flow
  (`mathEquationBrowserFlow`, `inequalityReplayShowsFlipWarning`,
  `mathDerivativeBrowserFlow`, `mathMatrixBrowserFlow`).

Eine bildliche Übersicht steht in der [Demo-Gallery](demo-gallery.md).
