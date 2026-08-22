# Pattern-targeted SymPy rule amplification

Issue #718 adds the first bounded fallback after the exact preparation solvers
from #708. The fallback does not search for a desired result. Its only terminal
condition is that one explicitly selected visible `PatternRewriteRule` becomes
applicable and that its concrete implementation replays successfully.

## First pilot

The initial external-rule pilot uses the unconditional Pythagorean identity
from the experimental `sympy-trigonometry` pack:

```text
sin(X)^2 + cos(X)^2 -> 1
```

The direct rule does not match:

```text
((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2
```

The local planner uses the frozen preparation inventory, finds two ordinary
factor-cancellation steps and then replays the unchanged SymPy-derived rule:

```text
((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2
  -- ast_cancel_division_factor, a != 0 -->

sin(x)^2 + ((cos(x) * b) / b)^2
  -- ast_cancel_division_factor, b != 0 -->

sin(x)^2 + cos(x)^2
  -- sympy.trig.pythagorean -->

1
```

The emitted search edge retains all three primitive rule IDs and the two
non-zero assumptions. A different-argument near miss such as
`sin(x)^2 + cos(y)^2` is not accepted.

## Execution order

The product-facing pilot engine preserves existing outputs and stages work as:

```text
direct rules
  -> existing exact preparation engines
  -> bounded pattern-targeted local bridge
  -> concrete principal replay
```

The principal rule is removed from the preparation inventory. It can therefore
only appear as the final replayed step, not as a hidden search transition.

## State and work identity

A local state is identified by the exact recursive AST structure plus the
normalized assumption fingerprint. Pretty-printed or algebraically canonical
text is not used as state identity because it can erase grouping relevant to
syntactic applicability.

The retained work ledger includes finite bounds and actual values for depth,
visited states, generated transitions, primitive path work, expression nodes,
successors per state and pattern-match analysis. Reaching a limit without a
verified bridge is `BUDGET_INCONCLUSIVE`; only a completely exhausted frozen
closure can report that no bridge exists in that closure.

A prepared application retains source, prepared and result expressions,
structural SHA-256 fingerprints, every preparation step, assumptions, complete
primitive lineage, principal metadata, work and a content-addressed
certificate. Verification independently replays every preparation application
by its application key and then reruns the concrete principal rule.

## Safety boundary

The first factory enables only `sympy.trig.pythagorean`. It deliberately does
not automatically amplify every rule whose ID starts with `sympy.`. Logarithm,
power, root, rational and matrix rules require explicit domain and guard
contracts before a local preparation search may broaden their effective input
surface.

Algorithmic Java rules without a declarative applicability schema remain
unsupported by this planner. Cross-representation bridges such as scalar
systems to matrices retain their typed relation kinds and are not flattened
into scalar expression patterns.

## Claim boundary

This pilot establishes that one declared SymPy-derived rule can be made
applicable by a shortest bounded local preparation path while preserving
assumptions and replayable provenance. It does not establish global
reachability, universal rule amplification, search superiority, formal proof or
external mathematical novelty.

The next tranche should share one local closure across several guard-safe
principal schemas, add algorithmic applicability schemas and retain a direct vs
prepared amplification matrix per rule and case before selecting a broader
`SAFE_PREPARATION_V1` product default.
