# Contextual reuse learning under #1026

User-approved direction: repair main first, then implement arithmetic reuse transfer,
learned residual composition (#874), and invariant/induction learning. Main source
`4ac122f3` passed complete CI run `35417755959`, attempt 2, before this work.
The durable measurement repair remains isolated in #1027.

## First deliverable and scope

Learn a typed rewrite together with the unchanged output that makes a new shared
intermediate useful. Do not merely learn the already-known modular exponent law.
A generic learner in `regelsuche-learning` consumes independently checked TRAIN
pairs. It infers one changed output and a newly reused, unchanged sibling output.
Pairwise anti-unification shares bindings across the before/after/context triple.
Application matches two distinct output occurrences without fixing their positions
or requiring the program to have exactly two outputs. Additional outputs survive.

Reuse the existing typed AST, trajectory anti-unifier, lazy sequence matcher,
pattern instantiator and identity codec. The generic learner knows no modpow name,
exponent law or desired target. A caller-supplied, identity-bound proof contract
rechecks every training witness and every concrete application. Matching, scores
and artifact hashes never grant mathematical authority. Bound work and outputs;
resource exhaustion is explicit, not a mathematical failure or a partial binding.

The ordinary JUnit integration uses the existing #1025 composition provider and
proof checker to produce TRAIN witnesses by target-blind bounded search. It loads
application cases only after the learned artifact has been frozen. Its test-only
adapter can inspect private research factories, like the existing proof-contract
tests; no historical #1025 source or evidence is modified. Learning depends on
experiments only in the test configuration, not its production API.

## Frozen inputs and claim boundary

`contextual-reuse-v1/train.json` and `application.json` are committed before the
first new learner execution. No application target form is stored. The original
`research/1026-modpow-reuse-learning` branch and its already exposed experiment
remain untouched. These are public development shapes, not a sealed final test
or independent family-generalization claim.

Controls: unchanged source, complete primitive search, learned contextual rule,
broken-reuse TRAIN, and no-shared-intermediate application. Crucially, a complete
primitive search can already find the optimal output: do not call a tie against
that strong baseline a learned speedup. Report source and selected DAG costs,
primitive search work and learned matching/audit work separately. Success of
structural transfer is distinct from cost superiority or amortization.

## Implementation and verification

1. Freeze source-only TRAIN/application inputs and this plan.
2. Add focused failing tests for local/context abstraction, output order and arity,
   inconsistent bindings, exact literal preservation, domain rejection, training
   rejection, finite work, immutability and deterministic artifact identity.
3. Implement the generic learner using existing components, retaining primitive
   proof receipts and required domain-contract identity in the frozen artifact.
4. Run actual Java-25 tests and old trajectory/matcher regression tests. Then run
   the fixed modular-power comparison; retain every positive and negative row.
5. Publish a separate feature PR with exact execution evidence and honest limits.
   Preserve old results. No default production promotion or blanket speedup claim.

Later slices remain #874 learned residual strategy, Cassini/general recurrence
invariants with induction certificates, and adaptive generation selection. They
are not delivered by merely completing this contextual-reuse component.
