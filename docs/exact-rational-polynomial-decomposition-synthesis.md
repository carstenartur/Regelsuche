# Exact rational polynomial decomposition synthesis v1

## Purpose

`regelsuche.exact-rational-univariate-quartic-decomposition/v1` connects the
previously separate exact-input, rational-content and integer-decomposition
contracts without reconstructing mathematical coefficients from `double` or a
formatted intermediate expression.

The implemented path is:

```text
source expression
  -> ExactParsedTerm
  -> exact rational univariate polynomial view
  -> exact content normalization
  -> exact scalar × primitive integer polynomial
  -> typed integer quartic decomposition
  -> exact coefficient reassembly
  -> rendered candidate
  -> separate exact parse-and-view replay
```

The operator is deliberately experimental and is not registered in a default
discovery profile.

## Supported fragment

Version 1 accepts one parser-recognized scalar variable and an exact rational
polynomial of degree four. Coefficients may originate from integer literals,
finite decimals or explicit constant divisions, subject to the existing exact
literal, degree, coefficient-bit and arithmetic budgets.

For example,

```text
0.10*x^4 + 0.40
```

is preserved exactly as

```text
[2/5, 0, 0, 0, 1/10]
```

in ascending coefficient order. Content normalization produces

```text
scalar    = 1/10
primitive = [4, 0, 0, 0, 1].
```

The primitive polynomial is passed directly as a
`PolynomialSemanticView.Polynomial` to the bounded integer decomposition
solver. No temporary polynomial source text is generated and no primitive
coefficient crosses the legacy binary-floating-point boundary.

## Typed integer synthesis and reassembly

For every integer candidate with quadratic coefficient triples

```text
[a,b,c]
[d,e,f]
```

the rational layer reconstructs the primitive ascending coefficient vector
through a separate coefficient calculation:

```text
[c*f,
 b*f + c*e,
 a*f + b*e + c*d,
 a*e + b*d,
 a*d].
```

It first requires exact equality with the primitive integer polynomial. It then
multiplies every reconstructed coefficient by the normalized exact scalar and
requires equality with the original rational polynomial.

Only after both checks does the operator render a candidate such as

```text
(1 / 10) *
((x ^ 2 - 2 * x + 2) * (x ^ 2 + 2 * x + 2)).
```

The rendered candidate is parsed again through `parseExactTerm`, analyzed by
the exact rational polynomial view, and compared with the original source
polynomial. This second route verifies the actual user-visible expression rather
than only trusting the internal coefficient objects. It is a separate replay
route through existing exact components, not a claim of implementation-diverse
formal verification.

## Exact source provenance

Mathematically equal input spellings retain distinct source provenance. Thus

```text
0.10*x^4 + 0.40
```

and

```text
(1 / 10)*x^4 + 2 / 5
```

may produce the same transformed expression but intentionally receive different
combined certificates. The certificate binds:

- the versioned rational decomposition method;
- the complete exact-view canonical material, including source-bound literals;
- the rational-content normalization certificate;
- the typed integer candidate certificate and application key;
- the exact normalized scalar;
- both integer factor coefficient triples;
- the rendered candidate;
- the original exact rational coefficient vector.

The combined hash is currently an operator-owned content address, not a newly
published serialized Evidence schema. The source literal evidence and content
normalization evidence retain their own existing replay contracts.

## Sign handling

Content normalization moves a negative primitive leading sign into the exact
scalar. For example,

```text
-0.10*x^4 - 0.40
```

uses scalar `-1/10` and the same positive-leading primitive polynomial as the
positive case. The renderer emits the negative scalar explicitly as

```text
(0 - 1 / 10) * (...)
```

so no approximate negative literal or implicit sign convention is needed.

## Fail-closed outcomes

The operator exposes separate outcomes for:

- source parse failures;
- expressions outside the exact univariate polynomial fragment;
- exceeded view, content or integer-synthesis budgets;
- content normalization failure;
- non-quartic inputs;
- absence of a bounded integer quadratic-by-quadratic decomposition;
- coefficient reassembly mismatch;
- exact candidates that cannot be represented through the current syntax/AST
  boundary.

A candidate is never emitted after a failed coefficient or rendered-expression
replay. Large exact scalars that cannot safely pass through the current parser
and legacy AST companion boundary are rejected rather than silently rounded.

## Discovery integration boundary

The class implements `HypothesisOperator`, but v1 is not added to the default
operator registry. This avoids changing production search branching before the
new capability has held-out utility and cost evidence under #748.

A later activation change must separately measure:

- newly reachable rational decompositions;
- candidate branching and synthesis work;
- reuse through the derived-macro policy;
- interaction with exact-value identity and search quotienting;
- negative and null results on frozen cases.

## Verification

Focused tests:

```bash
./gradlew :regelsuche-core:test \
  --tests de.regelsuche.transform.\
ExactRationalPolynomialDecompositionSynthesisOperatorTest
```

Complete repository verification:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

The tests cover decimal coefficients, explicit fractions, negative scalar
normalization, deterministic certificates, exact search-edge generation and
fail-closed parse/domain/quartic/factorization cases.

## Claim boundary

This contract establishes bounded exact rational decomposition for a declared
univariate quartic fragment and checks emitted expressions through direct
coefficient reconstruction plus a separate exact parse-and-view replay. It does
not establish complete rational factorization, multivariate factorization,
algebraic factors, implementation-diverse proof, default-search utility,
external mathematical novelty or superiority over computer-algebra systems.
