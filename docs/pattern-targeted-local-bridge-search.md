# Pattern-targeted local bridge search

Issue #718 adds the bounded fallback stage that follows direct matching and the
specialized exact preparation solvers from #708.

## Purpose

A visible principal rule is not discarded merely because its left-hand side
does not match the current local AST exactly. The search asks a narrower
question:

```text
Can a short sequence of declared legal local rewrites make this one visible
principal rule concretely applicable?
```

No desired result expression, benchmark reference, family label or post-hoc
score is supplied. The only successful terminal condition is:

1. `PatternMatchAnalyzer` reports a complete exact or theory-aware match for the
   principal rule's declared source pattern; and
2. the concrete principal `PatternRewriteRule` matches and replays on that exact
   retained AST.

## Execution order

The intended product coordinator remains staged:

```text
direct rule application
  -> theory/equivalent-representative recognition
  -> specialized exact obligation solvers
  -> bounded pattern-targeted local bridge search
```

The local bridge is therefore a fallback, not a replacement for exact
polynomial quotient, AC factor exposure, common-monomial, perfect-square or
common-denominator preparation.

## Frozen inputs

`PatternTargetedLocalBridgeSearch` receives:

- one concrete visible `PatternRewriteRule`;
- a finite preparation-rule inventory;
- the local source expression and normalized assumptions;
- an exact repository revision;
- explicit depth, state, transition, primitive-work, AST-size, per-state
  successor and matcher budgets.

The principal rule is rejected if it occurs in the preparation inventory. Every
preparation rule must declare equivalence preservation by construction. The
inventory and principal rule are independently content-addressed with
`RuleInventoryFingerprint`.

The first implementation deliberately accepts only a principal rule with an
explicit pattern schema. It does not infer applicability from a Java class name,
rule ID, example or benchmark.

## Search semantics

The search uses breadth-first layers. A retained positive therefore has minimum
preparation-transition depth inside the frozen search space. Within one layer,
candidates are ordered deterministically by:

1. complete principal match;
2. number of matched pattern nodes;
3. number of consistent bindings;
4. number and lower-bound kind of residual obligations;
5. AST growth;
6. cumulative primitive work;
7. exact structural fingerprint, rule ID and application key.

This ranking changes only which equal-depth candidate is considered first. It
does not turn the search into target-expression best-first search.

State identity contains the exact recursive AST structure and the normalized
assumption fingerprint. Pretty-printed or algebraically canonical text is not
used as the sole key, because formatting can erase grouping relevant to
syntactic applicability. Equal syntax under different assumptions remains two
states.

Generated transitions are counted before duplicate elimination. Composite
transformations consume the complete retained primitive-rule lineage. A reached
cap while unseen legal work may remain yields `BUDGET_INCONCLUSIVE`; only a
fully exhausted frozen closure may yield
`NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE`.

## Evidence and replay

A prepared bridge retains:

- source, terminal prepared and result expressions;
- source, terminal and result assumptions;
- initial and terminal pattern analyses;
- principal rule and preparation-inventory identities;
- every preparation step, application key and primitive rule ID;
- the concrete principal replay;
- configured budgets and balanced work counters;
- a content-addressed certificate.

`verify(...)` replays every preparation step from the frozen inventory,
recomputes assumptions and terminal matching, replays the principal rule and
checks the certificate hash. A stale application key, path, assumption,
inventory, result or certificate fails closed.

## SymPy rule-amplification pilot

The first retained pilot uses the unchanged imported rule
`sympy.trig.pythagorean`:

```text
sin(X)^2 + cos(X)^2 -> 1
```

The preparation inventory contains only the ordinary cancellation rule. The
frozen matrix contains:

- canonical and AC-reordered direct matches;
- one hidden cancellation;
- two hidden cancellations;
- a different-argument near miss.

For example:

```text
((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2
  -> sin(x)^2 + ((cos(x) * b) / b)^2
  -> sin(x)^2 + cos(x)^2
  -> 1
```

The retained lineage is:

```text
ast_cancel_division_factor
ast_cancel_division_factor
sympy.trig.pythagorean
```

and the result preserves `a != 0` and `b != 0`. The different-argument control
must not be classified as Pythagorean after the same cancellations.

Generate the report with:

```bash
./gradlew :regelsuche-experiments:symPyRuleAmplification
```

The task writes deterministic JSON and Markdown under:

```text
regelsuche-experiments/build/reports/sympy-rule-amplification/
```

## Claim boundary

The pilot may show that bounded preparation increases the number of declared
inputs on which one unchanged imported SymPy rule becomes concretely
applicable. It does not establish general superiority over SymPy, completeness,
optimality, external mathematical novelty or safe amplification of rules whose
domain guards are incomplete. Logarithm, root, power, inequality and operator
rules require typed guard hardening before they may enter a normal amplified
profile.
