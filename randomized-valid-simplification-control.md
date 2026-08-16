# Deterministic randomized-valid simplification control

The comparative benchmark in #235 now includes a randomized control for the
target-free simplification track.

The control answers a narrow question:

> How much of the retained reference-form reachability is obtained by randomly
> scheduling the same valid production rewrites under the same bounded work
> contract, without target or reference visibility?

It is not an external computer-algebra system and it is not a new mathematical
algorithm.

## Information parity

`randomized-valid-rewrite-control` receives exactly the same internal surface as
the untargeted best-first configuration:

- the same source expression;
- the same default `AstRewriteTransformationEngine` inventory;
- the same `SearchHeuristic` depth, visited-state, expansion and
  candidates-per-state limits;
- no target expression and no pinned reference form;
- the same assumption-discharge contract;
- the same target-free single-output policy: minimum `ExpressionScorer` total,
  then normalized text, then depth.

The random component changes only which frontier state and which valid produced
successors are considered first before the common bounds are exhausted.

## Reproducibility

The production `RandomMonteCarloSearchStrategy` intentionally owns mutable
`java.util.Random` state. Reusing one instance directly would make a second
benchmark run depend on how many random values the first run consumed.

`DeterministicRandomValidRewriteStrategy` therefore creates a fresh delegate for
every case. Its case seed is derived as:

```text
case seed
  = frozen base seed
  XOR first 64 bits of the stable canonical source SHA-256 hash
```

The complete base seed, derivation policy, delegate type and
production-valid-rewrite boundary are included in the configuration's policy
hash. Reusing the same runner, constructing an independent runner and producing
a new retained bundle must therefore result in the same canonical evidence.

The canonical source hash is validated as exactly 64 lowercase hexadecimal
characters before seed derivation. An invalid hash fails closed rather than
silently selecting a fallback random stream.

## Evidence classification

The configuration uses `SystemKind.ABLATION`. It is kept distinct from:

- `REGELSUCHE`, the ordinary untargeted best-first implementation;
- `EXTERNAL_BASELINE`, the pinned native SymPy simplifier;
- target-aware search configurations in another benchmark track.

Every case retains its own selected output, correctness outcome, generated
transformations, explored states, engine invocations and trace hash. Losing
outcomes remain valid benchmark evidence.

## Claim boundary

This control can show how a seeded randomized ordering behaves on the frozen
small algebraic corpus under the declared work limit. It does not establish:

- universal random-search performance;
- superiority or inferiority of Regelsuche as a whole;
- mathematical discovery, proof or novelty;
- runtime or scalability superiority;
- independence from the Regelsuche rule inventory;
- an equality-saturation or external term-rewriting baseline.

The remaining `SIMPLIFICATION_COMPETITION` coverage gap therefore still names a
shared-fragment equality-saturation competitor, a multi-domain corpus and an
independent assumption-aware output validator, but no longer claims that the
randomized-valid control is absent.
