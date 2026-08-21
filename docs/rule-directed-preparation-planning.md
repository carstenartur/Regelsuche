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

a partial match can bind `A = x - 1`; the remaining obligation is
`x^3 - 1 = (x - 1) * B`. The first implementation slice solves this in the
bounded univariate integer-polynomial fragment, derives `B = x^2 + x + 1`,
retains a certificate and replays ordinary cancellation.

A second solver exposes a divisor already present in a multiplication tree but
hidden by grouping or operand order:

```text
(b * (a * c)) / a
  -> prepared as (a * (b * c)) / a
  -> b * c
```

A third solver handles a factor that is not a structurally identical AST child
in both terms. It computes an exact greatest common monomial:

```text
x^2 * y + x * z
  -> prepared as x * (x * y) + x * z
  -> x * (x * y + z)
```

The exact fragment and evidence are documented in
[`monomial-common-factor-preparation.md`](monomial-common-factor-preparation.md).

## Information boundary

A preparation solver consumes only:

- the current AST subtree;
- the visible principal rule ID;
- its declared mathematical fragment;
- its local work budget.

It receives no search target, pinned benchmark reference, hidden family label
or post-hoc qualification result. If the principal rule is absent from the
visible inventory, no prepared application is generated.

## Opt-in use

The exact-polynomial path is available through:

```java
TransformationEngine engine =
    new RulePreparationTransformationEngine();
```

The AC engine composes direct and exact-polynomial paths and adds bounded factor
exposure:

```java
TransformationEngine engine =
    new AcNormalizationPreparationTransformationEngine();
```

The monomial engine composes those paths and adds exact common-factor synthesis:

```java
TransformationEngine engine =
    new MonomialCommonFactorPreparationTransformationEngine();
```

All engines support explicit rule selection through their
`withKnowledgePacks(selection)` factories. `AstRewriteTransformationEngine`
remains unchanged, so historical benchmark configurations retain their previous
inventory and output.

## Retained application evidence

`RulePreparationPlanner.PreparedRuleApplication` records original, prepared and
result subtrees; bindings; residual obligation; assumptions; primitive lineage;
solver identity; exact remainder-zero certificate; and balanced work.

`AcNormalizationPreparationSolver.PreparedApplication` additionally records the
flattened factor witnesses, exact structural hashes, selected divisor
occurrence, factor-count budget and an AC multiset certificate.

`MonomialCommonFactorPreparationSolver.PreparedApplication` additionally records
the exact greatest common monomial, both quotient monomials, `A/B/C` bindings,
exact monomial descriptors and the configured/consumed/remaining factor work.

Every search edge keeps all primitive IDs, so a composed frontier move does not
hide mathematical work.

## AC-normalization preparation

The AC solver only flattens `MUL` nodes in the numerator of one division and
asks whether the visible divisor is already one of those factors by exact AST
equality. For `(b * (a * c)) / a`, it retains `[b, a, c]`, deterministically
selects the first `a`, preserves the relative order of the remaining factors
and prepares `a * (b * c)`.

The certificate checks that original and prepared numerators have the same
multiplicative factor multiset before `ast_cancel_division_factor` is replayed.
The solver does not distribute, invent a unit for `a / a`, equate merely
algebraically equivalent factors, assume non-commutative multiplication is AC,
or continue beyond its factor limit. A limit hit is `BUDGET_INCONCLUSIVE`.

## Fail-closed outcomes

The exact-polynomial planner distinguishes:

- `PREPARED`;
- `DIRECT_MATCH_AVAILABLE`;
- `NOT_APPLICABLE`;
- `UNSUPPORTED`;
- `NO_EXACT_QUOTIENT`;
- `BUDGET_INCONCLUSIVE`.

The AC and monomial solvers distinguish:

- `PREPARED`;
- `DIRECT_MATCH_AVAILABLE`;
- `NOT_APPLICABLE`;
- `UNSUPPORTED`;
- `BUDGET_INCONCLUSIVE`;
- `INVALID_CERTIFICATE`.

Unsupported input, missing structure, exhausted work or rejected evidence never
produces a guessed candidate. Failed verification or principal-rule replay
skips the prepared application while preserving direct results and other AST
positions.

## Invocation-local memoization

One transformation invocation may contain the same subtree at several AST
positions. `RulePreparationTransformationEngine` therefore maintains a bounded,
deterministic cache for one `transformWithEvidence` call. Each key binds:

- planner revision and principal rule ID;
- exact recursive AST-structure hash;
- normalized assumption fingerprint;
- deterministic rule-inventory fingerprint;
- preparation-budget identity.

The AST descriptor records node kinds, operators, child order, names, argument
counts and exact floating-point bits using length-prefixed tokens. It does not
rely on pretty-printed text, which may hide associative parentheses. Hashes are
computed once per invocation as a bottom-up Merkle tree.

The standard rule-list constructor uses `RuleInventoryFingerprint`. Pattern
rules bind source, target and recognition profile; every rule binds its class
and public metadata. Retained experiments additionally bind the repository
revision because a changed Java body behind the same class is not
content-addressed by that fingerprint.

Only analyses that consumed solver work occupy cache capacity.
`BUDGET_INCONCLUSIVE` outcomes are never cached. Prepared applications enter the
cache only after independent verification; the visible principal rule is still
replayed for every concrete AST position. Insertion order and oldest-expensive
entry eviction are deterministic, and capacity zero supplies the no-cache
ablation. Metrics retain balanced lookups, hits, misses, entries, evictions,
skipped outcomes and verifications.

## Experiment identity and ablation

A retained experiment enabling preparation binds at least repository revision,
engine and solver IDs, rule-inventory fingerprint, assumption fingerprint, each
solver budget and cache policy. A disabled or capacity-zero variant is required
for a work-reduction claim. Preparation changes mechanical reachability and
work, so it must not rewrite evidence from older configuration identities.

## Current limits

Implemented solvers cover exact univariate integer-polynomial quotient
synthesis, existing-factor exposure modulo scalar multiplication AC, and exact
common-factor synthesis for two positive integer monomials. They do not yet
provide general partial-pattern obligations, declarative schemas for every Java
rule, e-class representative planning, power or square-structure exposure,
rational common-denominator preparation, or bounded local pattern-targeted BFS.
Those remain in #708.
