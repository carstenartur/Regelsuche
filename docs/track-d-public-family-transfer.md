# Public held-out transfer of a mined factoring bridge

Eight formation searches yielded a candidate from each of two coefficient series. The production structural clusterer accepted their shared rewrite shape. The compiled bridge produced no candidate in either held-out family. The existing native factorization reached both intended targets in one step. Enabling the bridge left both paths and all search counters unchanged, while adding a matcher call at each expansion. [Pre-execution registration](https://github.com/carstenartur/Regelsuche/commit/bc52b33d5dc44d1c7661a6335cb49b33733bb616)

## Formation and information access

The first process reads eight expressions in two numbered batches. It runs the default native AST inventory through untargeted best-first search, then mines each batch independently. The two batches respectively produced 76 explored states and 80 generated transformations each. Their candidate records retain the distinct native rewrite paths that converged to each output. The cluster disposition and hypothesis are derived by the existing structural clusterer and bridge builder. The runner attaches the registered family metadata only after both candidate sets are fixed. Its output commits to all eight observation identities. [Formation inputs](../research/benchmarks/track-d-public-transfer/formation.json) · [Retained formation evidence](../research/benchmarks/track-d-public-transfer/formation-result.json)

The first series used coefficient pairs separated by two, and the second used twin-prime pairs. Both therefore share the same numerical gap. Both produced the pattern `(A + 2)*x + A*x → (2*A + 2)*x`. This structure is more specific than general common-factor extraction. The second process receives the frozen formation output and a separate evaluation manifest. The trigonometric and polynomial forms are absent from the formation inputs. Their registration is public, so the experiment addresses program input access within this comparison. It does not establish independent corpus selection or protected-test performance. [Post-hoc labels](../research/benchmarks/track-d-public-transfer/posthoc-family-labels.json) · [Evaluation cases](../research/benchmarks/track-d-public-transfer/evaluation.json)

## Paired results

Each family has one factoring target and one false target obtained by changing one factor in the source. Each arm has the same native inventory, search budget, and syntax-exact target. The treatment appends the compiled frozen bridge after native candidates. The ablation retains the actual search path and duplicate, transposition and budget counters for both arms. The following counts are identical in both arms. The final column counts extra invocations of the bridge matcher in the treatment over both tasks.

| Family | Split | Positive explored / generated | Near miss explored / generated | Near-miss termination | Bridge calls |
| --- | --- | ---: | ---: | --- | ---: |
| Gap-two coefficients | Formation family, fresh values | 3 / 4 | 7 / 7 | Frontier exhausted | 9 |
| Twin-prime coefficients | Formation family, fresh values | 3 / 4 | 7 / 7 | Frontier exhausted | 9 |
| Trigonometric factors | Held out | 2 / 4 | 8 / 8 | Depth budget | 8 |
| Univariate polynomial factors | Held out | 2 / 8 | 80 / 166 | State budget | 27 |

All four positive paths used `ast_factor_common_left` directly. Neither arm reached a false target. The bridge emitted no transformation in 53 matcher invocations. Zero emitted transformations is compatible with the additional matching work recorded here. Generated-transform counts alone cannot measure that overhead. The comparison records no timing or per-match CPU cost. [Complete 16-run ledger](../research/benchmarks/track-d-public-transfer/transfer-result.json)

## Validation, assumptions and proof

Every family's single fresh positive holdout failed to produce an applicable compiled candidate. All four adversarial near misses produced no application. The bridge had no declared assumptions and the bounded counterexample search recorded no counterexample or inferred premise. The ordinary transfer evaluator rejected the formation-family checks. Both held-out suites also failed their positive checks. The polynomial-normal-form proof gate issued no obligation because validation failed. Formal proof remains unevaluated. The paired ablation is ineligible for a beneficial-transfer claim. The native inventory already implemented factoring before this experiment. The study gives no external novelty result. [Validation, proof and ablation receipts](../research/benchmarks/track-d-public-transfer/transfer-result.json)

## Reproduction

From the repository root with JDK 25, run:

```bash
mvn -B -pl regelsuche-learning -am -Dtest=PublicFamilyTransferComparisonTest test
python3 scripts/verify-track-d-public-transfer.py
```

The module test runs formation with only its two inputs present. It then removes them before execution of the evaluation stage. Both outputs must reproduce the checked-in bytes. The test independently changes the formation input, the frozen candidate bundle and the evaluation cases, and requires rejection. The Python verifier checks the registered input hashes, matches every formation and evaluation identity, recomputes path and generation counts, and totals the observed bridge invocations across all sixteen runs. It checks the retained evidence without reimplementing the native Java search engine.

| Canonical evidence bytes | SHA-256 |
| --- | --- |
| Formation result | `d613013350cf0959d6e4e45f7f16d9d31b8f3cb6231fc1fe287443c186d70bef` |
| Transfer result | `9c10cf1e8a36659204183674e0c94e820c18424862dc62e06d647883e1343605` |
