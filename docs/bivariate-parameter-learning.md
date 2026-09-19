# Learning product relations between parameters

This is the first integration step for #1026, not a completed DAG-reuse or
historical-discovery claim. It extracts the domain-neutral idea explored on
`research/1026-modpow-reuse-learning` into the existing learner on current main.
That branch's experiment and frozen artifacts remain unchanged.

## Actual change

`PatternGeneralizer` already delegates its observed integer columns to
`ParameterRelationMiner`. The existing one-parameter grammar keeps precedence.
Only when it cannot explain every column does the new fallback try pairs of
varying observed columns. Its grammar is deliberately finite:

```text
A, A2, A*A2, repeated copies of either parameter, constants
```

Every observation must satisfy the proposed relation. Products are compared in
`long` before narrowing, so Java integer wraparound cannot manufacture an exact
product. Parameter assignment is deterministic under map iteration changes.
Constant columns remain constants. At least two varying columns and an observed
nonconstant product are required; a single example is insufficient.

For the observed factor pairs `(3,5)`, `(4,7)`, `(6,11)`, the existing generalizer
can now form the candidate:

```text
modpow(x,A*A2,B) -> modpow(modpow(x,A,B),A2,B)
```

The miner does not know the meaning of `modpow`. The same column relation can
occur in other mathematical domains. This is inference within a supplied
hypothesis grammar, not invention of that grammar or proof of the candidate.
Synthetic test observations are explicitly marked unverified rather than
manufacturing a proof receipt.

## Boundaries and remaining implementation

Agreement on TRAIN never grants mathematical authority. Existing domain,
assumption, exact-validation and promotion gates remain unchanged. In particular,
integer/nonnegative exponent and positive-modulus obligations must be proved by
the consuming modular-power path, not guessed by this numerical miner.

A scalar rewrite alone does not learn that a particular intermediate result is
also needed elsewhere. The next #1026 step must learn that shared-context
condition, preserve arbitrary other outputs, and replay every application through
the existing independent composition verifier. It must distinguish saved search
work from the already optimal output of exhaustive primitive search.

The old modular-power inputs are now development data, not a new untouched
holdout. A fresh transfer study must freeze TRAIN, model and evaluation contract
before use. The original negative/shuffled controls must remain visible.

After contextual transfer, the approved sequence continues with learned residual
composition (#874), then invariant synthesis with an induction certificate
(Cassini and parameterized second-order recurrences). Those capabilities are not
implemented by this prerequisite.

## Verification

Normal Maven/Gradle learning tests discover `ParameterRelationProductTest` and
`PatternGeneralizerProductTest`. Controls cover repeated bindings, constants,
wrong/shuffled observations, integer wraparound, signs/zero, incomplete tables,
deterministic parameter assignment and unchanged one-parameter precedence.

A dependency-free Java-21 run of the actual miner and normalized-node classes
first failed on the missing product relation and then passed 13 focused checks.
This is not a claim of a complete local Java-25/JUnit build. Current-head full CI
and review remain required before merge.
