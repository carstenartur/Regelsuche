# Didaktik-Ranking (`sort=teaching`)

Wer Pfade für den Unterricht sucht, will nicht den minimal-Score-Pfad, sondern
den *übersichtlichsten*: wenige Schritte, monotone Vereinfachung, möglichst
viele bekannte Regeln, mit Begründungen.

`de.regelsuche.search.TeachingPathScorer` berechnet eine Gesamtbewertung
in `[0, 1]` aus 5 Komponenten:

| Komponente              | Gewicht | Quelle                                                                       |
|-------------------------|---------|------------------------------------------------------------------------------|
| `stepCount`             | 0.30    | `1 / (1 + steps.size())` – kürzer ist besser.                                |
| `usesKnownRulesRatio`   | 0.20    | Anteil der Schritte, deren Regel `KnownRuleRepository` als bekannt einstuft. |
| `expansionPenalty`      | 0.20    | `1 - Anteil(steps mit scoreAfter > scoreBefore)`.                            |
| `monotonicityScore`     | 0.20    | Anteil der Übergänge mit nicht-steigender Komplexität.                       |
| `justificationCoverage` | 0.10    | Anteil der Schritte mit nicht-leerer `explanation`.                          |

## Beispiel

Eine zweischrittige Vereinfachung mit bekannter Regel und Begründung erzielt
in der Regel ~0.7. Ein vierschrittiger Pfad mit einer Expansionsstufe ohne
Erklärung landet bei ~0.4.

## API

`GET /api/paths?sort=teaching[&limit=N]` sortiert nach diesem Score absteigend.
Andere Modi: `score` (Default), `length`, `proof`.

## Tests

- `TeachingPathScorerTest#ranksTeachingPath`
- `PathSortersTest` (alle Sortier-Modi)

## Ausblick

- Domänen-spezifische Gewichte (z. B. höheres Gewicht für `monotonic` bei
  Bruchrechnung, höheres `known` bei Polynomrechnung).
- Per-User-Profil: A/B-Vergleich der Modi `score` vs. `teaching` mit Lehrkraft-
  Feedback.
