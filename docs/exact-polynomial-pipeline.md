# Exact polynomial pipeline

**Status: experimental stacked integration, 25 August 2026**

Regelsuche now develops the exact polynomial path as a sequence of explicit,
independently verifiable boundaries instead of extending the legacy
`NumberExpr(double)` interpretation until it appears exact.

```text
source text
  -> parser-issued exact literal provenance
  -> exact rational univariate polynomial view
  -> exact content normalization
  -> typed primitive integer polynomial
  -> bounded integer decomposition synthesis
  -> exact rational reassembly
  -> rendered-candidate replay
  -> search transformation with combined certificate
```

## 1. Exact scalar domain

`ExactRational` is the canonical arbitrary-precision scalar contract. Integers,
finite decimals and explicit integer fractions are interpreted from source
characters rather than through binary floating point. Every denominator is
positive, numerator and denominator are reduced, and zero is canonical.

See [Exact rational scalar domain](exact-rational-scalar-domain.md).

## 2. Literal provenance beside the legacy AST

`ExpressionParser.parseExactTerm` returns the ordinary syntax tree together
with parser-owned occurrences. Each occurrence binds the exact source range,
lexeme, value, value identifier and parse certificate to the concrete
`NumberExpr` instance created for that token.

The ordinary `parseTerm` route intentionally remains the allocation-minimal
legacy route used by existing search hot paths. Exact evidence is opt-in and
cannot silently add arbitrary-precision and certificate work to every parse.

See [Exact numeric literal provenance](exact-number-literal-provenance.md).

## 3. Exact rational polynomial view

`ExactRationalUnivariatePolynomialView` consumes the parser companion, never a
formatted `double`, and extracts a bounded polynomial with ascending canonical
`ExactRational` coefficients. Unsupported functions, multiple variables,
variable divisors, unsupported exponents and exceeded degree/work/bit budgets
fail closed.

See [Exact rational univariate polynomial view](exact-rational-univariate-polynomial-view.md).

## 4. Content normalization

`ExactRationalPolynomialContentNormalizer` computes

```text
P(x) = scalar * primitiveIntegerPolynomial(x)
```

by exact denominator clearing, integer-content extraction and sign
normalization. It reassembles both the integral and rational coefficients,
retains an arithmetic work ledger and issues replayable evidence.

See [Exact rational polynomial content](exact-rational-polynomial-content.md).

## 5. Typed integer synthesis

The existing bounded quartic decomposition now has a typed entry point that
accepts `PolynomialSemanticView.Polynomial`. A caller can therefore pass the
primitive `BigInteger` coefficient map directly into the solver. No temporary
expression is rendered and no coefficient is reconstructed through
`NumberExpr(double)`.

See [Polynomial decomposition synthesis](polynomial-decomposition-synthesis.md).

## 6. Rational decomposition and replay

`ExactRationalPolynomialDecompositionSynthesisOperator` composes the preceding
contracts for one exact univariate quartic:

1. extract exact rational coefficients from source provenance;
2. normalize exact content;
3. invoke typed integer quadratic-by-quadratic synthesis;
4. convolve the returned factors and require the primitive coefficients;
5. multiply by the exact scalar and require the original rational coefficients;
6. render only integers and explicit fractions;
7. parse and extract the rendered expression again through the exact path;
8. emit a transformation only when the replayed polynomial equals the source.

The combined certificate binds the exact-view material, content certificate,
integer-synthesis certificate, scalar, factor coefficients and rendered
expression.

See [Exact rational polynomial decomposition](exact-rational-polynomial-decomposition.md).

## Failure semantics

Every boundary has typed failure states. A missing literal occurrence,
approximate reconstruction, domain mismatch, exceeded budget, failed
coefficient convolution or non-representable output produces no search edge.
There is no fallback from the exact route to formatted binary floating point.

## Activation boundary

The rational decomposition operator is deliberately **not** part of a default
discovery profile while the stacked integration is under review. Activation
requires all predecessor PRs, focused tests, the checkout-owned `ciCheck`, and
the complete Maven/Docker contract to be green. A later utility experiment must
compare the operator with no-synthesis and derived-cache profiles under matched
canonical work before any stronger default-policy claim.

## Claim boundary

This pipeline establishes a bounded exact route from source rational literals
to verified rational quartic factors. It does not establish complete polynomial
factorization, algebraic-number support, multivariate rational decomposition,
external mathematical novelty, or universal search superiority.
