# Regelsuche – Benchmark-Qualitätsdashboard

Automatisch generiert von `./gradlew benchmarkReport`. Jede Zeile zeigt neben den klassischen Suchmetriken auch Qualitätsmetriken: ob das erwartete Ergebnis getroffen wurde, wie viele Zustände geprunet wurden, e-Graph-Größe, Matcher-Scan-/Cache-Metriken, Saturation-Einsparungen, ob eine gelernte Makroregel beteiligt war und ob das Export-Bundle für diese Zeile gültig ist.

**Ampel:** ✅ OK · ⚠️ WARN · ❌ FAIL

## known-identities

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | x + 0 | ✅ | ✓ | ✓ | 122 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x + 0 | ✅ | ✓ | ✓ | 10 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x + 0 | ✅ | ✓ | ✓ | 5 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x + 0 | ✅ | ✓ | ✓ | 5 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x + 0 | ✅ | ✓ | ✓ | 8 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x + 0 | ✅ | ✓ | ✓ | 8 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x * 1 | ✅ | ✓ | ✓ | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x * 1 | ✅ | ✓ | ✓ | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x * 1 | ✅ | ✓ | ✓ | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x * 1 | ✅ | ✓ | ✓ | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x * 1 | ✅ | ✓ | ✓ | 6 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x * 1 | ✅ | ✓ | ✓ | 5 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x * 0 | ✅ | ✓ | ✓ | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x * 0 | ✅ | ✓ | ✓ | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x * 0 | ✅ | ✓ | ✓ | 2 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x * 0 | ✅ | ✓ | ✓ | 2 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x * 0 | ✅ | ✓ | ✓ | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x * 0 | ✅ | ✓ | ✓ | 5 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | a + a | ❌ | ✗ | — | 14 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| beam | a + a | ❌ | ✗ | — | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| a-star | a + a | ❌ | ✗ | — | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| random-mc | a + a | ❌ | ✗ | — | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| mcts | a + a | ❌ | ✗ | — | 6 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |
| hybrid | a + a | ❌ | ✗ | — | 7 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | OBSERVED | ✓ |

## polynomial-simplification

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | (x + 1)*(x + 1) | ✅ | ✓ | — | 160 | 29 | 114 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + 1)*(x + 1) | ✅ | ✓ | — | 41 | 18 | 33 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + 1)*(x + 1) | ✅ | ✓ | — | 85 | 28 | 110 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + 1)*(x + 1) | ✅ | ✓ | — | 81 | 34 | 111 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + 1)*(x + 1) | ✅ | ✓ | — | 1394 | 1500 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + 1)*(x + 1) | ✅ | ✓ | — | 29 | 47 | 147 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x*x + x*x | ✅ | ✓ | — | 3 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x*x + x*x | ✅ | ✓ | — | 2 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x*x + x*x | ✅ | ✓ | — | 4 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x*x + x*x | ✅ | ✓ | — | 3 | 9 | 25 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x*x + x*x | ✅ | ✓ | — | 75 | 170 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x*x + x*x | ✅ | ✓ | — | 7 | 18 | 42 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | (x + 2)*(x + 3) | ✅ | ✓ | — | 36 | 42 | 127 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + 2)*(x + 3) | ✅ | ✓ | — | 12 | 17 | 26 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + 2)*(x + 3) | ✅ | ✓ | — | 34 | 42 | 127 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + 2)*(x + 3) | ✅ | ✓ | — | 18 | 37 | 93 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + 2)*(x + 3) | ✅ | ✓ | — | 598 | 1500 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + 2)*(x + 3) | ✅ | ✓ | — | 40 | 59 | 153 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | (x + a)^2 | ✅ | ✓ | — | 51 | 26 | 106 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + a)^2 | ✅ | ✓ | — | 21 | 15 | 35 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + a)^2 | ✅ | ✓ | — | 44 | 26 | 106 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + a)^2 | ✅ | ✓ | — | 34 | 25 | 95 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + a)^2 | ✅ | ✓ | — | 481 | 1195 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + a)^2 | ✅ | ✓ | — | 27 | 41 | 141 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |

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
| best-first | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 569 | 663 | 2232 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 12 | 20 | 55 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 442 | 664 | 2241 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 308 | 619 | 2059 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 1675 | 3000 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 388 | 683 | 2287 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | VALIDATED_BY_EXAMPLES | ✓ |

## equations

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | x + 3 = 7 | ✅ | ✓ | — | 9 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | SYMBOLICALLY_VERIFIED | ✓ |

## inequalities

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | (0 - 2) * x < 4 | ✅ | ✓ | — | 4 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | SYMBOLICALLY_VERIFIED | ✓ |

## calculus

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | diff(x ^ 3, x) | ✅ | ✓ | — | 7 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | SYMBOLICALLY_VERIFIED | ✓ |

## linear-algebra

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | A * (B + C) | ✅ | ✓ | — | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.00 | – | SYMBOLICALLY_VERIFIED | ✓ |

