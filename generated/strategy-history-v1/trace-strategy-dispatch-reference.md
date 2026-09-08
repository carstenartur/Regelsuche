# Conditional continuation: fixed development comparison

All 288 new polynomial inputs and four profiles are retained. These are public development compositions of known building blocks, not a sealed FINAL TEST or independent mathematical families.

The learned dispatcher reuses an actual primitive candidate and executes only a selected continuation. All one-successor profiles use the same ranking and budgets. FLAT_EXHAUSTIVE is retained as an additional stronger search control.

| Profile | Search mechanics + exact audit calls | Score regressions vs FLAT_GREEDY |
|---|---:|---:|
| FLAT_EXHAUSTIVE | 11360 | 0 |
| FLAT_GREEDY | 8960 | 0 |
| LEARNED_DISPATCH | 8832 | 0 |
| UNGATED_CONTINUATIONS | 9248 | 0 |

Each family has 32 inputs. Work includes the cases where a learned hint fails.

| Family | Exhaustive | Greedy | Learned dispatch | Ungated |
|---|---:|---:|---:|---:|
| compound | 1408 | 1248 | 1088 | 1088 |
| factor | 768 | 608 | 704 | 800 |
| multiple-sites | 3200 | 1760 | 1728 | 1856 |
| noncancelling | 480 | 512 | 576 | 704 |
| offset | 1408 | 1248 | 1088 | 1088 |
| product | 1408 | 1248 | 1088 | 1088 |
| same-hint-mismatch | 1088 | 896 | 1280 | 1344 |
| scale | 1408 | 1248 | 1088 | 1088 |
| unchanged | 192 | 192 | 192 | 192 |

Formation work: 232; dispatch training including rejected trials and context collection: 850; total counted learning work: 1082.

Development criterion passed: true. Learning plus application cheaper on this batch: false.

These units are not CPU time. Complete parsing, identity projection, compilation and BigInteger work remain outside this ledger. All output paths are exact-audited. A heuristic route can lose quality on unseen inputs; this batch cannot prove general optimality.

## Frozen routes

- Available-gene mask 132: difference-product → square-product → cancel-addend
