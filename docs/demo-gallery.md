# Demo-Gallery

Jede Demo unten wird von einem **echten Playwright-Browsertest** abgenommen, der
denselben Screenshot erzeugt. So bleibt die Doku immer aktuell — wenn die
Funktion bricht, fällt der Screenshot weg, weil der Test rot wird.

Aktualisieren der Gallery:

```bash
./gradlew e2eTest -Pregelsuche.recordDocs=true
```

Die Tests stehen in
[`app/src/e2eTest/java/de/regelsuche/e2e/BrowserDemoFlowTest.java`](../app/src/e2eTest/java/de/regelsuche/e2e/BrowserDemoFlowTest.java).

---

## Binomische Formel — `(x+3)² → 9 + 6·x + x²`

![Binomial-Demo](assets/screenshots/binomial-graph.png)

| Aspekt | Wert |
| --- | --- |
| Demo-ID | `binomial` |
| Erwarteter Rechenweg | `(x+3)^2 → x*(x+3)+3*(x+3) → x^2+3x+3x+9 → x^2+6x+9` |
| Test | `binomialDemoBrowserFlow()` |
| Export | `GET /api/exports/bundle.zip` nach Demo-Klick |

## Bruchkürzung mit Annahme `x ≠ 0`

![Rational-Demo](assets/screenshots/rational-graph.png)

| Aspekt | Wert |
| --- | --- |
| Demo-ID | `rational` |
| Erwarteter Rechenweg | `(x·y)/(x·z) → y/z`, sofern `x ≠ 0` |
| Test | `rationalDemoBrowserFlow()` |
| Hinweis | Die Annahme `x ≠ 0` wird explizit im Summary-Panel ausgewiesen. |

## Trigonometrische Identität

![Trigonometry-Demo](assets/screenshots/trigonometry-graph.png)

| Aspekt | Wert |
| --- | --- |
| Demo-ID | `trigonometry` |
| Erwarteter Rechenweg | `sin(x)^2 + cos(x)^2 → 1` (Pythagoras) |
| Test | `trigonometryDemoBrowserFlow()` |

## Polynom-Expansion

![Polynomial-Expansion-Demo](assets/screenshots/polynomial-expansion-graph.png)

| Aspekt | Wert |
| --- | --- |
| Demo-ID | `polynomial-expansion` |
| Erwarteter Rechenweg | `(x+1)·(x+2) → x² + 3·x + 2` |
| Test | `polynomialExpansionBrowserFlow()` |

## Macro-Learning

![Macro-Learning-Demo](assets/screenshots/macro-learning-summary.png)

| Aspekt | Wert |
| --- | --- |
| Demo-ID | `macro-learning` |
| Beobachtung | Nach drei Beispielen aktiviert `MacroRuleLearningService` die binomische Formel als Makroregel; der vierte Lauf nutzt sie. |
| Test | `macroLearningBrowserFlow()` |

## Math-Domain: Lineare Gleichung

![Equation-Demo](assets/screenshots/math-equation-school-form.png)

| Aspekt | Wert |
| --- | --- |
| Demo-ID | `math-equation` |
| Erwarteter Rechenweg | `x + 3 = 7  → x = 4` (Schulform-Lösungsweg) |
| Test | `mathEquationBrowserFlow()` |

## Math-Domain: Ungleichung mit Vergleichszeichen-Flip

![Inequality-Warnung](assets/screenshots/inequality-flip-warning.png)

![Inequality-Replay](assets/screenshots/inequality-replay.png)

| Aspekt | Wert |
| --- | --- |
| Demo-ID | `math-inequality` |
| Erwarteter Rechenweg | `-2·x < 4  → x > -2` mit explizitem Vorzeichen-Flip |
| Test | `inequalityReplayShowsFlipWarning()` |
| Besonderheit | Die Replay-Karte des kippenden Schritts trägt die CSS-Klasse `replay-flip-notice` und zeigt einen roten Hinweis. |

## Math-Domain: Ableitung (Regelkarte)

![Derivative-Card](assets/screenshots/math-derivative-card.png)

| Aspekt | Wert |
| --- | --- |
| Demo-ID | `math-derivative` |
| Erwarteter Rechenweg | `d/dx x³ → 3·x²` (Potenzregel) |
| Test | `mathDerivativeBrowserFlow()` |

## Math-Domain: Matrix-Distributivität

![Matrix-Preview](assets/screenshots/math-matrix-preview.png)

![Matrix-Replay](assets/screenshots/math-matrix-replay.png)

| Aspekt | Wert |
| --- | --- |
| Demo-ID | `math-matrix` |
| Erwarteter Rechenweg | `A·(B + C)  →  A·B + A·C` |
| Test | `mathMatrixBrowserFlow()` |
| Besonderheit | Die Replay-Karte rendert eine echte `\begin{bmatrix}`-Vorschau. |

## Proof-Bridge

![Proof-Bridge-Box](assets/screenshots/proof-bridge-result.png)

| Aspekt | Wert |
| --- | --- |
| Endpoint | `POST /api/proof-bridge` |
| UI | Button **Proof prüfen** im Demo-Summary jeder Math-Domain-Demo |
| Test | `proofBridgePanelShowsGeneratedScript()` |
| Status | `FORMALLY_PROVED` wird **nur** gesetzt, wenn der Prover (Lean/SMT) den Beweis bestätigt. |

## Export-Bundle

![Export-Bundle](assets/screenshots/export-bundle.png)

| Aspekt | Wert |
| --- | --- |
| Endpoint | `GET /api/exports/bundle.zip` |
| Inhalt | Markdown-Bericht, LaTeX, JSON, Mermaid, GraphML, Rule-Inventory |
| Test | `exportBundleDownloads()` |
