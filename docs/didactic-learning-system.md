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

## REST-Endpunkte

Die didaktische Schicht stellt drei Endpunkte über `WebWorkbenchServer`
bereit:

| Methode | Pfad                              | Body / Query                                                                                  |
|---------|-----------------------------------|-----------------------------------------------------------------------------------------------|
| `POST`  | `/api/didactic/step-check`        | `{"currentExpression":"…","studentStep":"…","difficulty":"MITTELSTUFE"}` → `Result`-JSON      |
| `POST`  | `/api/didactic/hint/{pathId}`     | `{"currentExpression":"…","pedagogyProfile":"SCHOOL"}` → gestufte Hinweise                    |
| `GET`   | `/api/didactic/misconceptions`    | listet den eingebauten `MisconceptionRule`-Katalog                                            |
| `GET`   | `/api/didactic/replay/{pathId}`   | Schritte plus token-Level `SymbolDiff` für die Replay-Ansicht                                 |
| `GET`   | `/api/didactic/analytics`         | aggregierter Snapshot (Misconception-Häufigkeit, Hinweis-Nutzung, Genauigkeit)                |
| `GET`   | `/api/didactic/export/worksheet/{pathId}.md` | Arbeitsblatt (Aufgabe + Leerzeilen)                                                |
| `GET`   | `/api/didactic/export/solution/{pathId}.md`  | Musterlösung (alle Schritte mit Begründung)                                        |
| `GET`   | `/api/didactic/export/teacher/{pathId}.md`   | Lehrermodus (Schritte + Symbol-Diff + pädagogische Hinweise)                       |

`GET /api/didactic` liefert die Endpunkt-Übersicht.

`POST /api/search` akzeptiert zusätzlich `"goal":"DIDAKTIK"`, das das
`DidacticCostModel` (Default `MITTELSTUFE / SCHOOL`) in die bestehende
Suchpipeline einhängt — ohne Änderungen an den Strategien.

## Was bewusst noch nicht enthalten ist

- Optional LLM-gestützte Formulierung kann über die Schnittstelle
  `LlmHintPhraser` angebunden werden — diese Sitzung liefert nur den
  No-Op-Adapter; konkrete Modell-Aufrufe sind bewusst extern gehalten.

Sie können auf der hier eingeführten API direkt aufgebaut werden, ohne
weitere Refactorings an der Suchpipeline.
