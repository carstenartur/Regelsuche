# Exact local Boolean constraint counting

## Contract and status

`FiniteConstraintCounter` sums a product of local nonnegative integer factors over all Boolean assignments consistent with `Problem.fixed`. A factor table uses bit `i` for its sorted scope's `i`th variable. Zero/one tables describe forbidden/allowed configurations; larger weights give weighted counts. Empty products equal one. Every unfixed variable is summed over, including variables absent from all factors.

The implementation performs bounded variable elimination in ascending variable order. It returns an exact `BigInteger` count, a source-bound elimination trace, and a positive-weight assignment when the count is positive. `FiniteConstraintVerifier` independently conditions the original tables and replays each local sum, checks all buckets and remaining scalar factors, and checks the witness against the original problem. The producer invokes this verifier before returning `EXACT`.

Resource exhaustion returns `UNAVAILABLE` with no count or certificate. It never means zero solutions. Work, cancellation, interruption, wall time, retained table cells, intermediate width and integer bit lengths are bounded. Input factors have at most 20 variables; actual admissible table sizes also depend on the state budget. The shared hard ceiling is one million retained cells and 65,536 integer bits. Elimination can be exponential in induced width; the API makes no general polynomial-time or optimal-order claim. It does not implement a SAT asymptotic theorem or optional generating-polynomial refinements.

## Reproducible example

For three variables `a,b,c`, use factors with scopes `[0,1]` and `[1,2]`, both with table `[1,1,1,0]`. These forbid `a && b` and `b && c`. The exact count is **5**. Conditioning `b=true` gives **1**; conditioning `b=false` gives **4**. These are separate `Problem` values, so the condition is part of each certificate's source.

Core entry points: `FiniteConstraintCounter.count` and `FiniteConstraintVerifier.verify`. SDK entry points: `FiniteConstraintSdk.count` and `FiniteConstraintSdk.verify`. Pass an explicit `OptimizationBudget` appropriate to the retained tables; the SDK introduces no second arithmetic implementation.

The [complete Java example](../../examples/external-consumers/FiniteConstraintCount.java) prints counts `5`, `1`, `4`. After building the current source SDK and its runtime dependencies into `lib/`, run `java --class-path 'lib/*' examples/external-consumers/FiniteConstraintCount.java` with Java 25. The published 0.5.3 SDK does not contain this API.

## Evidence

`FiniteConstraintCounterTest` checks empty instances, isolated variables, weighted tables, contradictions, conditioning, forged local tables/totals/sources/witnesses and resource boundaries. Seventy seeded arbitrary small problems are compared with independent complete enumeration. `FiniteConstraintSdkTest` checks the public facade and cancellation. These tests check exactness and boundary behavior, not an unmeasured performance improvement.

## Research relationship

The motivation is the local-constraint/counting transfer identified in the [quantitative reduction manuscript](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/The-Exponential-Time-Hypothesis-is-False-October-25-2026/build/quantitative-reduction.tex). The implemented component is a conventional, finite, exact factor-elimination engine with an independent checker. Its correctness does not rely on the manuscript's headline complexity claim.
