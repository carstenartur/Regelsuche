# Exact numeric literal provenance v1

## Purpose

The legacy expression AST stores numeric leaves as `NumberExpr(double)`. That is
sufficient for historical rewrite behavior, but it cannot recover whether a
source coefficient was written as `0.10`, `1/10`, `0001`, or another exact
lexeme after parsing.

`ExpressionParser.parseExactTerm` therefore returns an additive companion object:

```text
source text
  -> ordinary Expr tree
  + parser-issued exact literal occurrences
```

Each occurrence retains:

- the exact `NumberExpr` node instance created for the token;
- start-inclusive and end-exclusive source positions;
- the unmodified integer or finite-decimal lexeme;
- verified `regelsuche.exact-rational-scalar/v1` parse evidence;
- the canonical arbitrary-precision `ExactRational` value.

This is the parser boundary required before rational polynomial synthesis can
consume source coefficients without passing through binary floating point.

## Identity boundary

Literal spelling is deliberately **not** added to `NumberExpr.equals`, AST hash
codes, canonical search identity, or previously issued evidence. A value-equal
node constructed later does not inherit the original token's provenance.

Consumers resolve an occurrence by object identity:

```java
ExactParsedTerm parsed = parser.parseExactTerm("0.10*x + 2");
ExactParsedTerm.LiteralOccurrence occurrence =
    parsed.literalFor(numberNode).orElseThrow();
```

The companion result is immutable and its constructor is package-private.
Occurrence construction is parser-owned. It validates source ranges, node
uniqueness, exact status, source binding, and semantic replay of every retained
literal certificate. An identity-keyed index is built once at construction, so
repeated occurrence resolution remains constant-time.

## Grammar and fail-closed conversion

The current expression grammar creates numeric leaves for unsigned integer and
finite-decimal tokens. A unary minus remains an AST subtraction with a synthetic
zero; only the source token itself receives provenance. An explicit fraction
such as `1 / 4` remains a division AST and retains exact evidence for both leaf
lexemes.

Inside `parseExactTerm`, the exact scalar limits apply before
`Double.parseDouble`. A token is rejected when it:

- is outside the versioned exact-rational literal grammar or budgets;
- starts with a decimal point or has one without a following digit;
- overflows to a non-finite legacy AST value;
- is exact and non-zero but underflows to legacy `0.0`.

The declared maximum decimal scale is 256. Its smallest positive decimal,
`10^-256`, is still non-zero as a `double` and is characterized as a successful
boundary. Scale 257 is rejected by the exact scalar contract before legacy
conversion.

Ordinary binary rounding is not presented as exact AST semantics: `0.10` still
produces the historical `double` leaf, while the companion retains exact `1/10`
for consumers that explicitly select the exact path. The conversion guard only
prevents catastrophic class changes such as finite-to-infinite or nonzero-to-zero.

## Existing parser API and hot-path boundary

`parseTerm` and `parseExactTerm` share the expression grammar and produce the
same ordinary AST for inputs accepted by both methods. They deliberately have
different evidence responsibilities:

- `parseTerm` is the allocation-minimal legacy path used by canonicalization,
  search, rewriting and E-graph construction. It parses finite `double` leaves
  but does not invoke `ExactRationalDomain`, allocate exact values, hash
  certificates or retain literal occurrences.
- `parseExactTerm` is the explicit exact-input boundary. It enforces the
  versioned exact-rational grammar and budgets and returns the companion object.

This separation is semantically important as well as performance-critical.
Legacy callers do not accidentally pay arbitrary-precision and certificate
costs for every visited search state, while exact consumers cannot obtain a
certificate without selecting the fail-closed API. A term accepted only by the
legacy path must never be reinterpreted as exact later.

Callers that need exact coefficients must retain the `ExactParsedTerm`
companion; formatting and reparsing the AST cannot restore source provenance.

Equation- and system-wide provenance are not yet exposed as aggregate objects.
Their existing AST APIs remain unchanged.

## Integration sequence

The exact rational path is intentionally layered:

1. [Exact rational scalar domain v1](exact-rational-scalar-domain.md) defines
   canonical values and source-bound parse certificates.
2. This page defines occurrence-preserving parser provenance beside the legacy
   AST.
3. [Exact rational univariate polynomial view v1](exact-rational-univariate-polynomial-view.md)
   extracts source-bound coefficients without reading legacy `double` values as
   exact mathematics.
4. [Exact rational polynomial content v1](exact-rational-polynomial-content.md)
   clears denominators and extracts a primitive integer polynomial with bounded
   work and replayable Evidence.
5. [Semantische Polynomansicht und Zerlegungssynthese](polynomial-decomposition-synthesis.md)
   provides the typed exact-integer polynomial entry point, so the primitive
   polynomial is not rendered and reparsed through the legacy numeric AST.
6. [Exact rational polynomial decomposition synthesis v1](exact-rational-polynomial-decomposition-synthesis.md)
   binds the view material, content certificate and integer-synthesis
   certificate, reapplies the exact scalar, verifies the rendered rational
   expression through exact replay, and emits candidates only through the
   explicitly selected experimental operator.

No layer reconstructs exact coefficients from formatted `double` values or
silently reinterprets historical search identities. Default-profile activation
remains a separate held-out utility and cost decision.

## Verification

Focused checkout test:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-core -am \
  -Dtest=ExpressionParserExactLiteralTest,ExpressionParserTest test
```

Full repository contract, including the parser-sensitive canonicalization and
E-graph JMH ratchets:

```bash
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Claim boundary

This layer preserves and verifies exact numeric source tokens alongside the
legacy AST without imposing exact-evidence work on ordinary search parsing. It
does not itself extract polynomial coefficients, factor rational polynomials,
alter search identity, infer exactness for programmatically created `NumberExpr`
nodes, or authorize a default rational-synthesis profile.
