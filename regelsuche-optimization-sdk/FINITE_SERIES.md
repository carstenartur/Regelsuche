# Verified finite-series kernel

The APIs in `de.regelsuche.sdk.optimization.iteration` are mathematical models and bounded proof/evaluation helpers. Java source admission, termination without counter wrap, effects, binding substitution, source replay, and checking generated Java belong to the caller. These APIs never justify Java truncating division, floating-point reassociation, or BigDecimal scale/context changes.

## Runtime-bound polynomial sums

`PolynomialSum(index, parameters, start, stride, term, initial, arithmetic)` describes

```
initial + sum(term(index = start + stride*k), k = 0 .. count-1)
```

`count` is a runtime nonnegative `BigInteger`; `stride` is a nonzero constant. All symbols are scoped `SymbolId` values. Start and initial may mention parameters only. Supported expression operators are addition, subtraction, multiplication and exact numeric leaves. Source division, powers, functions, and unscoped symbols are rejected (construct powers as multiplication).

`PolynomialSumOptimizer.search` constructs a `PolynomialSumPlan` with coefficients `c[j]` and initial expression. Its result is

```
initial + c[0]*binomial(count,1) + c[1]*binomial(count,2) + ...
```

The list and source are immutable. `PolynomialSumVerifier.verify(source, plan, budget, cancellation)` independently reconstructs the source term in the Newton basis, checks the source identity and initial expression, validates symbol scopes, and proves coefficient integrality. Verification does not call synthesis. Search success alone is not verification.

`plan.evaluate(count, parameters, budget, cancellation)` verifies the plan and evaluates it with exact intermediates, then applies `EXACT`, `JAVA_INT` or `JAVA_LONG` final normalization. Java primitive arithmetic is only modeled when all source operations belong to that single ring and the caller has independently proved counter semantics. Fractional source literals are rejected for both modular rings. Exact sources may contain rational polynomial leaves only when all multivariate Newton coefficients establish integer values on the complete integer lattice. This supports composed binomial polynomials, not arbitrary fractional ring arithmetic.

At count zero only the incoming initial value is read from the parameter map. Coefficients multiplied by zero binomials are not evaluated. Negative counts, missing used parameters, cancellation, malformed proofs and exhausted limits fail closed.

A caller can emit the exposed integer coefficients and binomial recurrence using `BigInteger`; ordinary integral source polynomials produce integral coefficient leaves. Exact rational coefficient expressions from composition require exact rational evaluation or a common denominator with a proved exact division. They must never become primitive multiplication followed by Java division.

## Composition

`PolynomialSeries.NestedSum` and `nested(...)` compose exact polynomial inner and outer sums. The inner count is affine in the outer index, with polynomial free-parameter coefficients. This covers rectangular and triangular bounds, either stride direction and nonzero initial states. Independent verification expands the inner Newton representation into the outer source and verifies both plans. At runtime the first and last executed outer indices prove that all affine inner counts are nonnegative. Empty outer ranges skip bound reads. If all inner ranges are empty, only repeated inner initial values contribute; a bounded initial-only plan is synthesized and independently checked under the same runtime budget. Higher-degree bounds and modular nesting are deliberately rejected.

`PeriodicPlan` interleaves a bounded list of already source-bound plans. Residue `r` receives `max(0, ceil((count-r)/period))` local terms. Each child source supplies the start and stride for its own progression; for a global arithmetic progression, set its start to `start+r*stride` and its stride to `period*stride`. An alternating sum uses two plans with opposite term signs.

`PiecewisePlan` concatenates local child plans in consecutive ordinal intervals. Endpoints are strictly increasing, starting implicitly at zero, so gaps and overlaps cannot be represented. A null final endpoint means an unlimited final interval. Counts beyond a finite final endpoint are rejected. Each child starts at its own local ordinal zero; its mathematical source start must describe the corresponding interval.

Periodic and piecewise incoming states are the sum of child initial values, each included exactly once, including empty children. Use zero child initial values except where an incoming state is intended. Children must share arithmetic. All child verification and evaluation uses a single work ledger.

## Exact rational telescoping

`RationalTelescoping.Source` is explicitly exact rational and represents a numerator polynomial in free parameters divided by a quadratic denominator of the form

