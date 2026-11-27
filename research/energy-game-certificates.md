# Cyclic energy-credit certificates

## Contract and status

`EnergyGame` accepts a finite directed graph with integer edge weights, Min/Max ownership, a start vertex, and at least one outgoing edge per vertex. The request includes a nonnegative upper bound on the credits to search. Parallel edges are identified by their list index; self-loops and cycles retain their semantics.

Bounded monotone credit lifting starts with zero at every vertex. Max chooses the least required successor credit; Min requires the greatest credit across every outgoing edge. A required credit above the request's bound becomes a distinguished top value. The process stops at a fixed point or a resource boundary. `WINNING` contains an independently checked certificate for a region containing the start vertex. `NO_CERTIFICATE_WITHIN_BOUND` supplies no winning or unbounded losing certificate. `UNAVAILABLE` means resource exhaustion or cancellation and also carries no certificate.

`EnergyGameVerifier` does not call the lifting procedure. It binds the certificate to the entire request and checks nonnegative bounded credits, exactly one selected edge per Max vertex in the region, closure along the selected Max edges and **all** Min edges, and

\[
c(v)+w(v,u)\ge c(u).
\]

Starting with at least `c(start)`, induction on path length preserves energy at least `c(v)` after each transition. Since the credits are nonnegative, the selected positional Max strategy keeps energy nonnegative for arbitrary play length against every Min choice. This is a cyclic local invariant, not a finite unrolling argument. The verifier certifies sufficiency; it makes no minimal-credit assertion about a supplied certificate.

## Reproducible example

Vertex `a` belongs to Max, vertex `b` to Min. Edges, in order, are `a -> b` with weight `-2`, `a -> a` with `-1`, `b -> a` with `3`, and `b -> b` with `0`. With credit bound `10`, the solver produces `c(a)=2`, `c(b)=0` and selects edge `0` at `a`. Both Min edges satisfy the inequality, including the self-loop.

Core entry points are `EnergyGame.solve` and `EnergyGameVerifier.verify`; the SDK delegates through `EnergyGameSdk.solve` and `EnergyGameSdk.verify`. Use an explicit `OptimizationBudget`. Work, wall time, cancellation, interruption, retained cells and 65,536-bit numeric growth are guarded. Runtime can depend on the credit bound; there is no quasipolynomial-time implementation or performance claim.

The [complete Java example](../../examples/external-consumers/checked-math-java25/src/main/java/example/EnergyCredits.java) prints credits `2`, `0` and selected Max edge `0`. Build and run it through the [standalone Maven consumer](../../examples/external-consumers/checked-math-java25/README.md); `mvn -f examples/external-consumers/checked-math-java25/pom.xml clean verify` runs all ten examples after the source SDK has been installed. The published 0.5.3 SDK does not contain this API.

## Evidence

`EnergyGameTest` compares 100 seeded small games against independent enumeration of every bounded credit vector and omitted-vertex region. It also covers the two-state cycle, negative self-loops, parallel edges, 180-bit weights, adversarial Min choices, forged source/strategy/credits, missing region closure and resource limits. `EnergyGameSdkTest` checks public invocation, certificate verification and cancellation.

## Research relationship

The selected transfer is the finite credit-invariant layer of the [energy-credit manuscript section](https://github.com/openai/math/blob/4eaee87548250b2682affda7d05d947c05554bae/preprints/Deterministic-quasipolynomial-time-mean-payoff-games-September-25-2026/build/source/sections/08-energy-credits.tex). The implementation uses ordinary bounded exact lifting and local certificates; it does not claim to implement the manuscript's complete mean-payoff algorithm.
