# Track C rational operator grammar

The three arms reached the same six of twelve public rational-rewrite tasks in each of four campaigns. All reached the four TRAIN tasks and both symbolic difference-of-squares TEST tasks. None reached the four VALIDATION tasks or the two nested-reciprocal TEST tasks. Four campaigns reuse the same twelve tasks. The 144 evaluations represent twelve distinct tasks.

| Arm | Frozen program in each campaign | Proposals | Rejected | TRAIN reached | VALIDATION reached | TEST reached | Formation states / candidate checks |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Production operator | Both forms | 4 | 0 | 16/16 | 0/16 | 8/16 | 16 / 16 |
| Ordered enumeration | Cancellation only | 12 | 4 | 16/16 | 0/16 | 8/16 | 36 / 32 |
| Seeded random | Both forms | 12 | 6 | 16/16 | 0/16 | 8/16 | 30 / 24 |

Protocol commit `a82b446ccd79d3d4dd84783eeb310be8a78c5c99` fixes the grammar, order, randomness, projection, budgets, eligibility and scoring before execution. The experiment uses the original #383 public corpus and primitive profile. Each arm sees only four TRAIN expressions and their declared assumptions. The adapter assigns opaque sequential source references. It validates strictly smaller canonical ASTs and exact field equality under supplied premises. Each selected program has four supporting witnesses. The runner freezes all twelve selections before constructing evaluation tasks.

The grammar contains the existing root-level common-factor cancellation operator, the existing equal-denominator addition/subtraction operator, and their union. Enumeration executes every member. Random selection draws with replacement and re-executes two repeated proposals. The first member supported by all inputs is frozen. The four inputs are all cancellation examples, so the equal-denominator-only proposals fail on the first example. Public component tests exercise guarded addition and subtraction as well as unsupported inputs. Evaluation retains the original square-difference factorization and divide-by-one primitives and requires the exact target syntax.

Across each twelve-task panel, every arm explored 26 states and validated 14 candidates. All 72 successful evaluations have independently checked paths and all 72 unsuccessful evaluations have independent exhausted-frontier witnesses. Each task allows 245 explored states and 45 candidate validations at depth five. Formation has one cumulative 60-state, 60-validation allowance per campaign and arm. Rejected and repeated proposals consume actual work. Formal proofs, wall time and external novelty are outside these measurements. These three related programs and this previously public panel support a bounded comparison only.

## Reproduction

From the root, Java 25 and Maven 3.9.16 replay the Java measurement and compare the entire artifact byte for byte. The Python verifier uses the repository's existing independent exact rational arithmetic and Python dependency `jsonschema`. It regenerates every schedule, selection, guard, canonical witness cost, search path, terminal outcome and work count.

```sh
mvn -pl regelsuche-release -am -Dtest=TrackCRationalGrammarComparisonTest,RationalAssumptionRewriteAdapterTest,RationalNormalizationHypothesisOperatorAssumptionTest -Dsurefire.failIfNoSpecifiedTests=false test
python3 scripts/verify-track-c-rational-grammar.py
```

The prospective plan and gzip-compressed raw evidence are in `research/benchmarks/candidate-independent/track-c-rational-grammar/`. Two independent JVM invocations produced identical 252,441-byte JSON, SHA-256 `98c0ea8940db1950a14ec81cef83902fa0df663ef481c20f2b8e75d1639e6dca`. The historical combined-operator adapter also regenerated its original byte-identical 48-row report when executed before and after enabling program selection.
