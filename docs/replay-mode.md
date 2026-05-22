# Replay-Modus

Der **Replay-Modus** stellt einen entdeckten Rechenweg Schritt-für-Schritt dar – mit
Vorher/Nachher-Ausdruck (Text + LaTeX), Regelbegründung und Komplexitäts-Delta.

## API

`GET /api/paths/{id}/replay` liefert ein `PathReplayDto`:

```json
{
  "pathId": "path-1",
  "steps": [
    {
      "stepIndex": 0,
      "fromExpression": "a + 0 + 0",
      "fromLatex": "a + 0 + 0",
      "toExpression": "a + 0",
      "toLatex": "a + 0",
      "ruleId": "ast_add_zero_right",
      "ruleExplanation": "Regel: Neutrales Element der Addition (rechts)\n…",
      "scoreDelta": -2,
      "equivalencePreserving": true
    }
  ]
}
```

Die Regelbegründung kommt aus `ExplanationService` (Form `SCHOOL`).
LaTeX wird derzeit per einfachem `*` → `\cdot` Mapping erzeugt; eine vollwertige
LaTeX-Renderkette (KaTeX/MathJax) ist im UI-Schritt vorgesehen.

## UI

Tab **Replay**:

- Dropdown listet alle in der Datenbank verfügbaren Pfade (sortiert nach Score).
- *Laden* zieht den Replay-Stream vom Server.
- *▶ / ⏸* startet/stoppt die automatische Wiedergabe (1.2 s pro Schritt).
- *⟵ / ⟶* navigiert manuell.
- Pro Schritt sieht man Vorher-/Nachher-Ausdruck (Text + LaTeX), Regel-Id,
  Komplexitäts-Delta und die schulbuchstilisierte Erklärung der Regel.

## Stage 3 — Farb-Diff & Vergleichszeichen-Hinweis

Seit Stage 3 hebt der Replay-Tab Änderungen zwischen aufeinanderfolgenden
Schritten visuell hervor. Server-seitig liefert `PathReplayDto.ReplayStep`
drei neue Felder, die `MathPresentation.alignedDerivationLatexWithDiff(…)`
auswertet:

* `comparatorFlipped` — `true`, wenn die Regel `inequality_multiply_both_sides`
  oder `inequality_divide_both_sides` greift **und** sich das
  Vergleichszeichen (`<`/`>`/`\le`/`\ge`) zwischen `fromExpression` und
  `toExpression` umkehrt. Wird ausschließlich auf dem Server berechnet
  (`MathPresentation.detectComparatorFlip(...)`); die alte JS-Heuristik
  ist entfernt.
* `changedFromSpans` / `changedToSpans` — Listen von `[start, length]`-
  Paaren über `fromLatex` / `toLatex`. Werden vom AST-Token-Diff
  (`MathDiff.diffSpans(...)`) ermittelt und vom Codec mitserialisiert.

Im Frontend rendert `app.js` jeden Schritt mit
`\htmlClass{diff-old}{…}` / `\htmlClass{diff-new}{…}`-Wrappern
(KaTeX-Trust-Mode aktiv). Der fokussierte Schritt erhält die Klasse
`replay-derivation-focus`. CSS-Tokens: `--diff-old`, `--diff-new`,
`--danger`. Der „Vergleichszeichen gedreht"-Hinweis wird ausschließlich
vom Server-Flag gesteuert (Klasse `.replay-flip-notice`).

## Stage 5 — Layout-Pipeline

`PathReplayDto.derivationLayout()` liefert eine strukturierte
`MathLayout` mit `kind=ALIGNED` und einer `ALIGNED_ROW` pro Schritt
(plus Quell-Zeile). Layout-fähige Front-Ends (`renderMathLayout(layout,
host)`) rendern die Zeilen unter `.math-aligned-rows` als CSS-Grid, so
dass Per-Row-Hover, Diff-Klassen und ein vom AST abgeleitetes
`aria-label` ohne LaTeX-Operationen möglich sind. Diff-CSS-Klassen
liegen dabei als Knoten-Attribute vor (keine `\htmlClass`-Wrappers),
der KaTeX-Trust-Mode ist für Diffs in diesem Pfad nicht mehr nötig.

## Tests

- `de.regelsuche.web.SearchGraphEndpointsTest#replaysTransformationPath`
- UI-Sanity: `WebWorkbenchAssetsTest` prüft, dass die Replay-Tab-Hooks im JS- und
  HTML-Asset enthalten sind.
- Stage 3: `MathPresentationTest`, `PathReplayDtoTest`,
  `SearchGraphRepositoryTest#codecRoundTripsStage3ReplayDiffPayload`,
  `WebUiMathPipelineTest#appJsUsesServerComparatorFlipFlagAndDiffClasses`.
- Stage 5: `MathPresentationLayoutTest`, `AllMathDtosCarryLayoutTest`,
  `WebUiMathPipelineTest#appJsDefinesLayoutAwareRenderer`.
