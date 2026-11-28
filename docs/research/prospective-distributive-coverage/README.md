# A checked algebra shortcut reduced work on two families and lost coverage overall

The cumulative learner induced a four-primitive-step shortcut from two distributive-cancellation traces. On four held-out parallel expressions it reduced the work per solution from **636 to 543**. On four nested expressions it reduced work from **584 to 552**. Both variants use the same learned schema twice. All eight expressions also have reachable primitive solutions at the same depth limit.

**The preregistered coverage criterion failed.** Across 24 inputs and 16 common budgets the primitive solver solved 220 of 384 case–budget pairs, the learned solver 200, and the same solver with its learned providers removed 208. Compared with the primitive solver, the learned solver gained zero pairs and lost 20. Compared with provider removal it gained four and lost 12. All three solved the same 20 inputs at the 200,000-unit reference budget. The four irreducible controls remained unsolved.

The [public study protocol](https://github.com/carstenartur/Regelsuche/commit/54effcd56a390119d2211707c3ea1f23e0954e44) predates training. The [public model and evaluator freeze](https://github.com/carstenartur/Regelsuche/commit/f02c90f78d78274583db1c83d1de670605b94ba6) predates the two final runs. This developer-specified panel contains four coefficient variants per family. Its 384 pairs reuse the same 24 inputs. There was no polynomial alpha-identity overlap between TRAIN, GATE and FINAL. Within FINAL, each neutral expression is equivalent to the irreducible input with the same coefficient. Those use different node goals.

## Coverage at equal query budgets

BASE uses the ordinary primitive solver. LEARNED uses the frozen checked model with normal occurrence selection and routing. DROP uses the same model, selection, routing and search settings with the learned providers removed. Each cell counts solved inputs out of 24. Every solution passed independent path replay, exact polynomial equivalence and the complete work-budget check.

| Work budget | BASE | LEARNED | DROP |
|---|---:|---:|---:|
| 64 | 0 | 0 | 0 |
| 96 | 4 | 0 | 0 |
| 128 | 4 | 4 | 4 |
| 192 | 4 | 4 | 4 |
| 256 | 4 | 4 | 4 |
| 384 | 12 | 4 | 8 |
| 512 | 12 | 4 | 12 |
| 768 | 20 | 20 | 16 |
| 1,024 | 20 | 20 | 20 |
| 1,536 | 20 | 20 | 20 |
| 2,048 | 20 | 20 | 20 |
| 3,072 | 20 | 20 | 20 |
| 4,096 | 20 | 20 | 20 |
| 6,144 | 20 | 20 | 20 |
| 8,192 | 20 | 20 | 20 |
| 12,288 | 20 | 20 | 20 |
| **Sum across sweep** | **220** | **200** | **208** |
| 200,000 reference | 20 | 20 | 20 |

Across the 408 rows per arm including reference, 100 BASE, 120 LEARNED and 112 DROP attempts ended above budget. Their checked prefixes retain their actual expense. All count as failures. The [row table](results/rows.csv) records the output and budget outcome separately.

## Where learning changed the cost

Each count sums the four coefficient variants across 16 budgets, out of 64. The work columns give total query plus independent witness replay units at the reference budget. All four coefficients had the same expense within each family.

| Family | BASE solved | LEARNED solved | DROP solved | BASE work/input | LEARNED work/input | DROP work/input |
|---|---:|---:|---:|---:|---:|---:|
| Parallel difference | 36 | 36 | 32 | 636 | 543 | 790 |
| Nested product | 36 | 36 | 36 | 584 | 552 | 740 |
| Normalization before reuse | 44 | 36 | 40 | 311 | 548 | 410 |
| Mirrored distributivity | 44 | 36 | 44 | 266 | 587 | 367 |
| Independent neutral operations | 60 | 56 | 56 | 88 | 109 | 109 |
| Irreducible | 0 | 0 | 0 | 32 | 41 | 41 |

At 768 units the learned schema brought four parallel cases within budget relative to DROP. BASE already solved all four. On the normalization cases LEARNED interleaved two shorter learned rewrites with a primitive add-zero. Verification and independent replay consumed 103 units each, compared with 15 each for the primitive witness. In the mirrored family all successful witnesses were primitive. Its 587 versus 367 units show the extra cost of enabling learned providers even though none of their moves was in the chosen witness. The 367 versus 266 additional comparison measures the common selection and routing overhead. Variable and coefficient names alone do not account for this behavior.

Across all 24 reference queries BASE spent 7,668 units, LEARNED 9,520 and DROP 9,828. Seven promoted schemas cost 9,130 units to acquire. Loading and preparing the frozen model added 3,675 units to each model arm. Counting those costs once for the panel gives BASE **7,668**, LEARNED **22,325**, and DROP **22,633**. Repeating this particular mix cannot amortize LEARNED against BASE while its query total remains higher. The independent polynomial audit is a study check for every row, reported separately from the solver work. End-to-end wall and thread CPU timings include that audit, file output and setup. They vary by execution order and do not support a wall-time speed claim.

## Reproduction and evidence

Run with JDK 25 and the repository's Maven build from its root. Each Maven invocation starts a fresh test JVM. The frozen inputs and row checker are in this directory. The optional TRAIN run below writes a new directory and can be compared with the published files.

```bash
study="$PWD/docs/research/prospective-distributive-coverage"
run_dir=$(mktemp -d)
common=(-B -pl regelsuche-learning -am -Dtest=DistributiveCoverageStudyTest -Dsurefire.failIfNoSpecifiedTests=false)
mvn "${common[@]}" -Ddistributive.phase=train -Ddistributive.directory="$run_dir/train" test
cmp "$run_dir/train/freeze.json" "$study/freeze.json"
cmp "$run_dir/train/snapshot.json" "$study/snapshot.json"
freeze=sha256:6863b403ed13292dd4aefde8603ba83b1f7ff221ee5dab091a7f019108947930
for rotation in 0 1; do
  mvn "${common[@]}" -Ddistributive.phase=final -Ddistributive.directory="$study" \
    -Ddistributive.digest="$freeze" -Ddistributive.rotation="$rotation" \
    -Ddistributive.output="$run_dir/run-$rotation" test || exit 1
done
python3 "$study/compare.py" "$run_dir/run-0" "$run_dir/run-1" "$run_dir/compared"
cmp "$run_dir/compared/rows.csv" "$study/results/rows.csv"
cmp "$run_dir/compared/paths.jsonl" "$study/results/paths.jsonl"
cmp "$run_dir/compared/outputs.json" "$study/results/outputs.json"
```

The two JVMs produced identical outcomes, outputs, costs and witness hashes on all 1,224 rows, including unsuccessful prefixes. The 121 distinct witnesses are dictionary-coded by the zero-based `path` column in [paths.jsonl](results/paths.jsonl). Every step includes the selected schema if present, before/after expressions and verification work. The zero-based `output` column indexes [outputs.json](results/outputs.json). The [summary](results/summary.json) records timing, both raw run hashes, alpha overlaps and the primary decision. External polynomial audit work appears in the CSV's `audit` column. The published [training receipt](freeze.json) separates acquisition, training traces and validation from the [canonical model](snapshot.json).

## Subsequent development

An exploratory [instrumented dispatch comparison](dispatch-followup/README.md) on the known panel tested a deeper occurrence filter, including preparation, replay, and all original control rows.
