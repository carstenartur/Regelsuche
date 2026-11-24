# Costed elimination-order transfer on public software configuration models

The [protocol](protocol.md) records the selection, the training extension after the format check, and the resource limits. The [input manifest](inputs.tsv) pins each uncompressed DIMACS source by SHA-256. Gzip copies with the collection's [license](inputs/LICENSE) allow offline runs. Each original clause is interpreted as one factor and each declared DIMACS variable is counted, including auxiliaries. No user-visible product-variant semantics were assumed.

## Outcome

The original ascending-ID order exceeded a hard resource limit on both supported evaluation problems. Both structural heuristics and the trained selector produced exactly the d4 counts and replayed their certificates against the original unpermuted factors. One of the three evaluation problems contained a 62-variable input clause, beyond the existing 20-variable factor contract. d4 solved all three evaluation problems. On this panel acquisition did not pay back.

| Evaluation source | Ascending | Minimum degree | Minimum fill | Trained | d4 |
| --- | --- | --- | --- | --- | --- |
| BerkeleyDB | Input width 62 | Input width 62 | Input width 62 | Input width 62 | 32 |
| Bank | Width limit | Verified | Verified | Verified, minimum degree | 52,582,279,903,621,926,514,707,790,823,424 |
| Printer | Width limit | Verified | Verified | Verified, minimum degree | 2,278,241,108,363,321,839,974,600,000 |

Among the four training sources, Tankwar (maximum clause width 23) and ERP (77) exceeded the input contract; every ordering for Routefinding exhausted the retained-cell budget. Only axTLS contributed a successful ordering. All evaluation choices selected its minimum-degree label. This is a transfer of a strategy selection, with no measured advantage over fixing that strategy. The costs from unsuccessful training trials are included.

| Evaluation strategy | Bank work | Printer work | Total with acquisition | Solved of 3 |
| --- | ---: | ---: | ---: | ---: |
| Ascending | 28,142 (limit) | 49,099,072 (limit) | 49,127,214 | 0 |
| Minimum degree | 626,286 | 3,665,908 | 4,292,194 | 2 |
| Minimum fill | 494,026 | 3,654,439 | 4,148,465 | 2 |
| Trained | 627,625 | 3,667,417 | 113,715,787 | 2 |

The trained arm paid 109,420,745 units for acquisition plus 4,295,042 for the evaluation queries. The best fixed arm, minimum fill, used 4,148,465 at equal coverage. On axTLS itself, all 684 declared variables in 2,155 clauses gave **428,726,493,299,198,656,512** assignments. Ascending returned `TERM_LIMIT` after 427,848 units. Minimum degree cost 21,068,354 units and minimum fill 21,100,433, including planning and the internal original-source verifier. Those measures establish an increase in supported cases relative to the previous order. They do not establish an advantage over d4 or a benefit from training on this panel.

Reported results were measured using Temurin OpenJDK 25.0.5, Linux x86-64 and a 2 GiB maximum JVM heap. The [source hashes](sources.sha256) identify the core implementation and harness at measurement. The fixture choices were recorded before the first solver runs on Bank, Printer and BerkeleyDB. An initial development run on the same named panel preceded the final generic planner and learning resource-accounting changes. The reported rerun is a public development comparison; there was no independent blind final test after those changes.

## Reproduction

Run with JDK 25 and Maven from this checkout. With Maven dependencies present locally the harness needs no network. A fully built reactor classpath can also be supplied through `REGELSUCHE_STUDY_CLASSPATH`. The script uses the installed dependency classpath or builds it from the checked-out modules.

```sh
study=research/benchmarks/constraint-planning-transfer
"$study/run.sh" > /tmp/constraint-planning.tsv
python3 "$study/check.py" /tmp/constraint-planning.tsv
```

[Java raw results](java-results.tsv) record one row per source/arm and the complete raw training ledger. `INPUT` fields are partition, name, variables, clauses, maximum distinct clause width and admission. `ACQUIRE` contains training status, total charged logical work, wall nanoseconds and current-thread CPU nanoseconds. `TRIAL` gives training source, strategy, status, reason, work and best successful strategy. `RUN` contains partition, source, arm, selected strategy, originating training source, status, reason, count, total charged work, additional source-bound audit work, wall nanoseconds and current-thread CPU nanoseconds. `PARSE` records input decompression, digest checks and table construction times separately. Charged work on each solution includes the production verifier and the selected planner. The extra audit replays the certificate independently on its original source and is reported separately. Times are single-process samples in fixed execution order. The study makes no comparative runtime claim.

The [external control](d4-results.tsv) uses the Linux d4 binary distributed for [feature-model batch analysis](https://github.com/SundermannC/feature-model-batch-analysis/blob/85edaeb26a3a0e821e0f91233f2d045dbcd7bbb3/solver/d4). The pinned executable has SHA-256 `4e5f5591e00af08f604dd7fe3c87446683bdf9353d3ede0b869d4f20c9ff5dc1` and was run with `-i INPUT -m counting` on the identical uncompressed DIMACS files. Its process timing includes the executable launch. To repeat the comparison with the exact binary, from a clone of that external repository:

```sh
git show 85edaeb26a3a0e821e0f91233f2d045dbcd7bbb3:solver/d4 > /tmp/constraint-study-d4
chmod +x /tmp/constraint-study-d4
# From the Regelsuche checkout:
python3 research/benchmarks/constraint-planning-transfer/check.py /tmp/constraint-planning.tsv --d4 /tmp/constraint-study-d4
```
