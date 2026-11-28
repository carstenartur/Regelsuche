# Learned rewrites on composite rectangle quadrature

The primitive solver completed all **1,024** previously unseen quadrature tasks within the registered budget of **262,144** logical units. The previous learned solver finished 615 and the optimized learned solver finished 678. Removing the learned providers from the optimized solver raised that count to 959. Both registered transfer tests failed. With more budget all five arms completed every task. They returned the same checked proof path for each input, using only ordinary left or right factoring.

| Solver | Solved by deadline | Setup | Search, including verification | Independent replay | Exact polynomial audit | Full cost |
|---|---:|---:|---:|---:|---:|---:|
| Primitive (BASE) | **1,024** | 0 | 129,536 | 4,608 | 105,472 | **239,616** |
| Previous learned solver (REGEN) | 615 | 12,828 | 304,640 | 4,608 | 105,472 | 427,548 |
| REGEN without learned providers (DROP) | 836 | 12,828 | 195,072 | 4,608 | 105,472 | 317,980 |
| Optimized learned solver (FAST) | 678 | 12,850 | 265,728 | 4,608 | 105,472 | 388,658 |
| FAST without learned providers (FAST_DROP) | 959 | 12,850 | 156,160 | 4,608 | 105,472 | 279,090 |

Here the extra cost of keeping the learned providers available was **109,568** units in either pair. Sharing node counts saved 38,890 units in both pairs. Even with its learned providers removed, the prepared solver paid for its model, routing and different search machinery. BASE is an ordinary primitive search with its own scheduler. [Primary outcomes and costs by stratum](results/verified.json)

| Cells and height position | BASE | REGEN | DROP | FAST | FAST_DROP |
|---|---:|---:|---:|---:|---:|
| 2, left | 256 | 154 | 209 | 169 | 240 |
| 2, right | 256 | 154 | 209 | 169 | 240 |
| 3, left | 256 | 154 | 209 | 170 | 239 |
| 3, right | 256 | 153 | 209 | 170 | 240 |

The expression is the sum of rectangles of equal height over two or three consecutive cells. Multiplying their combined widths by the height once meets the node bound. All four variants were specified in the [published protocol](https://github.com/carstenartur/Regelsuche/commit/dc01b4f5df9aa0a2c2e93ac5473d3a1108246339). The protocol introduced no local training fragment into the inputs. The [frozen implementation](https://github.com/carstenartur/Regelsuche/commit/15f2fb41e876df5bb13138a2b9ed87340fa85146) supplied the only seed. Exact polynomial coefficient multisets distinguish the final cases from each other and the training, validation and historical comparison cases. The 1,024 inputs are related instances of one synthetic family. The stratum counts use one shared cumulative deadline.

## What improved on the public development stream

The optional Java preparation mode `DOMAIN_RECEIPTS_WITH_ROUTING` shares the checked node count of an immutable expression with the node-count goal inside one query. An identity lookup costs one unit. A miss also pays for the original traversal. The two proof checks still run independently. The original inventory and previously learned seven schemata were frozen across both studies. The learning arms pay their historical acquisition cost of 9,130 units and the cost of loading and preparing the model.

| Solver | Solved by the original 524,288-unit deadline | Full cost for all 1,024 | Learned witness steps |
|---|---:|---:|---:|
| BASE | 642 | 834,410 | 0 |
| REGEN | 646 | 823,252 | 1,536 |
| DROP | 541 | 979,844 | 0 |
| FAST | **708** | **752,130** | 1,536 |
| FAST_DROP | 699 | 760,778 | 0 |

On the [published Horner and dot-product inputs](../prospective-amortization/README.md), the new solver used 9.86% less work than primitive search and 8.64% less than the previous learned solver. Against the same optimized solver with learned providers removed, the rules contributed **nine additional completions by the deadline** and saved **8,648 units (1.14%)** over the full stream. All outputs and proof-path hashes matched between REGEN and FAST on every input. The three unchanged arms reproduced the original row ledgers byte for byte. These data were public during development. [Additional arm ledgers, summaries and timing observations](development)

Both studies used fresh JVMs with opposite arm order. For each study all seven non-timing output files agreed byte for byte. The two runs of each study reversed the relative elapsed ordering of FAST and REGEN. The timings cover the evaluator with a frozen model available. The logical ledger includes historical acquisition. These runs support no stable wall-clock speedup. [Quadrature timings, forward](results/elapsed-0.json) · [reverse](results/elapsed-1.json)

## Reproduction

Use JDK 25, Maven and Python 3.11 or later. The reporting revision includes the frozen solver and the development-ledger checker. Run from the repository root. Use a fresh output directory each time.

```bash
runs=$(mktemp -d)
seed=15f2fb41e876df5bb13138a2b9ed87340fa85146
for phase in final development; do
    for rotation in 0 1; do
        mvn -B -pl regelsuche-learning -am -Dtest=IntervalTransferStudyTest \
            -Dsurefire.failIfNoSpecifiedTests=false -Dinterval.phase="$phase" \
            -Dinterval.freeze="$seed" -Dinterval.rotation="$rotation" \
            -Dinterval.research="$PWD/docs/research" \
            -Dinterval.output="$runs/$phase-$rotation" test
    done
    for file in BASE.jsonl REGEN.jsonl DROP.jsonl FAST.jsonl FAST_DROP.jsonl paths.json summary.json; do
        cmp "$runs/$phase-0/$file" "$runs/$phase-1/$file"
    done
done
python3 docs/research/prospective-interval-transfer/verify.py \
    "$runs/final-0" "$runs/final-1" "$seed"
python3 docs/research/prospective-interval-transfer/verify_development.py \
    "$runs/development-0" "$runs/development-1"
```

The [independent checker](verify.py) reconstructed the 1,024 sources and verified their exact endpoint polynomials, witness chains, node counts, audit and replay costs, per-arm cumulative clocks and preregistered decisions across 5,120 quadrature rows and 1,024 distinct paths. The [quadrature archive](results) contains one lossless copy of both runs' common mathematical ledgers and both timing records. In the development stream every witness is already present in the [earlier path dictionary](../prospective-amortization/results/paths.json.gz). Its BASE, REGEN and DROP archives also supply the three unchanged controls. The development directory contains the two new per-task ledgers and the five-arm summary.
