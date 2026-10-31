# Extended verified finite series

The user authorized implementation of the previously discussed extensions on 2026-10-31 ("Kannst du das alles umsetzen?"). This supersedes the first-fragment deferrals in the finite-iterations design. Keep and extend that working implementation; do not discard recovered work.

## Goal and responsibility

Support useful, bounded families of finite reductions beyond constant-bound additive loops: runtime-bound polynomial sums, nonunit strides, local temporaries, multiple accumulators, nested polynomial sums, exact BigInteger arithmetic, geometric progressions, affine and small linear recurrences, constant-factor products, periodic and piecewise polynomial reductions and rational telescoping. Support ordinary for/while/do and suitable sequential range streams where a Java admission proof can establish equivalent semantics. This is a family-based implementation, not a claim that arbitrary programs or every symbolic sum are decidable.

Regelsuche owns mathematical models, candidate construction, and independently checkable certificates. Sandbox owns Java name/type/effect/termination admission, lowering, generated-code validation, source replay, and the user-facing explanation. New paths must integrate into the existing optimizer and evidence/report model, not merely be standalone demonstrations.

## Semantics and safety

- Keep exact integers and exact rationals distinct from Java int/long modular arithmetic. Do not reassociate floating-point arithmetic or BigDecimal operations under strict Java preservation.
- Runtime iteration counts are nonnegative mathematical integers. Empty ranges preserve the incoming state and must not introduce previously unexecuted reads or exceptions.
- Counted Java loops must terminate without counter wrap. Runtime conditions may use an explicit guard and retain the original loop as fallback when representability is not provable for all inputs.
- Polynomial output may use integer-valued/binomial representations or exact BigInteger intermediates; never emit overflowing primitive multiplication followed by ordinary division as a substitute for exact division.
- Local temporaries and ordered state assignments are allowed only after binding-aware substitution preserves width, evaluation order, and effects. Preserve live counters and all state outputs; retain comments.
- Rational telescoping is for exact rational semantics with checked nonzero denominators. It does not authorize rewriting truncating integer division or floating-point sums.
- Infinite series, arbitrary nonlinear recurrences, impure/unknown calls, arbitrary floating-point reductions and BigDecimal scale/context changes remain explicitly unsupported. Fail closed with a reason, never silently claim verification.
- Every model, intermediate, proof, emitted expression, runtime-evaluation helper, work budget and cancellation path has a finite limit. Limits are public and documented; index degree and free-parameter complexity should be distinguished.
- Verification must not invoke search or trust candidate status/cost. Check source identity, binding scope, mathematical certificate and actual emitted result. Test adversarial certificates and source mutations.

## Acceptance

Compiled Java equivalence tests must cover negative/empty ranges, inclusive/exclusive boundaries, positive/negative nonunit strides, int/long overflow, mixed-width rejection or range proof, nonzero incoming states, comments, selections, cancellation, stale source and combined edits. Runtime-bound summation for n=50000 must produce 1250025000, not the overflowing primitive n*(n+1)/2 result. Polynomial degree eight is a useful baseline, not an accidental total-degree limit including independent parameters.

Tests of mathematical families include cubic and higher powers, symbolic parameter coefficients, huge counts without source-loop unrolling, nested triangular sums, alternating/periodic sums, affine updates, geometric sums, constant products, small coupled recurrences, rational telescope identities and pole rejection. All tests are Maven/JUnit; preserve the existing source-qualified SDK distribution contract and run a fresh standalone Java consumer.

## Delivery

Use isolated feature branches and ordinary draft PRs directly against main. No merge or release is authorized. Maintain a persistent execution ledger and meaningful commits so quota interruption cannot lose work. Repository-size exceptions must be substantive and recorded in the PR before exceeding the policy threshold. Broad integration branches remain draft/reference until the work is split into reviewable merge candidates.
