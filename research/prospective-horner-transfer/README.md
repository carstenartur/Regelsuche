# Cold-start transfer on Horner chains

The primitive solver and the solver with learned rules and source-derived witness checking both solved **128 of 128** tasks by the preregistered deadline. Removing the learned providers from the latter reduced this to **126**. The primary hypothesis required strictly more completions than both controls, so it failed. The earlier learned solver also finished all 128 before the deadline.

The [protocol](https://github.com/carstenartur/Regelsuche/commit/9724a48b0b8c4c1d02a91f8bd81f9512ed0c8119) predates the [evaluator freeze](https://github.com/carstenartur/Regelsuche/commit/05dda9fb837d53f6d37e3925744b009c3b09672d). The evaluator used the freeze commit SHA to generate the full stream. Training used two distributive simplifications and three validation inputs. The 128 distinct test expressions belonged to one synthetic family of quadratic Horner chains. The generator was designed to present possible reuse of that algebra. The split check established distinct polynomial coefficient signatures across training, validation and test inputs. The search received each expression and a node-count goal.

| Verified completions at cumulative budget | Primitive | Earlier learned | Source-derived verification | Learned providers removed |
|---:|---:|---:|---:|---:|
| 16,384 | 20 | 4 | 4 | 3 |
| 32,768 | 41 | 24 | 24 | 20 |
| 65,536 | 80 | 67 | 68 | 56 |
| **131,072 (primary)** | **128** | **128** | **128** | **126** |
| 262,144 | 128 | 128 | 128 | 128 |

| Work to complete all 128 tasks | Primitive | Earlier learned | Source-derived verification | Learned providers removed |
|---|---:|---:|---:|---:|
| Acquisition | 0 | 9,130 | 9,130 | 9,130 |
| Model load and solver preparation | 0 | 3,675 | 3,698 | 3,698 |
| Search, including verification | 78,240 | 62,781 | 61,873 | 94,785 |
| Independent witness replay | 2,656 | 16,488 | 15,580 | 2,656 |
| Exact polynomial audit | 21,671 | 21,671 | 21,671 | 21,671 |
| **Total** | **102,567** | **113,745** | **111,952** | **131,940** |

The two learned solvers each used 192 learned steps. Reconstructing the bindings directly from the source saved 908 units in search and 908 in final replay, with 23 extra preparation units. The resulting saving of 1,793 units over the earlier learned solver preserved the full set of solutions. For an already prepared model, the full search, replay and audit cost was 99,124, compared with 102,567 for primitive search. Acquisition, loading and preparation added 12,828 to the learned solver, leaving a cold-start deficit of 9,385 units on this stream. These are metered logical operations. The two elapsed-time records in the results directory describe the individual runs and are sensitive to execution order.

## Reproduction

Use JDK 25, Maven and the [frozen source tree](https://github.com/carstenartur/Regelsuche/tree/05dda9fb837d53f6d37e3925744b009c3b09672d). From its root run the following commands in Bash. The earlier training harness recreates the receipt and model. Each evaluation starts a fresh test JVM and writes a new directory.

```bash
study=$(mktemp -d)
frozen=05dda9fb837d53f6d37e3925744b009c3b09672d
common=(-B -pl regelsuche-learning -am -Dsurefire.failIfNoSpecifiedTests=false)
mvn "${common[@]}" -Dtest=DistributiveCoverageStudyTest -Ddistributive.phase=train \
    -Ddistributive.directory="$study/train" test
for file in freeze.json snapshot.json; do
    cmp "$study/train/$file" "docs/research/prospective-distributive-coverage/$file"
done
for rotation in 0 1; do
    mvn "${common[@]}" -Dtest=HornerTransferStudyTest -Dhorner.phase=final \
        -Dhorner.research="$PWD/docs/research" -Dhorner.freeze="$frozen" \
        -Dhorner.rotation="$rotation" -Dhorner.output="$study/run-$rotation" test
done
for file in BASE.jsonl PRIOR.jsonl REGEN.jsonl DROP.jsonl summary.json paths.json; do
    cmp "$study/run-0/$file" "$study/run-1/$file"
done
```

Both runs produced identical [per-task ledgers, witness paths and cumulative counts](results). The four ledgers contain 512 rows and reference 320 distinct witness fingerprints. The witness dictionary is stored as losslessly compressed JSON. Two independent checks of each reported path remain active in the solver. An additional exact polynomial comparison checked the result of every task in every arm. The rows expose search, its verification component, final replay, audit, and the cumulative clock so readers can recompute every checkpoint.
