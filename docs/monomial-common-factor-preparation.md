# Monomial common-factor preparation

Issue #708 places bounded, certificate-carrying preparation between direct AST
matching and the global frontier. This slice handles common factors hidden by
different coefficients or powers, which AC reordering alone cannot expose.

## Example

The direct rule `A * B + A * C -> A * (B + C)` does not match
`x^2 * y + x * z`. In the supported exact monomial fragment the solver derives:

```text
left  = x^2 * y
right = x * z
GCD   = x
```

It prepares `x * (x * y) + x * z` and then replays the unchanged
`ast_factor_common_left` implementation to obtain `x * (x * y + z)`.
The retained primitive lineage is:

```text
prepare_monomial_common_factor
ast_factor_common_left
```

The same mechanism yields
`6 * x^2 * y + 9 * x * z -> (3 * x) * (2 * x * y + 3 * z)`.
It may introduce an exact quotient of one, as in
`x^2 + x -> x * x + x * 1 -> x * (x + 1)`; this unit is certified by exact
monomial division rather than guessed.

## Fragment, limits and evidence

Each of the two additive terms may contain positive exact integer coefficients,
variables, positive integer powers and multiplication. Factor count, exponent
and intermediate coefficient are bounded. A reached limit is
`BUDGET_INCONCLUSIVE`; subtraction, fractions, symbolic exponents, functions or
non-monomial terms are `UNSUPPORTED`.

A prepared application retains the original/prepared/result ASTs, GCD and both
quotients, `A/B/C` bindings, residual equations, balanced work, exact monomial
descriptors, a content hash and both primitive IDs. Verification reparses the
source under the retained budget, recomputes the GCD and quotients, reconstructs
both terms and requires successful replay through the real principal rule.

Outcomes are `PREPARED`, `DIRECT_MATCH_AVAILABLE`, `NOT_APPLICABLE`,
`UNSUPPORTED`, `BUDGET_INCONCLUSIVE` and `INVALID_CERTIFICATE`. Hidden rules,
invalid evidence or failed replay emit no candidate.

## Opt-in and claim boundary

```java
TransformationEngine engine =
    new MonomialCommonFactorPreparationTransformationEngine();
```

The engine composes the existing direct, exact-polynomial and AC-preparation
paths; historical engines and benchmark identities remain unchanged. The claim
is limited to exact GCD extraction for two positive integer monomials. It does
not cover general polynomial factorization, subtraction, rational coefficients,
multiterm GCDs, global reachability or search superiority.
