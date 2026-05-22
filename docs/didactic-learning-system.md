# Didactic Learning Layer (`de.regelsuche.didactic`)

Regelsuche bleibt eine allgemeine mathematische Suchmaschine — Equality
Saturation, Discovery+, Makroregel-Lernen, SearchGraph, Proof-Workflows
und universelle Muster funktionieren unverändert. Diese Seite beschreibt
die **didaktische Schicht**, die parallel dazu auf derselben Architektur
aufbaut: ein zusätzliches Kostenmodell und eine kleine Sammlung
spezialisierter Services für Lernkontexte.

> Discovery und Didaktik sind keine Gegensätze: ein gutes Lernsystem
> braucht genau die Fähigkeit, viele alternative mathematische Wege
> sichtbar zu machen.

## Bausteine

| Klasse                                          | Zweck                                                                         |
|-------------------------------------------------|-------------------------------------------------------------------------------|
| `DifficultyLevel`                               | Schul-/Studienstufe. Filtert die zulässigen Regel-IDs (Grundschule … Experte).|
| `PedagogyProfile`                               | Stil der Darstellung (CONCISE, SCHOOL, VERY_DETAILED, ELEGANT, EXAM_FRIENDLY).|
| `DidacticCostModel`                             | `CostModel` mit Operator-Komplexität, Tiefe, Symbol-Last, Schwierigkeits-Budget. |
| `MisconceptionRule` + `MisconceptionDetector`   | Erkennt typische Fehlvorstellungen (falsches Kürzen, Vorzeichen, Ungleichungs-Flip). |
| `HintGenerator`                                 | Gestufte Hinweise: `SMALL → STRONG → FULL_STEP`.                              |
| `StudentStepValidator`                          | Prüft einen vom Nutzer eingegebenen Zwischenschritt.                          |
| `SymbolDiff`                                    | Token-Diff zwischen zwei Ausdrücken für die Didaktik-Replay-Ansicht.          |

## Wie es in die bestehende Architektur passt

```
            ┌─────────────────────────────────────────┐
            │   bestehende Suchpipeline               │
            │   (BestFirst / Beam / AStar /           │
            │    EqualitySaturation)                  │
            └──────────────┬──────────────────────────┘
                           │ verwendet
                           ▼
             ┌───────────────────────────┐
             │ CostModel (Goal-basiert)  │   ← didaktischer Modus:
             │  – SIMPLIFY               │      DidacticCostModel
             │  – FACTORIZE              │
             │  – TEACHING_FRIENDLY      │
             │  – DIDAKTIK (neu)         │
             └─────┬─────────────────────┘
                   │ liefert Pfade
                   ▼
         ┌─────────────────────────────────────────┐
         │ DiscoveredTransformation + Steps        │
         └─────┬─────────────────────────┬─────────┘
               │                         │
               ▼                         ▼
   ┌──────────────────────┐   ┌──────────────────────┐
   │ ExplanationService   │   │ HintGenerator        │
   │  (bereits vorhanden) │   │ StudentStepValidator │
   └──────────────────────┘   │ MisconceptionDetector│
                              │ SymbolDiff           │
                              └──────────────────────┘
```

- Der `DidacticCostModel` ist ein normales `CostModel` und kann via
  `TransformationGoal` an die existierenden Strategien gehängt werden,
  ohne neue Suchmaschinen einzuführen.
- `ExplanationService` (bereits in `de.regelsuche.explain`) liefert die
  natürlich-sprachliche Erklärung pro Schritt; der `HintGenerator`
  baut darauf auf und staffelt die Information.
- `MisconceptionDetector` arbeitet rein strukturell auf der AST. Wenn
  ein `EquivalenceService` injiziert wird, fordert er zusätzlich
  Nicht-Äquivalenz — false positives wären in einem Lernkontext
  schlimmer als ausgelassene Erkennungen.

## DifficultyLevel-Allowlist

Regeln werden per ID gefiltert. Eine Stufe erbt alle Regeln der
niedrigeren Stufen.

| Stufe          | Beispiele für freigeschaltete Regeln                            |
|----------------|------------------------------------------------------------------|
| `GRUNDSCHULE`  | `ast_add_zero_*`, `ast_multiply_one_*`, `ast_multiply_zero_*`    |
| `MITTELSTUFE`  | + Distributivität, Zusammenfassen, einfache Brüche               |
| `OBERSTUFE`    | + Potenzgesetze, Trig-Identitäten, Logarithmen, Basis-Calculus    |
| `UNIVERSITAET` | + Kettenregel, Quotientenregel, Equality Saturation, Makroregeln |
| `EXPERTE`      | alles                                                            |

## Misconception-Katalog (Standard)

| ID                                       | Beispiel                | Typische Ursache                                                |
|------------------------------------------|-------------------------|------------------------------------------------------------------|
| `false_cancellation_sum_in_numerator`    | `(a + b) / b → a`       | Distributivität fälschlich auf einen Quotienten angewendet.      |
| `sign_distribution_partial`              | `-(a + b) → -a + b`     | Vorzeichen nur auf den ersten Summanden verteilt.                |
| `inequality_missing_flip`                | `-2·x < 4 → x < -2`     | Vergleichszeichen beim Teilen durch -2 nicht umgedreht.          |

Der Katalog ist absichtlich klein und konservativ gehalten; weitere
Einträge können über `MisconceptionDetector` per Konstruktor injiziert
werden, sobald der Bedarf entsteht.

## Hint-System

Pro Schritt erzeugt der `HintGenerator` drei Stufen:

1. **SMALL** — Zielrichtung (z. B. „Multipliziere die Klammer aus.“),
   ohne die Operation zu nennen.
2. **STRONG** — natürliche Begründung (z. B. „Multiplikation wird über
   die Addition verteilt.“) aus dem `ExplanationService`.
3. **FULL_STEP** — der konkrete `before → after`-Schritt.

## Tests

Die in der Spezifikation genannten Tests liegen in
`de.regelsuche.didactic.*Test`:

- `DidacticCostModelTest#didacticCostModelPrefersSimpleSchoolPath()`
- `MisconceptionDetectorTest#misconceptionRuleDetectsFalseCancellation()`
- `HintGeneratorTest#hintSystemProducesGraduatedHints()`
- `StudentStepValidatorTest#studentStepValidationRejectsWrongTransformation()`
- `SymbolDiffTest#replayHighlightsChangedSymbols()`
- `DifficultyLevelTest#difficultyLevelRestrictsAdvancedRules()`

Ausführen mit:

```bash
./gradlew test --tests 'de.regelsuche.didactic.*'
```

## Was bewusst noch nicht enthalten ist

Damit dieser PR überschaubar bleibt, sind die folgenden Punkte der
Spezifikation **vorbereitet** (durch die hier eingeführten Services),
aber noch nicht ausgeliefert:

- REST-Endpunkte `POST /api/didactic/step-check` und
  `POST /api/didactic/hint` (Web-Layer);
- Didaktik-Replay-Ansicht in der Web-Workbench-UI mit
  `SymbolDiff`-Highlighting;
- Demo-Gallery-Einträge „Mehrere Lösungswege“, „Typischer Fehler“,
  „Hinweis-Modus“, „Schrittprüfung“;
- Didaktik-Analytics (Persistenz, Dashboard);
- Bildungsspezifische Exportformate (Arbeitsblatt, Musterlösung,
  Lehrermodus-PDF/HTML);
- Optionale LLM-gestützte Formulierung der Erklärungen und Hinweise.

Sie können auf der hier eingeführten API direkt aufgebaut werden, ohne
weitere Refactorings an der Suchpipeline.
