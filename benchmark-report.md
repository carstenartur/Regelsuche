# Regelsuche – Benchmark-Qualitätsdashboard

Automatisch generiert von `./gradlew benchmarkReport`. Jede Zeile zeigt neben den klassischen Suchmetriken auch Qualitätsmetriken: ob das erwartete Ergebnis getroffen wurde, wie viele Zustände geprunet wurden, e-Graph-Größe, Matcher-Scan-/Cache-Metriken, Saturation-Einsparungen, ob eine gelernte Makroregel beteiligt war und ob das Export-Bundle für diese Zeile gültig ist.

**Ampel:** ✅ OK · ⚠️ WARN · ❌ FAIL

## known-identities

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | x + 0 | ✅ | ✓ | ✓ | 182 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x + 0 | ✅ | ✓ | ✓ | 29 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x + 0 | ✅ | ✓ | ✓ | 14 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x + 0 | ✅ | ✓ | ✓ | 18 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x + 0 | ✅ | ✓ | ✓ | 23 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x + 0 | ✅ | ✓ | ✓ | 12 | 8 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x * 1 | ✅ | ✓ | ✓ | 7 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x * 1 | ✅ | ✓ | ✓ | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x * 1 | ✅ | ✓ | ✓ | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x * 1 | ✅ | ✓ | ✓ | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x * 1 | ✅ | ✓ | ✓ | 8 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x * 1 | ✅ | ✓ | ✓ | 11 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x * 0 | ✅ | ✓ | ✓ | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x * 0 | ✅ | ✓ | ✓ | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x * 0 | ✅ | ✓ | ✓ | 6 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x * 0 | ✅ | ✓ | ✓ | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x * 0 | ✅ | ✓ | ✓ | 5 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x * 0 | ✅ | ✓ | ✓ | 6 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | a + a | ❌ | ✗ | — | 32 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| beam | a + a | ❌ | ✗ | — | 14 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| a-star | a + a | ❌ | ✗ | — | 11 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| random-mc | a + a | ❌ | ✗ | — | 7 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| mcts | a + a | ❌ | ✗ | — | 19 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| hybrid | a + a | ❌ | ✗ | — | 19 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |

## polynomial-simplification

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | (x + 1)*(x + 1) | ✅ | ✓ | — | 229 | 22 | 82 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + 1)*(x + 1) | ✅ | ✓ | — | 127 | 21 | 46 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + 1)*(x + 1) | ✅ | ✓ | — | 140 | 21 | 76 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + 1)*(x + 1) | ✅ | ✓ | — | 85 | 25 | 64 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + 1)*(x + 1) | ✅ | ✓ | — | 2237 | 1500 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + 1)*(x + 1) | ✅ | ✓ | — | 48 | 43 | 128 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x*x + x*x | ✅ | ✓ | — | 6 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x*x + x*x | ✅ | ✓ | — | 3 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x*x + x*x | ✅ | ✓ | — | 5 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x*x + x*x | ✅ | ✓ | — | 4 | 9 | 25 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x*x + x*x | ✅ | ✓ | — | 106 | 170 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x*x + x*x | ✅ | ✓ | — | 9 | 18 | 42 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | (x + 2)*(x + 3) | ✅ | ✓ | — | 57 | 42 | 127 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + 2)*(x + 3) | ✅ | ✓ | — | 17 | 17 | 26 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + 2)*(x + 3) | ✅ | ✓ | — | 47 | 42 | 127 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + 2)*(x + 3) | ✅ | ✓ | — | 38 | 37 | 93 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + 2)*(x + 3) | ✅ | ✓ | — | 1323 | 1500 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + 2)*(x + 3) | ✅ | ✓ | — | 65 | 59 | 153 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | (x + a)^2 | ✅ | ✓ | — | 63 | 26 | 106 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + a)^2 | ✅ | ✓ | — | 15 | 15 | 35 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + a)^2 | ✅ | ✓ | — | 21 | 26 | 106 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + a)^2 | ✅ | ✓ | — | 11 | 25 | 95 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + a)^2 | ✅ | ✓ | — | 522 | 1214 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + a)^2 | ✅ | ✓ | — | 18 | 41 | 141 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |

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
| best-first | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 577 | 663 | 2232 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 14 | 20 | 55 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 431 | 664 | 2241 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 295 | 619 | 2059 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 1492 | 3000 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 563 | 683 | 2287 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |

## equations

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | x + 3 = 7 | ✅ | ✓ | — | 9 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | SYMBOLICALLY_VERIFIED | ✓ |

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

