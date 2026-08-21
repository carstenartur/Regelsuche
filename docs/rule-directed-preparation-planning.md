# Rule-directed preparation planning

Issue #708 introduces an opt-in preparation layer between direct AST matching
and the global search frontier.

## Motivation

A direct rewrite currently has a binary boundary:

```text
rule.matches(subtree) -> apply or discard
```

For structurally close rules this throws away useful information. Given the
cancellation schema

```text
(A * B) / A -> B    under A != 0
```

and the input

```text
(x^3 - 1) / (x - 1)
```

a partial match can already bind `A = x - 1`. The remaining obligation is

```text
x^3 - 1 = (x - 1) * B
```

The first implementation slice solves only this exact obligation in the
bounded univariate integer-polynomial fragment. It derives
`B = x^2 + x + 1`, retains a factorization certificate and then exposes the
ordinary cancellation rule as the principal step.

## Information boundary

The planner consumes only:

- the current AST subtree;
- the visible principal rule ID;
- the declared exact-polynomial solver;
- its local work budget.

It receives no search target, pinned benchmark reference, hidden family label
or post-hoc qualification result. If the cancellation rule is absent from the
visible rule inventory, no prepared application is generated.

## Opt-in use

```java
TransformationEngine engine =
    new RulePreparationTransformationEngine();
```

or with an explicit rule selection:

```java
TransformationEngine engine =
    RulePreparationTransformationEngine.withKnowledgePacks(selection);
```

`AstRewriteTransformationEngine` remains unchanged. Historical benchmark
configurations therefore retain their previous rule inventory and output.

## Retained application evidence

`RulePreparationPlanner.PreparedRuleApplication` records:

- original, prepared and result subtrees;
- bindings `A` and `B`;
- the residual exact-factor obligation;
- the non-zero side condition;
- preparation and principal primitive rule IDs;
- solver identity and exact remainder-zero certificate;
- balanced configured, consumed and remaining solver work.

The transformation edge keeps both primitive IDs, so treating the composed
operation as one frontier move does not hide its mathematical work.

## Fail-closed outcomes

The planner distinguishes:

- `PREPARED`;
- `DIRECT_MATCH_AVAILABLE`;
- `NOT_APPLICABLE`;
- `UNSUPPORTED`;
- `NO_EXACT_QUOTIENT`;
- `BUDGET_INCONCLUSIVE`.

Unsupported multivariate input, a non-exact quotient, an explicit zero divisor
or exhausted solver work never produces a guessed candidate. A prepared
application that fails independent verification is skipped while direct
results and other AST positions remain available.

## Invocation-local memoization

One transformation invocation may contain the same subtree at several AST
positions. Repeating the same residual solver call at each occurrence adds no
mathematical information. `RulePreparationTransformationEngine` therefore
maintains a bounded deterministic cache for the duration of one
`transformWithEvidence` call.

Each key binds:

- planner revision;
- principal rule ID;
- exact formatted AST-subtree hash;
- normalized assumption fingerprint;
- deterministic rule-inventory fingerprint;
- preparation-budget identity.

The subtree key deliberately preserves AST structure rather than using a
broader algebraic canonical hash. Algebraically equivalent but syntactically
different occurrences may require different bindings and retained evidence;
they are therefore analyzed independently.

The standard rule-list constructor uses `RuleInventoryFingerprint`, so changes
to rule implementations, patterns, recognition profiles, descriptors or pack
metadata invalidate the cache identity. Callers that inject a custom direct
engine must provide or accept an explicit ID-only inventory hash.

Insertion order and oldest-entry eviction are deterministic. Cache capacity is
configurable and may be set to zero for an exact no-cache ablation.
`BUDGET_INCONCLUSIVE` results are never retained: a budget-limited non-result
must not become a semantic negative fact.

Prepared applications enter the cache only after independent verification. A
cache hit therefore reuses already verified formation evidence without
rerunning the exact quotient proposal or its verification. The visible
principal rule is still replayed for each concrete AST position before a
transformation is emitted.

`transformWithEvidence` returns balanced cache metrics:

```text
lookups = hits + misses
retained entries
oldest-entry evictions
skipped budget-inconclusive results
prepared-application verifications
skipped unverifiable applications
```

The cache is intentionally invocation-local. It cannot leak candidates between
experiments with different assumptions, inventories or configuration
identities, and it requires no invalidation protocol beyond the retained key.

## Current limits

This first slice is deliberately narrow. It does not yet provide general
partial-pattern obligations, AC/e-class representative planning, other
preparation solvers or bounded local pattern-targeted BFS. Those remain in
#708 after this executable foundation is reviewed and measured.
