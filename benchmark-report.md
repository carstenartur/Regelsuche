# Regelsuche – Benchmark-Qualitätsdashboard

Automatisch generiert von `./gradlew benchmarkReport`. Jede Zeile zeigt neben den klassischen Suchmetriken auch Qualitätsmetriken: ob das erwartete Ergebnis getroffen wurde, wie viele Zustände geprunet wurden, e-Graph-Größe, Matcher-Scan-/Cache-Metriken, ob eine gelernte Makroregel beteiligt war und ob das Export-Bundle für diese Zeile gültig ist.

**Sat-Sparung:** nicht gemessen. Ohne vergleichbaren Referenzlauf lassen sich aus Union- und Regelanwendungszahlen keine Einsparungen ableiten.

**Ampel:** ✅ OK · ⚠️ WARN · ❌ FAIL

## known-identities

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | x + 0 | ✅ | ✓ | ✓ | 163 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x + 0 | ✅ | ✓ | ✓ | 21 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x + 0 | ✅ | ✓ | ✓ | 8 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x + 0 | ✅ | ✓ | ✓ | 14 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x + 0 | ✅ | ✓ | ✓ | 19 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x + 0 | ✅ | ✓ | ✓ | 13 | 8 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x * 1 | ✅ | ✓ | ✓ | 8 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x * 1 | ✅ | ✓ | ✓ | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x * 1 | ✅ | ✓ | ✓ | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x * 1 | ✅ | ✓ | ✓ | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x * 1 | ✅ | ✓ | ✓ | 8 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x * 1 | ✅ | ✓ | ✓ | 7 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x * 0 | ✅ | ✓ | ✓ | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x * 0 | ✅ | ✓ | ✓ | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x * 0 | ✅ | ✓ | ✓ | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x * 0 | ✅ | ✓ | ✓ | 3 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x * 0 | ✅ | ✓ | ✓ | 12 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x * 0 | ✅ | ✓ | ✓ | 6 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | a + a | ❌ | ✗ | — | 21 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| beam | a + a | ❌ | ✗ | — | 5 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| a-star | a + a | ❌ | ✗ | — | 5 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| random-mc | a + a | ❌ | ✗ | — | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| mcts | a + a | ❌ | ✗ | — | 8 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| hybrid | a + a | ❌ | ✗ | — | 21 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |

## polynomial-simplification

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | (x + 1)*(x + 1) | ✅ | ✓ | — | 194 | 22 | 82 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + 1)*(x + 1) | ✅ | ✓ | — | 155 | 21 | 46 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + 1)*(x + 1) | ✅ | ✓ | — | 186 | 21 | 76 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + 1)*(x + 1) | ✅ | ✓ | — | 137 | 25 | 64 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + 1)*(x + 1) | ✅ | ✓ | — | 2463 | 1500 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + 1)*(x + 1) | ✅ | ✓ | — | 42 | 43 | 128 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x*x + x*x | ✅ | ✓ | — | 6 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x*x + x*x | ✅ | ✓ | — | 4 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x*x + x*x | ✅ | ✓ | — | 5 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x*x + x*x | ✅ | ✓ | — | 5 | 9 | 25 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x*x + x*x | ✅ | ✓ | — | 118 | 170 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x*x + x*x | ✅ | ✓ | — | 10 | 18 | 42 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | (x + 2)*(x + 3) | ✅ | ✓ | — | 63 | 42 | 127 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + 2)*(x + 3) | ✅ | ✓ | — | 21 | 17 | 26 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + 2)*(x + 3) | ✅ | ✓ | — | 55 | 42 | 127 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + 2)*(x + 3) | ✅ | ✓ | — | 38 | 37 | 93 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + 2)*(x + 3) | ✅ | ✓ | — | 1554 | 1500 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + 2)*(x + 3) | ✅ | ✓ | — | 26 | 59 | 153 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | (x + a)^2 | ✅ | ✓ | — | 31 | 26 | 106 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + a)^2 | ✅ | ✓ | — | 9 | 15 | 35 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + a)^2 | ✅ | ✓ | — | 19 | 26 | 106 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + a)^2 | ✅ | ✓ | — | 14 | 25 | 95 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + a)^2 | ✅ | ✓ | — | 607 | 1214 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + a)^2 | ✅ | ✓ | — | 20 | 41 | 141 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |

## rational-simplification

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | (x*y)/(x*z) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| beam | (x*y)/(x*z) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| a-star | (x*y)/(x*z) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| random-mc | (x*y)/(x*z) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| mcts | (x*y)/(x*z) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| hybrid | (x*y)/(x*z) | ❌ | ✗ | — | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| best-first | (a/b)*(c/d) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| beam | (a/b)*(c/d) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| a-star | (a/b)*(c/d) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| random-mc | (a/b)*(c/d) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| mcts | (a/b)*(c/d) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| hybrid | (a/b)*(c/d) | ❌ | ✗ | — | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| best-first | a/(b/c) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| beam | a/(b/c) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| a-star | a/(b/c) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| random-mc | a/(b/c) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| mcts | a/(b/c) | ❌ | ✗ | — | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| hybrid | a/(b/c) | ❌ | ✗ | — | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |

## search-explosion

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 613 | 663 | 2232 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 13 | 20 | 55 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 458 | 664 | 2241 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 418 | 619 | 2059 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 1514 | 3000 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 429 | 683 | 2287 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |

## equations

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | x + 3 = 7 | ✅ | ✓ | — | 10 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | SYMBOLICALLY_VERIFIED | ✓ |

## inequalities

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | (0 - 2) * x < 4 | ✅ | ✓ | — | 4 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | SYMBOLICALLY_VERIFIED | ✓ |

## calculus

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | diff(x ^ 3, x) | ✅ | ✓ | — | 8 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | SYMBOLICALLY_VERIFIED | ✓ |

## linear-algebra

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | A * (B + C) | ✅ | ✓ | — | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | SYMBOLICALLY_VERIFIED | ✓ |

