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

## Tests

- `de.regelsuche.web.SearchGraphEndpointsTest#replaysTransformationPath`
- UI-Sanity: `WebWorkbenchAssetsTest` prüft, dass die Replay-Tab-Hooks im JS- und
  HTML-Asset enthalten sind.
