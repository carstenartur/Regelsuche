# Exact rational scalar domain v1

## Purpose

Polynomial and representation procedures must not infer exact mathematical
coefficients from binary floating-point values. The exact rational scalar
domain introduces one versioned boundary:

```text
source lexeme
  -> raw-input and grammar limits
  -> canonical arbitrary-precision rational
  -> canonical value identity
  -> source- and limit-bound parse certificate
  -> strict JSON codec and semantic replay
```

The domain ID is:

```text
regelsuche.exact-rational-scalar/v1
```

This is the scalar foundation for the rational-coefficient remainder of issues
#661 and #748. It does not by itself enable rational polynomial synthesis in the
normal search profile.

## One authoritative arithmetic contract

`de.regelsuche.scalar.ExactRational` is the authoritative exact rational
implementation. It owns canonicalization, equality, hashing, arithmetic and
ordering.

The historical
`de.regelsuche.math.algorithms.equivalence.Rational` API remains available as a
compatibility facade. Its construction and arithmetic delegate to
`ExactRational`; it is not an independent exact-number implementation. The
legacy `fromDouble` method remains an explicitly approximate-input adapter and
must not authorize an exact source-language claim.

`PolynomialNormalizer` also uses `ExactRational` internally. Its conversion from
legacy `NumberExpr.value()` is explicitly isolated as a compatibility boundary;
the normalizer no longer contains a second rational arithmetic implementation.

Construction establishes:

- a strictly positive denominator;
- greatest-common-divisor reduction;
- the unique zero representation `0/1`;
- canonical integer rendering without `/1`.

Addition uses the denominator GCD before forming the common denominator.
Multiplication and division cross-cancel before multiplying large integers.
Undefined operations throw instead of returning a numeric sentinel.

## Accepted source forms

Version 1 accepts only:

- signed integers, such as `-12`;
- explicit fractions of signed integers, such as `6/-8`;
- finite decimal lexemes, such as `0.125` or `-3.50`.

Finite decimals are converted directly from their source characters. For
example, `0.125` becomes exactly `1/8`; no `double`, epsilon comparison or
rounded intermediate value is involved.

The following remain unsupported rather than guessed:

- scientific notation such as `1e-3`;
- repeating-decimal notation such as `0.(3)`;
- `NaN`, infinities and implementation-specific floating-point spellings;
- algebraic, transcendental, interval or approximate coefficients.

A lexically zero denominator is classified before the digit-work budget is
considered. Thus `1/000000` remains `ZERO_DENOMINATOR` even under a smaller
digit limit. No large integer must be constructed to make this decision.

Leading and trailing Unicode whitespace is removed with `String.strip()` only
after the raw character limit has been checked. Non-whitespace control
characters, including NUL, are not discarded and therefore remain unsupported.

## Resource limits

The domain applies finite limits to:

- raw source characters;
- total decimal digits;
- finite-decimal scale.

The raw source-length check runs **before** whitespace removal. Padding cannot
therefore bypass the configured character limit. Evidence retains at most the
configured source-character bound.

Version 1 caps serializable limits at 4,096 source characters, 1,024 digits and
256 decimal places. Callers may choose smaller limits. The decimal-scale limit
may not exceed the selected total-digit limit. Limit exhaustion yields
`LIMIT_EXCEEDED`; it is not evidence that a mathematical value is invalid.

The selected limits are part of every evidence object and every successful
parse certificate.

## Content-addressed identities

All hash inputs use UTF-8. Define the deterministic length-prefix operation as

```text
LP(v1, ..., vn) = concat(utf8Length(vi), ":", vi)
```

where `utf8Length` is the number of UTF-8 bytes, written in base-10 ASCII.
There is no separator beyond each length prefix.

For canonical value text `canonical`:

```text
valueMaterial = LP(
  "regelsuche.exact-rational-scalar/v1.value",
  canonical
)
valueId = "sha256:" + lowerHex(SHA-256(UTF-8(valueMaterial)))
```

The limits material is exactly:

```text
maxLiteralCharacters ":" maxDigits ":" maxDecimalScale
```

For an accepted, stripped source lexeme `source`:

```text
certificateMaterial = LP(
  "regelsuche.exact-rational-scalar/v1.parse",
  limitsMaterial,
  source,
  canonical,
  valueId
)
certificateHash =
  "sha256:" + lowerHex(SHA-256(UTF-8(certificateMaterial)))
```

Canonical test vector using default limits and source `0.50`:

```text
canonicalValue = 1/2
valueMaterial =
  41:regelsuche.exact-rational-scalar/v1.value3:1/2
valueId =
  sha256:287b26bb93278c5925a066707cdc8b3c8cd030b306cbb28c208d90472f08890d
certificateHash =
  sha256:e4a1084a45662f69f73aef170a08f15bd84de7b00f16cc37d73014744c97833c
```

Consequently, `1/2` and `0.50` share a value identity while retaining different
source certificates.

## JSON and semantic verification

The stable schema ID is:

```text
https://carstenartur.github.io/Regelsuche/schemas/
  regelsuche-exact-rational-scalar-v1.schema.json
```

`ExactRationalEvidenceJsonCodec` emits fields in a deterministic order and
rejects:

- blank, oversized or non-object JSON;
- duplicate or unknown fields;
- missing or incorrectly typed fields;
- trailing JSON values;
- invalid cross-field limit combinations;
- evidence that does not pass semantic replay.

`ExactRationalParseEvidence` has no public constructor. For serialized evidence,
`ExactRationalEvidenceVerifier` replays the source under the declared limits and
checks:

- status and detail code;
- canonical reduced value;
- value identity;
- source- and limit-bound certificate.

This semantic verifier is required because JSON Schema can constrain spelling
but cannot prove GCD reduction or recompute hashes. Non-exact outcomes expose no
value or exact hashes. Source-character-limit failures are verified as bounded
fail-closed failure shape because the rejected suffix is deliberately not
retained; other failure outcomes are replayed completely.

The schema rejects noncanonical textual forms such as `-0`, `01` and `1/1`. A
syntactically canonical-looking but reducible value such as `2/4` is rejected by
semantic replay.

## Integration boundary

This tranche deliberately leaves the existing integer polynomial synthesis
profile unchanged. Rational integration must still:

1. preserve exact numeric source lexemes in the AST;
2. derive rational coefficients without `double`;
3. clear denominators and extract content under a versioned certificate;
4. invoke the existing integer synthesizer on the primitive polynomial;
5. reassemble and independently verify the rational result;
6. retain integer-only evidence under its original domain identity.

## Verification

Focused tests:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-core,regelsuche-math-algorithms -am \
  -Dtest=ExactRationalDomainTest,ExactRationalCrossCancellationTest,\
ExactRationalEvidenceJsonCodecTest,RationalExactScalarAdapterTest test
```

Complete repository contract:

```bash
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Claim boundary

This implementation establishes a canonical exact rational value, one
authoritative arithmetic contract and a fail-closed source/evidence boundary. It
does not establish complete rational polynomial factorization, exact
interpretation of arbitrary legacy AST numbers, external mathematical novelty
or superiority over computer-algebra systems.
