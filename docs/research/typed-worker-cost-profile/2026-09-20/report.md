# Actual typed worker diagnostic profile

The dominant measured costs are repeated expression JSON transport, result serialization, and canonicalization. Pure mathematical pattern matching is a small fraction of the observed Java execution stacks. This is a diagnostic of the uncached worker, not a speedup benchmark, CI timing qualification, or result from the protected studies. No production or frozen benchmark files were changed for profiling.

## Scope and provenance

Compiled runtime snapshot declared by the successful build owner as #1043 commit `29db5761`; checkout had already advanced to `9657a6e8` when copied. All classpath files were hashed before/copy/after; `snapshot.json` records the 14 entries and 797 learning classes. Snapshotted runtime bytes remained unchanged. Java is Temurin 25.0.2+10. One process per BASE and LEARNED_NAIVE; real initialize/TRAIN and normal worker API. Both use the original uncached providers.

Twelve public development inputs per balanced cycle: two-sites, simple-control, near-miss, two-near-misses, offset, outer-product, sum-composition, compound-base, scaled-site, nested-factor, double-factor, negative-site. Stop rule written before queries: finish a whole cycle once 30 query seconds and 10 cycles have elapsed, capped at 120 cycles. Thus profile counts differ; total CPU or request counts are not controlled comparative speedup estimates. Other build activity may contend on this host.

JFR profile settings: ExecutionSample every 10 ms, ObjectAllocationSample throttle 300/s, stack depth 128, 512 MiB maximum Java heap. Initialization is separated by controller epoch timestamps; all raw outputs are retained in compressed JSONL. The existing independent rational-polynomial `judge` checked every final output and cost; this does not independently verify every intermediate witness. Existing model/profile/budget bindings were also checked. Graceful stdin close plus wait4 retained JFR and whole-worker CPU/RSS receipts.

## Actual work and process receipts

| Measure | BASE | LEARNED_NAIVE |
|---|---:|---:|
| Queries / balanced cycles | 1164 / 97 | 852 / 71 |
| Independent valid outputs | 1164 | 852 |
| Improved outputs | 873 | 639 |
| Work overruns | 0 | 0 |
| Initialization wall seconds | 0.317 | 0.734 |
| Actual TRAIN work | 0 | 2734 |
| Startup wall seconds | 0.950 | 0.977 |
| Query wall seconds | 30.090 | 30.202 |
| Whole worker user / system CPU seconds | 38.445 / 2.790 | 37.729 / 4.400 |
| Controller CPU seconds | 5.649 | 5.288 |
| Independent verifier CPU seconds | 0.542 | 0.347 |
| Peak resident MiB (OS wait4) | 435.53 | 443.58 |
| Query execution / allocation samples | 1286 / 8482 | 1427 / 8325 |
| Weighted estimated allocation GiB | 13.19 | 14.76 |
| Weighted estimated allocated MiB / query | 11.60 | 17.74 |
| Query GC collections / pause seconds | 143 / 0.461 | 335 / 0.857 |

CPU includes all JVM threads, JIT, GC, initialization, JSON and shutdown. JFR percentages below describe sampled Java stacks; they must not be multiplied by process CPU to claim exact component time. Allocation weights are JFR estimates of allocated bytes, not exact counters, object counts or live retention. The protocol includes no cached arm, so cache benefit/overhead is unmeasured.

## Exclusive mechanism attribution

| Mechanism | BASE execution samples | LEARNED execution samples | BASE allocation weight | LEARNED allocation weight |
|---|---:|---:|---:|---:|
| typed JSON codec | 35.85% | 36.86% | 34.62% | 30.45% |
| result/model/response JSON serialization | 30.25% | 28.94% | 34.85% | 33.34% |
| canonicalization and polynomial normalization | 18.27% | 19.83% | 23.27% | 27.81% |
| source exclusion/frozen-model checks | 5.37% | 4.63% | 3.26% | 3.94% |
| remaining search/path/incumbent management | 3.81% | 2.80% | 0.62% | 1.05% |
| remaining candidate generation | 2.88% | 2.80% | 2.04% | 2.32% |
| remaining replay/verification | 1.56% | 1.89% | 1.19% | 0.89% |
| other Java/runtime/boundary | 1.48% | 1.54% | 0.08% | 0.13% |
| mathematical pattern match/instantiate | 0.54% | 0.70% | 0.07% | 0.07% |

