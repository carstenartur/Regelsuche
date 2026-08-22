# Bounded reachability oracle

`BoundedReachabilityOracle` is the first executable slice of the reachability
atlas tracked in [issue #620](https://github.com/carstenartur/Regelsuche/issues/620).

## Purpose

The oracle answers one diagnostic question:

> Is the visible target reachable through the exact directed transformations of
> a declared `TransformationEngine` inside a finite depth and primitive-work
> closure?

The target is an input to the oracle. The result is therefore **not autonomous
discovery evidence**. It may diagnose representation and search behavior, but
it cannot count as target-blind candidate formation or mathematical novelty.

## State and path identity

The initial implementation deliberately uses the parser's deterministic
formatted syntax rather than adding another algebraic equality oracle. A search
state is qualified by:

- formatted expression syntax;
- normalized cumulative assumptions;
- path depth;
- cumulative primitive rewrite work.

Expression plus assumptions define the mathematical state key. Depth and
primitive work are retained as bounded non-dominated labels. This matters when
one macro edge is shallow but expands to more primitive steps than a longer
ordinary path.

The public API does not trust the separately supplied fingerprint of an
`AssumptionSignature`. It rebuilds the normalized signature from the retained
assumption expressions at the boundary. A malformed caller-created record
therefore cannot collapse otherwise distinct assumption contexts.

Every retained transformation preserves:

- the visible rule ID;
- the complete `primitiveRuleIds` lineage;
- the resulting expression;
- cumulative normalized assumptions;
- a deterministic state and edge identity;
- its enqueue, dominance, primitive-bound or state-limit disposition.

## Bounds and terminal statuses

Two limits define the mathematical closure:

- maximum edge depth;
- maximum primitive work along one path.

Reaching either boundary completes that part of the declared finite closure. It
does not by itself make the result inconclusive. Candidate primitive work is
first accumulated in a wider integer domain and compared with the configured
bound. Even a near-`Integer.MAX_VALUE` budget therefore produces an ordinary
`OUTSIDE_PRIMITIVE_WORK_BOUND` edge instead of an arithmetic exception.

Two separate ceilings limit the execution:

- maximum retained states;
- maximum generated transitions.

Hitting either execution ceiling means some in-bound work was omitted, so the
result is `BUDGET_INCONCLUSIVE`.

The terminal statuses are:

- `REACHABLE`: a witness uses no assumption beyond the declared initial set;
- `REACHABLE_ONLY_WITH_ADDITIONAL_ASSUMPTIONS`: the complete declared closure
  contains a target witness, but every retained target witness requires at
  least one additional normalized assumption;
- `UNREACHABLE_IN_COMPLETE_BOUNDED_CLOSURE`: the exact declared finite closure
  was exhausted without a target;
- `BUDGET_INCONCLUSIVE`: a mechanical execution ceiling was reached;
- `TECHNICAL_FAILURE`: the engine failed or produced an invalid expression.

A conditional witness found before a later execution ceiling remains visible,
but the terminal status stays `BUDGET_INCONCLUSIVE`; the oracle does not infer
that no assumption-free path exists from an incomplete run.

## Deterministic shortest witness

The frontier is ordered first by cumulative primitive work and then by edge
depth, assumption burden, expression and stable identity. For each
expression/assumption key, all non-dominated `(depth, primitive work)` labels are
retained. The selected witness therefore minimizes primitive rewrite work,
while deterministic tie-breaking keeps repeated runs stable.

## Work accounting

The retained ledger balances all generated transitions as exactly one of:

```text
enqueued
+ dominated by an existing label
+ outside the primitive-work closure
+ rejected because the visited-state ceiling was reached
```

It separately records expanded and discovered states, superseded labels,
boundary states, maximum frontier size, and whether either execution ceiling
was reached.

## Current scope

This slice does not yet:

- prove endpoint equivalence independently;
- classify representation support;
- distinguish direction, matcher and canonicalization failures by itself;
- compare the oracle witness against a production-search frontier;
- retain witness-prefix pruning ranks;
- serialize the result as the final atlas schema;
- claim global unreachability outside the declared finite closure.

Those layers remain explicit follow-ups in #620. The immediate next comparison
is to run the ordinary production strategies under matched primitive work and
record where an oracle witness prefix is generated, pruned or starved.
