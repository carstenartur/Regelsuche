# Regelsuche – Benchmark-Qualitätsdashboard

Automatisch generiert von `./gradlew benchmarkReport`. Jede Zeile zeigt neben den klassischen Suchmetriken auch Qualitätsmetriken: ob das erwartete Ergebnis getroffen wurde, wie viele Zustände geprunet wurden, e-Graph-Größe, Matcher-Scan-/Cache-Metriken, Saturation-Einsparungen, ob eine gelernte Makroregel beteiligt war und ob das Export-Bundle für diese Zeile gültig ist.

**Ampel:** ✅ OK · ⚠️ WARN · ❌ FAIL

## known-identities

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | x + 0 | ✅ | ✓ | ✓ | 85 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x + 0 | ✅ | ✓ | ✓ | 7 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x + 0 | ✅ | ✓ | ✓ | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x + 0 | ✅ | ✓ | ✓ | 5 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x + 0 | ✅ | ✓ | ✓ | 5 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x + 0 | ✅ | ✓ | ✓ | 5 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x * 1 | ✅ | ✓ | ✓ | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x * 1 | ✅ | ✓ | ✓ | 2 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x * 1 | ✅ | ✓ | ✓ | 2 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x * 1 | ✅ | ✓ | ✓ | 2 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x * 1 | ✅ | ✓ | ✓ | 5 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x * 1 | ✅ | ✓ | ✓ | 4 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x * 0 | ✅ | ✓ | ✓ | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x * 0 | ✅ | ✓ | ✓ | 2 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x * 0 | ✅ | ✓ | ✓ | 2 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x * 0 | ✅ | ✓ | ✓ | 2 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x * 0 | ✅ | ✓ | ✓ | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x * 0 | ✅ | ✓ | ✓ | 9 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | a + a | ❌ | ✗ | — | 21 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| beam | a + a | ❌ | ✗ | — | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| a-star | a + a | ❌ | ✗ | — | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| random-mc | a + a | ❌ | ✗ | — | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| mcts | a + a | ❌ | ✗ | — | 5 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| hybrid | a + a | ❌ | ✗ | — | 12 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |

## polynomial-simplification

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | (x + 1)*(x + 1) | ✅ | ✓ | — | 121 | 22 | 82 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + 1)*(x + 1) | ✅ | ✓ | — | 44 | 21 | 46 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + 1)*(x + 1) | ✅ | ✓ | — | 48 | 21 | 76 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + 1)*(x + 1) | ✅ | ✓ | — | 36 | 25 | 64 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + 1)*(x + 1) | ✅ | ✓ | — | 825 | 1500 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + 1)*(x + 1) | ✅ | ✓ | — | 27 | 43 | 128 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x*x + x*x | ✅ | ✓ | — | 4 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x*x + x*x | ✅ | ✓ | — | 2 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x*x + x*x | ✅ | ✓ | — | 3 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x*x + x*x | ✅ | ✓ | — | 3 | 9 | 25 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x*x + x*x | ✅ | ✓ | — | 62 | 170 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x*x + x*x | ✅ | ✓ | — | 5 | 18 | 42 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | (x + 2)*(x + 3) | ✅ | ✓ | — | 31 | 42 | 127 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + 2)*(x + 3) | ✅ | ✓ | — | 10 | 17 | 26 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + 2)*(x + 3) | ✅ | ✓ | — | 29 | 42 | 127 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + 2)*(x + 3) | ✅ | ✓ | — | 16 | 37 | 93 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + 2)*(x + 3) | ✅ | ✓ | — | 873 | 1500 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + 2)*(x + 3) | ✅ | ✓ | — | 23 | 59 | 153 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | (x + a)^2 | ✅ | ✓ | — | 20 | 26 | 106 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + a)^2 | ✅ | ✓ | — | 7 | 15 | 35 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + a)^2 | ✅ | ✓ | — | 16 | 26 | 106 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + a)^2 | ✅ | ✓ | — | 11 | 25 | 95 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + a)^2 | ✅ | ✓ | — | 358 | 1176 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + a)^2 | ✅ | ✓ | — | 17 | 41 | 141 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |

## rational-simplification

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | (x*y)/(x*z) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| beam | (x*y)/(x*z) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| a-star | (x*y)/(x*z) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| random-mc | (x*y)/(x*z) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| mcts | (x*y)/(x*z) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| hybrid | (x*y)/(x*z) | ❌ | ✗ | — | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| best-first | (a/b)*(c/d) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| beam | (a/b)*(c/d) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| a-star | (a/b)*(c/d) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| random-mc | (a/b)*(c/d) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| mcts | (a/b)*(c/d) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| hybrid | (a/b)*(c/d) | ❌ | ✗ | — | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| best-first | a/(b/c) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| beam | a/(b/c) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| a-star | a/(b/c) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| random-mc | a/(b/c) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| mcts | a/(b/c) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| hybrid | a/(b/c) | ❌ | ✗ | — | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |

## search-explosion

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 565 | 663 | 2232 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 10 | 20 | 55 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 388 | 664 | 2241 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 254 | 619 | 2059 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 1277 | 3000 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 324 | 683 | 2287 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |

## equations

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | x + 3 = 7 | ✅ | ✓ | — | 6 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | SYMBOLICALLY_VERIFIED | ✓ |

## inequalities

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | (0 - 2) * x < 4 | ✅ | ✓ | — | 3 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | SYMBOLICALLY_VERIFIED | ✓ |

## calculus

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | diff(x ^ 3, x) | ✅ | ✓ | — | 6 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | SYMBOLICALLY_VERIFIED | ✓ |

## linear-algebra

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | A * (B + C) | ✅ | ✓ | — | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | SYMBOLICALLY_VERIFIED | ✓ |

