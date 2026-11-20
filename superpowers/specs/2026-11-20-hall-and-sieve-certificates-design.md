# Hall and finite sieve certificates

Primachsenraum needs two finite counts for its canonical Greedy proof project. A Hall certificate bounds the number of candidates that can have distinct blockers. A Bonferroni certificate bounds how many progression elements escape activated congruences, even when the full inclusion–exclusion expansion exceeds the term limit. Each certificate answers a different question and can be checked against its original input.

## Global constraints

- Java 25. No new dependencies.
- CORE owns all mathematical algorithms and verification.
- SDK facades map `OptimizationBudget` and `CancellationToken` to `IterationLimits` and `BooleanSupplier`.
- The consumer owns chronological provenance, graph completeness and the interpretation of the bounds.
- Every claim is finite. No analytic theorem is assumed.

## Hall deficiency

Represent the graph by cardinalities of its two vertex sets and a finite collection of integer edge pairs. Preserve isolated vertices, distinguish the two sets of indices, and validate endpoints. Repeating an edge cannot create additional capacity. Make the source and certificate immutable. Process vertices and neighbors in deterministic order.

For a matching of maximum size, follow alternating paths from all unmatched left vertices. Let their reachable left set be W. The certificate carries the source graph, matching M and W. The verifier receives the expected graph separately. It checks source identity by value, endpoint disjointness and membership of every matched edge. It computes the entire neighborhood N(W) from the expected edges and checks

    |M| = |U| − |W| + |N(W)|.

The right side counts a vertex cover. It is an upper bound for every matching, while M attains it. Thus |U| − |M| is the exact maximum of |W| − |N(W)| over all left subsets. An empty W is valid. Derive all reported cardinalities from this evidence or validate them explicitly. The verifier uses a linear scan of the represented graph and proof. It works independently of the augmenting-path search.

A Hall witness alone can certify its own deficit. Adding the matching certifies the strongest deficit available from the graph. Use a bounded deterministic augmenting-path search. Check work, allocation dimensions, cancellation and timeout in search and verification, including on edgeless graphs. Exhaustion yields an unavailable search or an unsuccessful verification.

## Activated congruence count bounds

Use the existing finite progression, normalization, inclusive activation and generalized CRT intersection in `CongruenceCoverage`. For rule sets Aᵢ on a progression of N elements, let Sⱼ sum the cardinalities of all intersections of j distinct rule positions and let S₀=N. The partial sum Tₖ=Σⱼ₌₀ᵏ(−1)ʲSⱼ is a lower bound for the survivor count at odd k and an upper bound at even k. For a point hit by t>0 rules its contribution through k<t is (−1)ᵏ times binom(t−1,k), and through k≥t it is zero. Every unhit point contributes one. These identities justify the parity and exactness at full depth, including repeated rules.

Return an interval contained in [0,N], using the strongest bounds from complete layers through the requested order and both endpoints equal when all rule positions are exhausted. Order zero gives the trivial interval when effective rules remain. Bind the original query and order to a separate bounds certificate with independently replayable layer totals. Prefix sums alone need not improve monotonically across successive odd orders or successive even orders. Compare all complete layers of each parity when tightening the interval. A bound contains no position for the first survivor. If the requested computation is interrupted, return unavailable. Use existing bounded arithmetic and avoid constructing the full union.

The exact-union entry point retains its role when an exact count or least free value is needed. For multiples of 11,13,17,19,23,29,31,37 over one full period of 35,336,848,261 consecutive indices starting at zero, exact expansion has 255 distinct signed terms. Eight single counts certify a survivor lower bound of 20,617,871,161. Order three certifies 22,976,886,861. The independent exact count is 22,992,076,800. The finite sign argument is related to the alternating tree in [family 021, §3](https://github.com/openai/math/blob/main/preprints/A-quadratic-bound-for-Jacobsthals-function-September-25-2026/build/sections/tree.tex#L29-L64).

## Consumer handoff

Pin a Primachsenraum source snapshot before selecting files. The consumer supplies one p-class below the strict deadline D, candidates surviving all prior applicable exclusion tests, and every possible actual larger blocker edge. Each actual omission must have a represented cause. Two candidates h=a+2pi and h′=a+2pi′ meeting the same fixed class modulo an odd prime q>p satisfy q | (i−i′). A verified index span below q therefore gives capacity one. The actual omissions form a matching and at least the certified deficiency survives. Extra edges can weaken the bound. An unrepresented actual cause can invalidate it.

Document the source revision, availability of each rule at the candidate time, and finite window. Translate a strict integer activation h>T into inclusive activeFrom=T+1. Verify the candidate graph independently from the chronology on a finite reference case. A count bound by itself supplies neither the candidate vertices for this graph nor their chronology. A finite result leaves the universal Greedy deadline open.
