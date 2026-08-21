# Monomial common-factor preparation

Issue #708 adds bounded preparation solvers between direct AST matching and the
global search frontier. This slice handles a case that associative/commutative
reordering alone cannot solve: a common factor is present mathematically, but
is encoded with different powers or integer coefficients in the two terms.

## Example

The direct rule

```text
A * B + A * C -> A * (B + C)
```

does not match

```text
x^2 * y + x * z
```

because the left term does not contain an outer factor that is structurally
identical to the `x` in the right term. The monomial solver parses both terms in
a bounded exact fragment and computes their greatest common monomial:

```text
left  = x^2 * y
right = x * z
GCD   = x
```

It then constructs the certified preparation

```text
x * (x * y) + x * z
```

and replays the unchanged `ast_factor_common_left` rule to obtain

```text
x * (x * y + z)
```

The retained primitive lineage is:

```text
prepare_monomial_common_factor
ast_factor_common_left
```

## Supported fragment

Each of the two additive terms may contain only:

- positive exact integer coefficients;
- variables;
- positive integer powers of variables;
- multiplication.

The default limits bind the total number of inspected atomic factors, the
largest exponent and the largest intermediate coefficient. Reaching a declared
limit produces `BUDGET_INCONCLUSIVE`, not a mathematical non-match.

Subtraction, negative or fractional coefficients, symbolic exponents,
functions and non-monomial sums inside either term are outside this first
fragment and produce `UNSUPPORTED`.

## Exact unit remainder

The solver may introduce a unit only when exact monomial division requires it.
For example:

```text
x^2 + x
  -> x * x + x * 1
  -> x * (x + 1)
```

This is not arbitrary term invention. The certificate proves that the original
term is the exact product of the GCD and the retained quotient; factoring itself
introduces no non-zero side condition.

## Retained evidence

Each prepared application retains:

- the original, prepared and result subtrees;
- the exact greatest common monomial;
- both exact quotient monomials;
- placeholder bindings `A`, `B` and `C`;
- the residual equations `left = A * B` and `right = A * C`;
- the configured, consumed and remaining factor budget;
- canonical monomial descriptors for both source terms and the GCD;
- a content-addressed certificate;
- both primitive rule IDs.

Verification reparses both original terms under the retained budget, recomputes
the GCD, divides both terms exactly, reconstructs the products, checks every
retained field and finally requires a successful replay through the real
principal rule implementation.

## Fail-closed outcomes

The solver distinguishes:

- `PREPARED`;
- `DIRECT_MATCH_AVAILABLE`;
- `NOT_APPLICABLE`;
- `UNSUPPORTED`;
- `BUDGET_INCONCLUSIVE`;
- `INVALID_CERTIFICATE`.

If the principal rule is hidden, the certificate is invalid, replay fails, or
the fragment cannot decide the case within budget, no prepared transformation
is emitted.

## Opt-in engine

```java
TransformationEngine engine =
    new MonomialCommonFactorPreparationTransformationEngine();
```

This engine composes the existing direct, exact-polynomial and AC-normalization
preparation paths and then adds monomial common-factor synthesis. Existing
engines and historical benchmark identities remain unchanged.

## Claim boundary

This slice establishes bounded exact common-factor synthesis for two positive
integer monomials. It does not establish general polynomial factorization,
multiterm GCD extraction, subtraction handling, rational coefficients, general
AC unification, global reachability or search superiority.
