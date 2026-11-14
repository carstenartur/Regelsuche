# Measured effect of shared-binding dispatch

Primitive search completed the 12 growing-inventory programs in **356 logical
work points**. The indexed learned arm with primitive fallback used **940**,
compared with **2,257** for exhaustive learned matching with the same fallback.
The index reduced work within the learned arm by 1,317 points (58.4%).
Primitive search reached at least as many of the six positive optima at every
preregistered stream budget. No acceleration over primitive search was observed.

| Stream budget | Primitive optima reached | Exhaustive learned | Indexed learned |
|---:|---:|---:|---:|
| 0 | 0 | 0 | 0 |
| 1 | 0 | 0 | 0 |
| 2 | 0 | 0 | 0 |
| 4 | 0 | 0 | 0 |
| 8 | 0 | 0 | 0 |
| 16 | 1 | 0 | 0 |
| 32 | 2 | 0 | 0 |
| 64 | 2 | 0 | 0 |
| 128 | 4 | 0 | 0 |
| 256 | 5 | 2 | 2 |
| 512 | 6 | 3 | 4 |
| 1024 | 6 | 4 | 6 |
| 2048 | 6 | 6 | 6 |
| 4096 | 6 | 6 | 6 |

## Where the work went

| Logical charge on the full prospective stream | Primitive | Exhaustive learned | Indexed learned |
|---|---:|---:|---:|
| TRAIN acquisition and TEST model decode | 0 | 156 | 156 |
| Dispatch plan compilation | 0 | 0 | 3 |
| Key extractions / bucket writes / lookups / positions examined | 0 | 0 | 128 / 64 / 64 / 88 |
| Learned tuple attempts / matcher steps / AC branches | 0 | 328 / 328 / 1,080 | 24 / 24 / 24 |
| Primitive attempts / steps | 64 / 64 | 64 / 64 | 64 / 64 |
| Candidates generated | 104 | 113 | 113 |
| Independent replay requests | 64 | 64 | 64 |
| DAG scores, including source scores | 60 | 60 | 60 |
| **Total** | **356** | **2,257** | **940** |

The 64 replays yielded 48 approvals per arm. Candidates discarded after
generation as duplicates numbered 40, 49 and 49, respectively. At 512 points
the index had reached four positives, exhaustive learned matching three, and
primitives six. Across 84 positive row/budget pairs, indexed warm search won
zero, tied 77 and lost seven versus primitives. Cold counts were zero, 63 and
21. Exhaustive warm counts were zero, 69 and 15. Exhaustive cold counts were
zero, 59 and 25. All 12 rows reached the independently enumerated optimum at
exhaustion; every indexed application agreed in order, bindings, target and
recognition metadata with exhaustive AC. The three no-reuse controls had zero
learned applications and no DAG savings. In all three missing-premise controls,
a syntactic proposal existed and replay rejected every candidate.

| Positive case | Source DAG | Optimum DAG | Primitive first hit | Exhaustive warm first hit | Indexed warm first hit |
|---|---:|---:|---:|---:|---:|
| J2-forward | 103 | 86 | 9 | 28 | 25 |
| J2-reverse | 103 | 86 | 14 | 32 | 29 |
| J4-forward | 195 | 178 | 9 | 104 | 35 |
| J4-reverse | 195 | 178 | 26 | 108 | 39 |
| J7-forward | 333 | 316 | 9 | 308 | 50 |
| J7-reverse | 333 | 316 | 44 | 312 | 54 |

## Historical deficit and homogeneous-base stress control

The original v3 result remains **716 versus 232**. Of its 484 extra points,
408 were learned matching (112 assignments, 112 steps and 184 AC branches),
nine were generated duplicates, and 67 were acquisition. Replays and scores
were identical in both arms. Inspecting the TRAIN implementation revealed that
v3 used an eight-root shared-matcher proxy with eight steps and counted the
tree comparator's calls but omitted 16 invocations in the DAG comparator.

The v4 acquisition ledger charges all 40 score calls and all 88 exact
rule/subtree attempts (two primitive rules at 11 syntax occurrences on four
TRAIN and SHUFFLED inputs). The transport calls the exact matcher directly.
The remaining charges are four generator calls, eight proposals, ten replays,
two examples, one generalization, one serialization and one validation decode.
This totals **155**. Loading the persisted model for TEST costs one further
decode and indexed dispatch costs three once-only plan inspections.

On the retrospective v3 inputs all outputs had the same base. Both matchers
delegated all 112 assignments and took 184 AC branches. The index added
312 per-row dispatch operations across the panel and three setup operations.
Total cold stream work was **232 primitive, 805 exhaustive learned and
1,120 indexed learned** under the v4 accounting. This stress control shows
the cost of indexing a nondiscriminating key. No switch was introduced after
viewing these outcomes. Results describe deterministic logical costs of these
one-step searches on public inputs. These weights do not establish wall time
or blind generalization.

## Evidence and reproduction

The [public preregistration](https://github.com/carstenartur/Regelsuche/commit/fd862da259ef965d8bd02c714620b95a2e768f95)
preceded the official runs. The first [hosted JDK 25 reproduction](https://github.com/carstenartur/Regelsuche/actions/runs/43603361630/job/132472661730)
at implementation commit `61ef318a82080605ec3273eac29063d267d0769d`
passed 17 matcher and 38 application tests. One TRAIN JVM regenerated the
v1 model. Two independent TEST JVMs emitted the same 182,466 raw report bytes
(SHA-256 `34d5309dba400b3a183a2aa5c31432dd7eebaddf1e5856b489c6c2471de761d3`).
The [acquisition receipt](acquisition.json) has SHA-256
`eb9c5355615d60f3df08ccb2337c77aea8d3055cce8017dfbfb50134783f13eb`.
The [compressed report](result.json.gz) includes both panels, all three arms
and every finite checkpoint. The retained-byte test regenerates both outputs.

```sh
mvn -pl app -am test \
  -Dtest=TypedOutputPatternTest,TypedOutputPatternLimitTest,TypedModPowAcTransferIntegrationTest,TypedModPowTransferIntegrationTest,ModPowFrozenTransferTest,ModPowAcTransferTest,ModPowBudgetStudyTest,ModPowDispatchStudyTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
