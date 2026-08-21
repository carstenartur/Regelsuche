# Perfect-square structure preparation

Issue #708 places bounded, certificate-carrying preparation between direct AST
matching and the global search frontier. This slice follows the shared evidence,
work-accounting and fail-closed contract in
[`rule-directed-preparation-planning.md`](rule-directed-preparation-planning.md).
It exposes perfect-square structure that is mathematically present in two
monomials but not syntactically visible to the existing rule

```text
A^2 - B^2 -> (A - B) * (A + B)
```

## Example

For

```text
4 * x^4 * y^2 - 9 * z^2
```

the exact monomial parser derives the roots

```text
A = 2 * x^2 * y
B = 3 * z
```

and prepares

```text
(2 * x^2 * y)^2 - (3 * z)^2
```

The unchanged `ast_square_difference_factor` implementation is then replayed on
the retained AST to produce

```text
(2 * x^2 * y - 3 * z) * (2 * x^2 * y + 3 * z)
```

The primitive lineage remains explicit:

```text
prepare_exact_monomial_square_structure
ast_square_difference_factor
```

## Fragment and evidence

Both subtraction operands must be positive exact integer monomials. Their
coefficients must be perfect squares and every variable exponent must be even.
The shared factor, exponent and safely representable coefficient bounds from the
monomial preparation layer apply. A reached bound is `BUDGET_INCONCLUSIVE`;
unsupported syntax is `UNSUPPORTED`; a supported non-square is a conclusive
`NOT_APPLICABLE` result.

The application retains source/prepared/result ASTs, both exact roots, `A/B`
bindings, residual square equations, balanced work, monomial descriptors and a
content-addressed certificate. Verification reparses both terms, recomputes the
roots, squares them back to the originals and requires exact AST replay through
the real principal rule. No assumptions are introduced.

## Opt-in and claim boundary

```java
TransformationEngine engine =
    new PerfectSquareStructurePreparationTransformationEngine();
```

The engine composes all earlier preparation paths. Historical engines and
benchmark identities remain unchanged. The claim is limited to exact square
exposure for two positive integer monomials; it does not establish general
factorization, arbitrary root extraction, polynomial completeness, global
reachability or search superiority.
