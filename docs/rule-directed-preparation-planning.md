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

A second preparation solver handles a different case without polynomial
factorization. If the required divisor already occurs in a multiplication tree
but is hidden by grouping or operand order, associative/commutative
normalization can expose it directly. For example:

```text
(b * (a * c)) / a
  -> prepared as (a * (b * c)) / a
  -> b * c
```

No factor is invented: the solver retains and checks the exact multiset of
existing factors before the ordinary cancellation implementation is replayed.

## Information boundary

A preparation solver consumes only:

- the current AST subtree;
- the visible principal rule ID;
- its declared mathematical fragment;
- its local work budget.

It receives no search target, pinned benchmark reference, hidden family label
or post-hoc qualification result. If the cancellation rule is absent from the
visible rule inventory, no prepared application is generated.

## Opt-in use

The exact-polynomial preparation path remains available through:

```java
TransformationEngine engine =
    new RulePreparationTransformationEngine();
```

The AC-normalization engine composes the existing direct and exact-polynomial
paths and then adds bounded factor exposure:

```java
TransformationEngine engine =
    new AcNormalizationPreparationTransformationEngine();
```

Both engines also support an explicit rule selection through their
`withKnowledgePacks(selection)` factories.

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

`AcNormalizationPreparationSolver.PreparedApplication` additionally records:

- the flattened original and prepared factor witnesses;
- exact structural hashes for every factor, including duplicate factors;
- the deterministically selected divisor occurrence;
- the factor-count budget and inspected work;
- an AC multiset certificate independent of the later cancellation replay.

The transformation edge keeps both primitive IDs, so treating a composed
operation as one frontier move does not hide its mathematical work.

## AC-normalization preparation

The AC solver is deliberately narrower than a general normalizer. It only
flattens `MUL` nodes in the numerator of one division. It then asks whether the
visible divisor is already one of those factors using exact AST equality.

For

```text
(b * (a * c)) / a
```

the retained factor sequence is:

```text
[b, a, c]
```

The first matching `a` is selected deterministically, the remaining factors
retain their original relative order, and the prepared numerator is:

```text
a * (b * c)
```

The certificate checks that the original and prepared numerators have the same
multiplicative factor multiset. Only then does the engine replay
`ast_cancel_division_factor` on the prepared subtree. If replay does not produce
the expected result and assumptions, the candidate is discarded.

The solver does not:

- distribute through addition or subtraction;
- invent a unit factor for `a / a`;
- infer algebraically equivalent but structurally different factors;
- treat multiplication in an undeclared non-commutative domain as AC;
- continue after its configured factor limit is exhausted.

A factor-limit hit is `BUDGET_INCONCLUSIVE`, never a proven non-match.

## Fail-closed outcomes

The exact-polynomial planner distinguishes:

- `PREPARED`;
- `DIRECT_MATCH_AVAILABLE`;
- `NOT_APPLICABLE`;
- `UNSUPPORTED`;
- `NO_EXACT_QUOTIENT`;
- `BUDGET_INCONCLUSIVE`.

The AC solver distinguishes:

- `PREPARED`;
- `DIRECT_MATCH_AVAILABLE`;
- `NOT_APPLICABLE`;
- `UNSUPPORTED`;
- `BUDGET_INCONCLUSIVE`;
- `INVALID_CERTIFICATE`.

Unsupported input, a missing factor, an explicit zero divisor, exhausted work
or a rejected certificate never produces a guessed candidate. A prepared
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
- exact recursive AST-structure hash;
- normalized assumption fingerprint;
- deterministic rule-inventory fingerprint;
- preparation-budget identity.

The subtree descriptor records node kinds, operators, child order, variable and
function names, argument counts and exact floating-point number bits using
length-prefixed tokens before hashing. It does not rely on pretty-printed text:
the formatter may intentionally hide associative parentheses. Algebraically
equivalent or identically formatted but structurally different occurrences may
require different bindings and retained evidence; they are analyzed
independently.

Structural hashes are computed once per invocation as a bottom-up Merkle tree.
Each AST occurrence contributes one node descriptor and refers to its child
hashes, so fingerprint construction is linear in the number of AST nodes rather
than repeatedly traversing every subtree.

The standard rule-list constructor uses `RuleInventoryFingerprint`. Pattern
rules bind their source, target and recognition profile; every rule binds its
implementation class and public execution metadata. A changed Java body behind
the same implementation class is not content-addressed by this fingerprint, so
retained experiments must additionally bind the repository revision. Callers
that inject a custom direct engine must provide or accept an explicit ID-only
inventory hash.

Only analyses that consumed residual-solver work are retained. Cheap decisions
such as “this node is not a division” remain visible in the metrics but cannot
occupy cache capacity or evict a verified quotient. `BUDGET_INCONCLUSIVE`
results are also never retained: a budget-limited non-result must not become a
semantic negative fact.

Prepared applications enter the cache only after independent verification. A
cache hit therefore reuses already verified formation evidence without
rerunning the exact quotient proposal or its verification. The visible
principal rule is still replayed for each concrete AST position before a
transformation is emitted.

Insertion order and oldest-expensive-entry eviction are deterministic. Cache
capacity is configurable and may be set to zero for an exact no-cache ablation.
`transformWithEvidence` returns balanced cache metrics:

```text
lookups = hits + misses
retained entries
oldest expensive-entry evictions
skipped budget-inconclusive results
skipped zero-solver-work decisions
prepared-application verifications
skipped unverifiable applications
```

The cache is intentionally invocation-local. It cannot leak candidates between
experiments with different assumptions, inventories or configuration
identities, and it requires no invalidation protocol beyond the retained key.

## Experiment identity and ablation

A benchmark or retained experiment that enables preparation must bind at least
the repository revision, engine and solver IDs, rule-inventory fingerprint,
normalized assumption fingerprint, each solver budget and any cache-capacity
policy in its configuration identity. The disabled or capacity-zero variant is
the required direct ablation for a work-reduction claim.

Preparation and memoization change mechanical reachability and work, not the
historical rule inventory. They therefore must never be used to rewrite evidence
from configurations that did not declare these execution policies.

## Current limits

The implemented solvers cover exact univariate integer-polynomial quotient
synthesis and existing-factor exposure modulo scalar multiplication AC. They do
not yet provide general partial-pattern obligations, e-class representative
planning, monomial/common-factor synthesis, power or square-structure exposure,
rational common-denominator preparation, or bounded local pattern-targeted
BFS. Those remain in #708.
