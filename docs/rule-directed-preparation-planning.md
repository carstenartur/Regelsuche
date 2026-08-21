# Rule-directed preparation planning

## Status and claim boundary

The first executable slice of issue #708 adds an opt-in preparation layer for
rules that are structurally close to an AST occurrence but do not directly
match it.

The implemented claim is deliberately narrow:

> Under a bounded planner session, the visible cancellation rule and the exact
> univariate integer-polynomial solver, Regelsuche can derive a certificate-
> carrying local preparation that makes factor cancellation applicable.

This is not a global completeness result, a general partial-unification engine,
an external-novelty claim or evidence that every failed rule match admits a
useful preparation.

## Motivation

The ordinary rewrite boundary is binary:

```text
rule.matches(subtree)
  true  -> apply
  false -> discard
```

For

```text
(x^3 - 1) / (x - 1)
```

the cancellation rule

```text
(A * B) / A -> B    under A != 0
```

does not directly match because the numerator does not syntactically expose
`x - 1` as a factor. Blindly inserting arbitrary zero terms would create an
unbounded search space.

The preparation planner instead uses the almost-matching rule as a constraint:

```text
A = x - 1
x^3 - 1 = A * B
```

The exact polynomial solver determines

```text
B = x^2 + x + 1
```

and retains the multiplication witness

```text
x^3 - 1 = (x - 1) * (x^2 + x + 1) + 0
```

before the ordinary cancellation rule is applied.

## Execution pipeline

`RulePreparingAstRewriteTransformationEngine` is an explicit opt-in engine. It
keeps the existing prepared AST engine as its direct first path and invokes
`RulePreparationPlanner` only when a concrete rule did not directly match a
concrete AST occurrence.

```text
AST occurrence
  -> direct rule match
  -> if direct match failed: preparation schema lookup
  -> exact residual-obligation solver
  -> independently checked multiplication certificate
  -> prepared subtree
  -> ordinary principal rule
  -> composite Transformation
```

For the motivating case the retained evidence contains:

```text
original subtree:
  (x^3 - 1) / (x - 1)

prepared subtree:
  ((x - 1) * (x^2 + x + 1)) / (x - 1)

result subtree:
  x^2 + x + 1

bindings:
  A = x - 1
  B = x^2 + x + 1

residual obligation:
  x^3 - 1 = (x - 1) * (x^2 + x + 1)

assumption:
  x - 1 != 0

primitive lineage:
  prepare_exact_polynomial_factor
  ast_cancel_division_factor
```

The search edge remains identified by the principal cancellation rule. Its
`primitiveRuleIds` expose both actual steps, so a macro-like edge does not hide
its mechanical work.

## Bounded and fail-closed behavior

Every planner session binds:

- a maximum number of residual-solver attempts;
- a maximum number of emitted prepared applications;
- the planner revision;
- the solver revision;
- an assumption signature;
- a rule-inventory hash;
- a deterministic invocation-local cache.

The result distinguishes:

```text
PREPARED
NOT_APPLICABLE
UNSUPPORTED
BUDGET_EXHAUSTED
INVALID_CERTIFICATE
DISABLED
```

The exact polynomial solver refuses non-integer coefficients, multiple
variables, unsupported powers, explicit zero divisors and divisions with a
non-zero remainder. It never proposes a rounded or sampled quotient.

The retained factorization certificate is checked by exact polynomial
multiplication rather than by rerunning the long-division proposal algorithm.

## Historical compatibility

Neither `AstRewriteTransformationEngine` nor
`PreparedAstRewriteTransformationEngine` changes behavior. Existing benchmark
and production identities therefore remain unchanged.

Callers must explicitly select `RulePreparingAstRewriteTransformationEngine`.
A planner-disabled instance projects to exactly the ordered direct output of
the existing prepared backend.

Any benchmark that enables preparation planning needs a new configuration and
inventory/planner identity. The historical 6/7 simplification result remains
retained evidence rather than being rewritten after the fact.

## Current limitations and next slices

The first slice recognizes only the residual factor obligation induced by
`ast_cancel_division_factor` and solves it only for the existing exact
univariate integer-polynomial fragment.

Follow-up work remains for:

1. structured partial-match results for general pattern rules;
2. declarative applicability schemas for algorithmic rules;
3. matching through bounded equivalent representatives;
4. AC, common-factor, power and rational-denominator preparation solvers;
5. a pattern-targeted bounded local rewrite fallback with explicit
   `BUDGET_INCONCLUSIVE` evidence;
6. durable schema-validated preparation evidence in benchmark and workspace
   artifacts.
