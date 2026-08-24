# Exact rational scalar domain v1

## Contract

`regelsuche.exact-rational-scalar/v1` is the exact coefficient boundary for
issues #661 and #748:

```text
source lexeme -> bounded parser -> canonical ExactRational
              -> value identity -> source certificate -> semantic replay
```

`ExactRational` is the single arithmetic authority. It normalizes denominator
sign and GCD, represents zero as `0/1`, and provides exact arithmetic with
cross-cancellation. The historical mathematical-algorithms `Rational` type is a
compatibility facade; its legacy `fromDouble` entry point is approximate input
and cannot authorize an exact source-language claim. `PolynomialNormalizer`
also uses `ExactRational` internally, while its conversion from an existing
`NumberExpr.value()` remains an explicit legacy boundary.

## Accepted syntax and limits

Version 1 accepts signed integers, integer fractions, and finite decimals.
Decimals are interpreted from source characters (`0.125` is exactly `1/8`).
Scientific and repeating notation, `NaN`, infinities, algebraic numbers, and
approximate values are unsupported.

Raw length is checked before `String.strip()`, so whitespace cannot bypass the
limit and NUL is not discarded. A lexical zero denominator is classified before
digit-budget work. Serializable maxima are 4,096 source characters, 1,024
digits, and 256 decimal places; callers may select smaller consistent limits.

## Content identities

All hash material is UTF-8. For each string `v`, `LP(v)` is
`utf8ByteLength(v) + ":" + v`; several values are concatenated without another
separator.

```text
valueId = sha256(LP(
  "regelsuche.exact-rational-scalar/v1.value",
  canonicalValue))

certificateHash = sha256(LP(
  "regelsuche.exact-rational-scalar/v1.parse",
  maxLiteralCharacters:maxDigits:maxDecimalScale,
  strippedSource,
  canonicalValue,
  valueId))
```

Hashes are lowercase and prefixed `sha256:`. Test vector for default limits and
source `0.50`:

```text
canonicalValue = 1/2
valueId = sha256:287b26bb93278c5925a066707cdc8b3c8cd030b306cbb28c208d90472f08890d
certificateHash = sha256:e4a1084a45662f69f73aef170a08f15bd84de7b00f16cc37d73014744c97833c
```

Equivalent lexemes share `valueId` but retain distinct certificates.

## JSON and replay

The stable schema is
`https://carstenartur.github.io/Regelsuche/schemas/regelsuche-exact-rational-scalar-v1.schema.json`.
The strict codec rejects oversized, duplicate, unknown, missing, mistyped, and
trailing JSON. Evidence has no public constructor. The verifier replays source,
limits, canonical reduction, value ID, and certificate. Non-exact outcomes
expose no exact value or hashes; a truncated raw-length failure is verified only
as bounded fail-closed failure shape.

## Integration boundary

This slice does not reinterpret existing AST numbers or enable rational factor
synthesis. The next layer must preserve exact number lexemes, clear denominators,
bind the integer synthesis certificate, and verify final AST reconstruction.

## Verification

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-core,regelsuche-math-algorithms -am \
  -Dtest=ExactRationalDomainTest,ExactRationalCrossCancellationTest,\
ExactRationalEvidenceJsonCodecTest,RationalExactScalarAdapterTest test

mvn --batch-mode --no-transfer-progress -Pfull verify
```

This contract establishes exact rational identity and replayable parse evidence;
it does not establish complete factorization, external novelty, or comparative
superiority.
