# Regelsuche – Benchmark-Qualitätsdashboard

Automatisch generiert von `./gradlew benchmarkReport`. Jede Zeile zeigt neben den klassischen Suchmetriken auch Qualitätsmetriken: ob das erwartete Ergebnis getroffen wurde, wie viele Zustände geprunet wurden, e-Graph-Größe, Matcher-Scan-/Cache-Metriken, ob eine gelernte Makroregel beteiligt war und ob das Export-Bundle für diese Zeile gültig ist.

**Sat-Sparung:** nicht gemessen. Ohne vergleichbaren Referenzlauf lassen sich aus Union- und Regelanwendungszahlen keine Einsparungen ableiten.

**Ampel:** ✅ OK · ⚠️ WARN · ❌ FAIL

## known-identities

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | x + 0 | ✅ | ✓ | ✓ | 297 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x + 0 | ✅ | ✓ | ✓ | 16 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x + 0 | ✅ | ✓ | ✓ | 8 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x + 0 | ✅ | ✓ | ✓ | 11 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x + 0 | ✅ | ✓ | ✓ | 17 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x + 0 | ✅ | ✓ | ✓ | 20 | 8 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x * 1 | ✅ | ✓ | ✓ | 7 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x * 1 | ✅ | ✓ | ✓ | 15 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x * 1 | ✅ | ✓ | ✓ | 5 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x * 1 | ✅ | ✓ | ✓ | 16 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x * 1 | ✅ | ✓ | ✓ | 26 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x * 1 | ✅ | ✓ | ✓ | 17 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x * 0 | ✅ | ✓ | ✓ | 7 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x * 0 | ✅ | ✓ | ✓ | 4 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x * 0 | ✅ | ✓ | ✓ | 6 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x * 0 | ✅ | ✓ | ✓ | 7 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x * 0 | ✅ | ✓ | ✓ | 32 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x * 0 | ✅ | ✓ | ✓ | 14 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | a + a | ❌ | ✗ | — | 43 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| beam | a + a | ❌ | ✗ | — | 9 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| a-star | a + a | ❌ | ✗ | — | 19 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| random-mc | a + a | ❌ | ✗ | — | 10 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| mcts | a + a | ❌ | ✗ | — | 29 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |
| hybrid | a + a | ❌ | ✗ | — | 26 | 6 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | OBSERVED | ✓ |

## polynomial-simplification

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| best-first | (x + 1)*(x + 1) | ✅ | ✓ | — | 417 | 22 | 82 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + 1)*(x + 1) | ✅ | ✓ | — | 121 | 21 | 46 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + 1)*(x + 1) | ✅ | ✓ | — | 182 | 21 | 76 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + 1)*(x + 1) | ✅ | ✓ | — | 187 | 25 | 64 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + 1)*(x + 1) | ✅ | ✓ | — | 3852 | 1500 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + 1)*(x + 1) | ✅ | ✓ | — | 56 | 43 | 128 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | x*x + x*x | ✅ | ✓ | — | 9 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | x*x + x*x | ✅ | ✓ | — | 5 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | x*x + x*x | ✅ | ✓ | — | 20 | 9 | 21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | x*x + x*x | ✅ | ✓ | — | 11 | 9 | 25 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | x*x + x*x | ✅ | ✓ | — | 194 | 170 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | x*x + x*x | ✅ | ✓ | — | 11 | 18 | 42 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | (x + 2)*(x + 3) | ✅ | ✓ | — | 89 | 42 | 127 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + 2)*(x + 3) | ✅ | ✓ | — | 23 | 17 | 26 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + 2)*(x + 3) | ✅ | ✓ | — | 74 | 42 | 127 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + 2)*(x + 3) | ✅ | ✓ | — | 41 | 37 | 93 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + 2)*(x + 3) | ✅ | ✓ | — | 2325 | 1500 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + 2)*(x + 3) | ✅ | ✓ | — | 66 | 59 | 153 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| best-first | (x + a)^2 | ✅ | ✓ | — | 49 | 26 | 106 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + a)^2 | ✅ | ✓ | — | 17 | 15 | 35 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + a)^2 | ✅ | ✓ | — | 43 | 26 | 106 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + a)^2 | ✅ | ✓ | — | 32 | 25 | 95 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + a)^2 | ✅ | ✓ | — | 785 | 1211 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + a)^2 | ✅ | ✓ | — | 29 | 41 | 141 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |

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
| best-first | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 804 | 663 | 2232 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| beam | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 22 | 20 | 55 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| a-star | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 702 | 664 | 2241 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| random-mc | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 340 | 619 | 2059 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| mcts | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 1493 | 3000 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |
| hybrid | (x + a)*(x + b)*(x + c) | ✅ | ✓ | — | 421 | 683 | 2287 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | VALIDATED_BY_EXAMPLES | ✓ |

## equations

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | x + 3 = 7 | ✅ | ✓ | — | 9 | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | SYMBOLICALLY_VERIFIED | ✓ |

## inequalities

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | (0 - 2) * x < 4 | ✅ | ✓ | — | 3 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | SYMBOLICALLY_VERIFIED | ✓ |

## calculus

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | diff(x ^ 3, x) | ✅ | ✓ | — | 7 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | SYMBOLICALLY_VERIFIED | ✓ |

## linear-algebra

| Strategie | Ausdruck | Status | Gefunden | Erw. getroffen | Zeit (ms) | Besucht | Geprunt | e-Klassen | e-Knoten | Klassen-Scans | Knoten-Scans | Kandidaten-Skips | Matches | Cache-Hits | Cache-Misses | Sat-Iterationen | Regeln gefeuert | Sat-Sparung | Lernregel | Proof | Export |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| math-domain | A * (B + C) | ✅ | ✓ | — | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | nicht gemessen | – | SYMBOLICALLY_VERIFIED | ✓ |