Cache bookkeeping: zero samples; the default worker never opens a candidate/codec cache. Missing samples alone are not an overhead measurement of an enabled cache.

Mechanism buckets are mutually exclusive: codec, serialization, canonicalization, mathematical matching, remaining replay, remaining generation, remaining path management, source/model checks, other. `analyze_samples.py` contains exact rules. Broad caller totals below include nested mechanisms.

## Where those costs are paid

| Broad caller (exclusive) | BASE Java execution samples | LEARNED Java execution samples |
|---|---:|---:|
| primitive generation | 36.31% | 27.12% |
| search evidence export | 19.52% | 17.31% |
| path/state/incumbent management | 17.50% | 14.86% |
| worker JSON response serialization | 10.19% | 10.37% |
| primitive verification/replay | 8.79% | 6.24% |
| per-query frozen-model reconstruction/check | 3.81% | 4.06% |
| TRAIN exclusion identity check | 2.10% | 1.82% |
| other Java/runtime/boundary | 1.79% | 1.75% |
| learned macro generation | 0.00% | 11.91% |
| learned macro verification/replay | 0.00% | 4.56% |

## Concrete hot paths and engineering implication

1. `TypedMoveSearch$1.candidates → CompiledAstReplayCodec.decodeExpression` repeatedly parses the existing encoded state, constructs JSON nodes and exact rationals, then re-encodes to check canonical text. The same codec is paid by `TypedMoveSearch.State.decode` for verification, reached states and event projections, and by learned macro transport. Pure-expression reuse inside an explicit bounded query scope is supported by this evidence; path identity, assumptions, eligibility and proof regeneration must remain separate.

2. `LearnedSchedulingArtifacts.resultJson → normalize/json` exports search events, then `TypedExternalPolynomialComparisonWorker.main → LearnedSchedulingArtifacts.json` serializes the response again. The top leaf samples are Jackson string writers, hash/linked maps and text buffers. Result evidence must remain available and any future export optimization must preserve its contract.

3. `AstRewriteTransport.generate → PreparedAstRewriteTransformationEngine.transformAst → canonicalAstNodeCount → ExpressionCanonicalizer/PolynomialNormalizer` contributes 18.27% BASE /19.83% LEARNED execution samples and 23.27%/27.81% allocation weight. This is substantial work inside generation, distinct from mathematical pattern matching.

4. Learned macro generation accounts for 11.91% of learned query samples; macro verification/replay accounts for 4.56%, including their nested codecs and primitive work. BASE has no learned macros. Primitive verification accounts for 8.79% BASE /6.24% learned. These broad callers overlap neither each other nor the purpose table but do include the mechanisms above.

5. The common Python verifier consumed 0.542s BASE /0.347s learned controller CPU over all attempted queries. Initialization used only 8/34 execution samples, so no precise training-path attribution is claimed. Training work and the actual initialize wall/CPU response are retained in metadata.

No speedup claim follows from this profile. Use the same architecture optimization for BASE and LEARNED; separately measure whether learned schemas add value. Stop sampling now: the dominant transport/canonicalization allocation paths are supported by both recordings.

## Retained evidence

- `BASE/worker.jfr`, `LEARNED_NAIVE/worker.jfr`: original recordings.
- Profile `rows.jsonl.gz`: every complete request outcome and independent judgment.
- Profile `metadata.json` and `process-samples.json`: phase timestamps, frozen model, OS CPU/RSS and per-case outcome counts.
- Profile `samples.tsv.gz`: compact lossless-for-attribution stack export; original JFR remains authoritative.
- `analysis.json`, `analyze_samples.py`, `ExtractJfr.java`: definitions, counts, weighted allocation classes and top complete project stacks.
- `classpath/`, `snapshot.json`, `source/`: fixed runtime and source provenance.
- `sampling-plan.json`, `profile_worker.py`, `diagnostic.jfc`: workload and launch settings.

One retained launch failure occurred before READY because JFR startup INFO lines reached strict JSON stdout. No initialize/query ran. `-Xlog:jfr+startup=off:stdout` resolved it; the failed directory and three no-query startup probes remain separate.
