# Exact rational univariate polynomial view v1

## Purpose

`regelsuche.exact-rational-univariate-polynomial-view/v1` converts one
parser-issued [`ExactParsedTerm`](exact-number-literal-provenance.md) into a
bounded polynomial over canonical `ExactRational` coefficients.

The decisive boundary is:

```text
numeric AST leaf
  -> identity lookup in ExactParsedTerm
  -> verified source-bound ExactRational
  -> polynomial arithmetic
```

The implementation never reads `NumberExpr.value()` to authorize an exact
coefficient. The `double` leaf remains available only for historical syntax and
rewrite consumers.

## Supported fragment

Version 1 supports one scalar variable and the following operations:

- exact integer and finite-decimal source tokens;
- explicit fractions represented by division of exact constant subexpressions;
- addition and subtraction;
- commutative multiplication;
- division by an exact non-zero constant polynomial;
- exact non-negative integer powers;
- parser unary minus through its existing synthetic-zero subtraction shape.

For example,

```text
0.10*x^4 - (3/4)*x^2 + 2
```

becomes the ascending coefficient vector

```text
[2, 0, -3/4, 0, 1/10].
```

Likewise,

```text
(x + 1/2)^2 / 2
```

becomes

```text
[1/8, 1/2, 1/2].
```

## Explicit rejection

The view fails closed for:

- more than one variable;
- function applications and other non-polynomial AST kinds;
- division by zero or by a variable-bearing expression;
- negative, fractional, symbolic or provenance-free exponents;
- numeric leaves without parser-issued exact source provenance;
- exceeded degree, coefficient-bit, visited-node or arithmetic budgets.

Matrix and operator products are not scalar commutative coefficients. Composite
functions are not silently renamed to substitution variables in this univariate
contract; the existing integer `PolynomialSemanticView` remains the separate
structural-atom path for its declared domain.

## Budgets and work

The public v1 ceilings are:

| Dimension | Ceiling | Default |
| --- | ---: | ---: |
| Degree | 64 | 16 |
| Coefficient numerator/denominator bits | 8,192 | 4,096 |
| Visited AST nodes | 4,096 | 512 |
| Exact arithmetic operations | 100,000 | 10,000 |

Every node visit and coefficient operation is counted before execution. A
budget failure retains the exact source-literal bindings and completed work but
exposes no partial polynomial or variable.

## Canonical material

A successful analysis retains:

- the view ID and complete budget;
- original source text;
- the single variable name or an empty name for a constant;
- the canonical ascending coefficient vector;
- every literal range, lexeme, canonical value, value ID and parse certificate;
- visited-node and exact-arithmetic counts.

`canonicalMaterial()` length-prefixes all top-level fields by UTF-8 byte length.
It is the deterministic input for the next combined synthesis certificate; it
is not yet a standalone serialized Evidence contract.

## Integration sequence

This view closes the gap between parser provenance and rational content
normalization:

```text
ExactParsedTerm
  -> exact univariate polynomial view
  -> exact rational content normalization
  -> primitive integer polynomial
  -> existing typed integer decomposition synthesis
  -> exact scalar reassembly
  -> independently reconstructed rational candidate
```

The next layer must add a typed entry point to the existing integer quartic
synthesizer instead of rendering the primitive polynomial to text and reparsing
it through `double`. It must bind this view material, the content certificate,
the integer synthesis certificate and the final transformed expression.

## Verification

Focused checkout test:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-core -am \
  -Dtest=ExactRationalUnivariatePolynomialViewTest test
```

Complete repository contract:

```bash
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Claim boundary

This layer establishes exact, bounded extraction of one univariate rational
polynomial from parser-issued source provenance. It does not establish general
polynomial recognition, multivariate or non-commutative semantics, complete
factorization, a rational search edge, held-out utility or production-profile
activation.
