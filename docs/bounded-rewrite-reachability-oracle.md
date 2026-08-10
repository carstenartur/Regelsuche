# Bounded rewrite reachability oracle

`BoundedRewriteReachabilityOracle` is target-aware diagnostic infrastructure for
historical rediscovery and search-space characterization. It performs a
deterministically ordered breadth-first traversal of a frozen
`TransformationEngine` and reports exactly one of three outcomes:

- `REACHABLE` with a shortest retained witness;
- `UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE` only after the finite closure has
  actually been exhausted;
- `BUDGET_INCONCLUSIVE` when an unseen successor is blocked by the declared
  depth or visited-state limit.

This distinction prevents a bounded search miss from being mislabeled as proof
that a transformation is absent from the rewrite graph.

The oracle is deliberately target-aware. Its results may diagnose
representation, inventory, directionality and search-policy gaps, but they are
not evidence of autonomous candidate formation, mathematical novelty or
production-search superiority.

The next rediscovery tranche should bind the oracle to the versioned historical
corpus and compare its complete-closure witness with the ordinary production
search under the same frozen primitive inventory.
