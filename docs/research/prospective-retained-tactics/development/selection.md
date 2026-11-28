# Selecting a matcher on public development results

Before opening the two prospective families, compare two implementations on the three earlier streams. Both use the same proof-price-bounded acquisition gates, identical input order and the same per-stream deadlines. One permits all retained rules after admission. The alternative builds a smaller active matcher from rules actually observed in paid calibration proofs and pays a second validation pass. The complete checked rule library stays available in both configurations. This is development selection.

Use the mean fraction of tasks solved by the cold deadline on the three streams, giving each stream equal weight. For the registered public stream sizes the integer score is MIXED completions plus twice interpolation completions plus twice squared-radius completions. Break ties using fewer total logical units to run all three streams. If the thin matcher does not improve either measure or fails the ordinary regression checks, use the full matcher in FINAL. The two new families will each be evaluated only with the publicly frozen choice. Report both development results and the ablation of the selected candidate. The successful measurement uses the untouched evaluation seed and individual margins in the [original protocol](../protocol.md).

| Public stream | Full matcher by deadline | Thin matcher by deadline |
|---|---:|---:|
| Mixed Horner/dot product | 447 | 439 |
| Constant interpolation | 384 | 384 |
| Sums of squared radii | 352 | 352 |

The selection scores were 1,919 and 1,911. The full matcher was selected for FINAL before any final instance was generated. The thin matcher selected nine of the 26 proved schemata. On the mixed stream it finished 818 tasks when setup was paid separately, versus 801 for the full matcher. Its additional calibration reduced cold-start coverage. The four calibrated controls and candidate used the same fitting mode in each run where applicable. Both public comparisons reproduced their mathematical ledgers in two JVMs. [Full matcher run](https://github.com/carstenartur/Regelsuche/actions/runs/46154325414) · [Thin matcher run](https://github.com/carstenartur/Regelsuche/actions/runs/46154815834)
