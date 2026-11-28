# Development comparison on the published Horner and dot-product stream

The second checker completed 720 tasks within 524,288 units on the existing seven-schema solver, up from 708. Adding the learned selection policy reduced that count to 510. All four variants eventually solved all 1,024 tasks. This public stream was available during development. The [prospective protocol](../protocol.md) defines the separate family-transfer test.

## Selection and checking crossed independently

Each arm loads the same model from the earlier distributive study, uses the same task order, and pays for an independent replay and an exact polynomial audit. The policy arms also fit the rule-selection policy using the public calibration cases. The original arm's complete per-task ledger has the same SHA-256 as the [archived FAST ledger](../../prospective-interval-transfer/development/FAST.jsonl.gz) after decompression.

| Selection | Checker | Setup | Search including online checking | Online verification within search | Separate replay | Polynomial audit | Full cost | Solved by deadline |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| Original | Original | 12,850 | 438,814 | 127,208 | 127,208 | 173,258 | 752,130 | 708 |
| Original | Shared context | 12,868 | 432,164 | 120,558 | 120,558 | 173,258 | 738,848 | 720 |
| Learned policy | Original | 150,498 | 461,914 | 127,208 | 127,208 | 173,258 | 912,878 | 502 |
| Learned policy | Shared context | 150,516 | 455,264 | 120,558 | 120,558 | 173,258 | 899,596 | 510 |

The policy fit costs 132,127 units. The policy also adds 5,521 units of preparation and 23,100 units of search over all tasks at either checker setting. All arms use 1,536 learned witness steps. Charging all four the same initial 150,516 units gives 517, 527, 502 and 510 completions in table order. The shared-context checker saves 6,650 units online and 6,650 in the independent replay at a one-time cost of 18 units. The audit is identical in all four arms.

## Withholding the learned proposals

A retrospective control paid the same 12,868 units of setup for the shared-context checker and original selection in both arms. The control removes the learned providers after the usual per-query routing. With proposals enabled, the solver completed 720 tasks at the deadline and used 738,848 units for the full stream. With proposals withheld it completed 698 tasks and used 760,796 units. The 1,536 learned steps contributed a net 22 extra completions and a saving of 21,948 units (2.88%) against this control. All tasks eventually passed the polynomial audit in both arms. [Ablation receipts and ledger digests](checker-ablation.json)

The prior checker with proposals withheld completed 699 tasks for 760,778 units. The new control checked every per-task field against the archived 1,024-row ledger. All fields matched after accounting for 18 additional units of initial preparation. The 699th task finished at 524,296 units, eight after the deadline. Its previous completion time was 524,278.

## Independent replay on the same learned steps

The isolated comparison replays every learned step returned by the original arm in its original state and context. Both checkers must accept and return the same receipts and reason as the original verification. Each check uses a fresh expression-codec cache. The isolated totals count learned steps. The separate replay in the main table also includes 256 primitive checks costing 768 units in total.

| Occurrence depth | Steps | Original checker | Shared-context checker | Change |
|---:|---:|---:|---:|---:|
| 1 | 254 | 14,986 | 16,764 | +1,778 |
| 2 | 252 | 16,380 | 17,388 | +1,008 |
| 3 | 776 | 68,904 | 63,028 | −5,876 |
| 4 | 254 | 26,170 | 22,610 | −3,560 |
| All | 1,536 | 126,440 | 119,790 | −6,650 |

Depth is the length of the checked occurrence path. Sharing work across the preserved expression context saved work at depths three and four in this stream. The costs at shorter paths rose.

## Elapsed time

The medians of the four whole-stream measurements were 1.78, 1.89, 2.15 and 2.42 seconds in table order. The checker-only arm was faster than the original in three of the four paired runs. Across twelve isolated blocks of 1,536 learned checks, the median changed from 462 to 433 milliseconds. The original checker had the lower median in one of the four JVMs. These order-sensitive development timings do not establish a general runtime advantage.

## Reproduction

From the repository root, run with JDK 25, Maven and Python 3. The evaluator writes into a new directory for each rotation. Each Maven invocation runs in a fresh JVM. The four balanced orders give each arm one turn at each position. Within each JVM the first 128 public tasks warm up each arm, then a fresh solver runs the entire stream. The timings include model loading, policy fitting when applicable, solving, final replay, the polynomial audit and constructing the ledgers. They exclude hashing the finished ledger, writing the report files and reading the historical acquisition data. The logical setup accounts for that prior acquisition. The isolated timing repeats all learned checks three times after one warm-up pass with alternating checker order across JVMs. Shared hardware, JIT and GC can affect these observations.

```bash
runs=$(mktemp -d)
for r in 0 1 2 3; do
  mvn -B -pl regelsuche-learning -am -Dtest=MixedTacticDevelopmentTest \
    -Dsurefire.failIfNoSpecifiedTests=false -Dmixed.research="$PWD/docs/research" \
    -Dmixed.rotation="$r" -Dmixed.output="$runs/$r" test || exit 1
done
python3 docs/research/prospective-tactic-transfer/development/summarize.py "$runs" > "$runs/combined.json"
```

The separate ablation uses the same `runs` directory and checks the active-arm ledger against the checked-in development result.

```bash
mvn -B -pl regelsuche-learning -am -Dtest=MixedCheckerAblationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmixed.research="$PWD/docs/research" \
  -Dmixed.ablation.output="$runs/checker-ablation.json" test
```

The [results](results.json) retain the four ledger digests, the full cost decomposition, the checks by depth and every elapsed observation. The [collector](summarize.py) checks that the mathematical summaries, including the SHA-256 over each complete per-task ledger, agree in every rotation.
