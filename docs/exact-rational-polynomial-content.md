# Exact rational polynomial content v1

## Purpose

Rational factor synthesis should reuse the exact integer theory rather than add
a floating-point factorizer. The versioned procedure

```text
regelsuche.exact-rational-polynomial-content/v1
```

converts a bounded univariate rational polynomial into one exact scalar and one
primitive integer polynomial:

```text
rational coefficients
  -> common positive denominator
  -> integral coefficients
  -> positive integer content
  -> primitive integer coefficients
  -> leading sign moved into the scalar
  -> exact reconstruction
```

For `[1/2, -3/4, 1/4]` in ascending exponent order, it retains denominator `4`,
integral and primitive coefficients `[2, -3, 1]`, content `1`, and scalar `1/4`.

## Exactness and work

`ExactRationalPolynomial` stores at most 65 canonical coefficients, removes
trailing zeros, and evaluates by exact Horner arithmetic. The normalizer counts
every coefficient visit, GCD, LCM, multiplication, division, sign adjustment,
and reconstruction check before taking the final work snapshot.

It verifies both

```text
integral[i] = signedContent * primitive[i]
source[i]   = scalar * primitive[i]
```

for every coefficient. Potentially oversized products are rejected from
bit-length bounds before multiplication and checked again afterwards. The zero
polynomial is explicit because it has no unique primitive content.

## Budget and outcomes

The budget binds degree, source-coefficient bit length, intermediate bit length,
and arithmetic work. Version-1 ceilings are 64, 8,192, 262,144, and 500,000;
`maxIntermediateBits` must be at least `maxCoefficientBits`.

Outcomes are `NORMALIZED`, `ZERO_POLYNOMIAL`, `DEGREE_LIMIT_EXCEEDED`,
`COEFFICIENT_LIMIT_EXCEEDED`, `INTERMEDIATE_LIMIT_EXCEEDED`, and
`WORK_LIMIT_EXCEEDED`. Budget outcomes do not imply mathematical
irreducibility.

## Evidence and JSON

Each result binds the source coefficient vector, complete budget, normalization
payload when present, work ledger, and SHA-256 certificate. Failure outcomes are
also content-addressed. The top-level evidence constructor is not public.

The stable schema ID is

```text
https://carstenartur.github.io/Regelsuche/schemas/
  regelsuche-exact-rational-polynomial-content-v1.schema.json
```

The strict JSON codec rejects duplicate, unknown, missing, incorrectly typed,
and trailing fields. Runtime construction enforces cross-field budget validity
and work-sum consistency. The independent verifier parses every coefficient as
a canonical reduced `ExactRational`, reruns normalization under the retained
budget, and compares status, payload, work, and certificate.

## Integration boundary

This procedure consumes already exact coefficients. It does not yet preserve
number lexemes in the AST, invoke integer factor synthesis, reassemble rational
factors, or enable a rational search profile. The next layer must bind this
content certificate to the integer synthesis certificate and verify the final
AST reconstruction.

## Verification

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-core -am \
  -Dtest=ExactRationalPolynomialContentNormalizerTest,\
ExactRationalPolynomialContentJsonCodecTest test

mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Claim boundary

The procedure establishes bounded exact denominator clearing and primitive
content extraction. It does not establish complete rational or multivariate
factorization, external novelty, or comparative superiority.
