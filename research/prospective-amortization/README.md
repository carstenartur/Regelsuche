# Learned rewrites completed four additional tasks at a fixed cold-start budget

At the preregistered cutoff of **524,288 logical work units**, the solver with independently checked learned rewrites completed **646** tasks. The primitive solver completed **642**, the earlier learned solver **635**, and the same new solver with its learned providers removed **541**. All four ultimately solved all 1,024 inputs. The four additional completions measure progress through a shared ordered stream by a deadline. The primitive solver also solves those four inputs if given more work.

This was one prespecified follow-up to the [128-task Horner study](../prospective-horner-transfer/README.md), which tied at its primary deadline. That result motivated a longer series to test whether the observed reduction in work per query could repay the initial costs. The [follow-up protocol](https://github.com/carstenartur/Regelsuche/commit/c01d8c8061d0bb041f4087f121583a40a71c0ddf) was published before the [evaluator freeze](https://github.com/carstenartur/Regelsuche/commit/efb9439d38f8f0fa4e20ce5ffbedc34fa5248519). The latter commit supplied the sole generator seed. There were no changes to the solver, model, acquisition or promotion decisions between studies.

| Arm | Verified by deadline | Learned steps in full stream | Work for all 1,024 |
|---|---:|---:|---:|
| Primitive (BASE) | 642 | 0 | 834,410 |
| Earlier learned (PRIOR) | 635 | 1,536 | 837,541 |
| Source-derived verification (REGEN) | **646** | 1,536 | **823,252** |
| Learned providers removed (DROP) | 541 | 0 | 979,844 |

The 512 Horner expressions and 512 dot-product expressions were interleaved in balanced blocks. Each block included examples with zero, one, two and three expanded coefficients for each topology. The local distributive relation was deliberately shared with the training inputs. The generator produced new polynomial identities and the learner saw none of these final tasks. Its two training inputs and three validation inputs contained neither complete topology. The search received only the starting expression and a node-count bound. The four arms used the same task sequence and the same independent checks.

| Topology | BASE by deadline | PRIOR | REGEN | DROP | REGEN gains / losses vs BASE |
|---|---:|---:|---:|---:|---:|
| Horner | 320 | 316 | 323 | 270 | 3 / 0 |
| Dot product | 322 | 319 | 323 | 271 | 1 / 0 |

These paired counts use the **shared cumulative deadline**. The balance between gains and losses can depend on work performed on preceding tasks. The [per-topology ledger](results/topologies.json) also reports complete-stream costs before the common setup cost. On every arm and every topology all 512 returned solutions ultimately passed their audits.

| Work component | BASE | PRIOR | REGEN | DROP |
|---|---:|---:|---:|---:|
| Training, validation, model loading and preparation | 0 | 12,805 | 12,828 | 12,828 |
| Search including in-search verification | 639,904 | 517,114 | 509,958 | 772,510 |
| Independent final replay | 21,248 | 134,364 | 127,208 | 21,248 |
| Exact polynomial audit | 173,258 | 173,258 | 173,258 | 173,258 |

Over the full stream, REGEN used **11,158 fewer units than BASE (1.34%)** and 14,289 fewer than PRIOR. The model arms each paid 9,130 of their setup units to acquire the seven frozen schemata. REGEN independently matches each learned step against the source, reconstructs all bindings, and compares the complete canonical evidence to the submitted evidence. This avoids decoding the claimed substitution trees a second time. The source check runs in the search and again during final path replay. The opt-in mode preserves the previous verifier for reproducing older studies.

The result establishes a cold-start advantage for this particular synthetic workload and logical accounting policy. The tasks are related instances of two designed shapes. An advantage on arbitrary mathematics, elapsed CPU time or external source programs remains an open question. The bounded series ended with this follow-up.

## Reproduction and independent check

Use JDK 25 and Maven. From the root of the [frozen source](https://github.com/carstenartur/Regelsuche/tree/efb9439d38f8f0fa4e20ce5ffbedc34fa5248519) run the following commands in Bash.

```bash
runs=$(mktemp -d)
seed=efb9439d38f8f0fa4e20ce5ffbedc34fa5248519
for rotation in 0 1; do
    mvn -B -pl regelsuche-learning -am -Dtest=HornerTransferStudyTest \
        -Dsurefire.failIfNoSpecifiedTests=false -Dhorner.phase=amortization \
        -Dhorner.research="$PWD/docs/research" -Dhorner.freeze="$seed" \
        -Dhorner.rotation="$rotation" -Dhorner.output="$runs/$rotation" test
done
for name in BASE.jsonl PRIOR.jsonl REGEN.jsonl DROP.jsonl paths.json summary.json topologies.json; do
    cmp "$runs/0/$name" "$runs/1/$name"
done
```

The [independent checker](verify.py) in the reporting revision takes the two run directories and the full seed as arguments, for example `python3 verify.py "$runs/0" "$runs/1" "$seed"`. It reconstructs the generator, computes integer polynomials and node counts independently of the Java solver, checks the arithmetic along each witness path, sums the audit and replay charges, and reconciles the deadline decisions with all four ledgers. Both runs produced byte-identical mathematical evidence across **4,096 rows and 2,560 distinct witness fingerprints**. The [published results](results) contain the original per-task JSON lines and path dictionary compressed losslessly with gzip, both execution-order timing records, and the full summaries.