```
a * (index + b(parameters)) * (index + b(parameters) + stride)
```

Here `a` is a nonzero rational constant; start is integer-valued and stride is a nonzero integer. Search derives a rational primitive. The independent verifier checks its cross-multiplication difference identity and checks that its affine denominator and shifted denominator factor the entire source denominator. Consequently checking the exact affine zero on the integer iteration lattice detects every source pole, including interior poles and the last term's second denominator. The pole test has constant iteration-count complexity. Count zero preserves the exact rational initial value without parameter reads or pole checks. The checker rejects off-fragment denominators and index-dependent source numerators.

## Linear state recurrences, geometric series and products

`LinearIteration` describes up to eight named state values, their incoming expressions,
an index start and constant stride, an exact nonnegative count expression, invariant
parameters, and either simultaneous or sequential assignments. Every state is assigned
once per iteration. Sequential assignments observe earlier assignments in that iteration;
simultaneous assignments all observe the incoming state. Distinguishing these orders is
necessary for coupled recurrences.

`LinearIterationOptimizer` constructs a `LinearTransition`, containing an immutable
matrix and initial vector. Coordinates are the states, a constant one, and the required
index powers. `LinearIterationVerifier` expands each candidate row and independently
checks it against the original ordered updates, then checks every initial and index-power
coordinate. Neither candidate status nor its reported work count establishes correctness.

This represents scalar affine updates (`s = a*s+b`), geometric accumulations,
constant-factor products, coupled linear states such as Fibonacci, and polynomial
forcing (`s = a*s+p(i)`). State coefficients may be polynomial in invariant parameters,
but must be independent of the index. Nonlinear products of evolving states are rejected.
Source expressions use integer leaves and `+`, `-`, `*`; powers are multiplication trees.

`LinearIterationEvaluator` verifies the transition before binary matrix powering.
The number of powering steps grows with the bit length of the count, not the count
itself. Java int/long arithmetic normalizes each operation in its ring. Exact arithmetic
rejects excessive growth before allocating an unbounded result. `verifyResult` additionally
checks supplied output values against the verified transition. For a zero count only the
incoming states are evaluated; update-only parameters and the unused index start are not read.

The linear provider has separate limits: eight states, index degree eight, invariant
parameter degree sixteen, at most seventeen augmented coordinates, sixteen parameters,
256 polynomial terms, 2048 expression nodes, depth64, and 4096-bit exact coefficients,
runtime values and count. Work, timeout and cancellation limits apply to verification
and every multiplication in powering. A Java emitter must still prove source admission,
termination, all live outputs and the correspondence of its actual generated program.

## Public polynomial and rational limits

* Source index degree: 8; polynomial intermediate total degree: 32, independent of the index-degree test.
* Sparse terms: at most 256, further limited by `OptimizationBudget.maximumStates()`.
* Free parameters: 16; source and parsed proof trees: 2048 nodes, depth 64.
* Symbolic rational numerator and denominator sizes: 4096 bits.
* Runtime integer/rational numerator/denominator sizes: 65536 bits. Intermediate products are checked before further use; one operation may temporarily hold the product of two bounded inputs.
* Periodic residues and piecewise intervals: 32.
* Every entry point has a work budget, timeout and cancellation token. Nested and collection evaluation shares the same ledger across children. Integer runtime operations debit work proportional to operand/result bit length; no API enumerates the runtime iteration count.

Limits may reject a mathematically valid result or proof. Reasons such as `INDEX_DEGREE_LIMIT`, `DEGREE_LIMIT`, `TERM_LIMIT`, `EXPRESSION_LIMIT`, `COEFFICIENT_LIMIT`, `RUNTIME_BIT_LIMIT`, `WORK_LIMIT`, `CANCELLED`, `NONINTEGRAL_POLYNOMIAL`, `SOURCE_MISMATCH` and `POLE_IN_RANGE` are intentional fail-closed outcomes. Input constructors reject structurally invalid models with `IllegalArgumentException`; search and verification return a reason, and runtime evaluation throws `IllegalArgumentException` on failure.

The original `FiniteIterationOptimizer` and `FiniteIterationVerifier` APIs and their original degree limits remain unchanged.
