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
and cannot authorize an exact source-language claim. `PolynomialNormalizer` and
bounded matcher arithmetic also use `ExactRational` rather than maintaining a
second coefficient model.

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

## Legacy numeric migration boundary

The ordinary syntax AST is still `NumberExpr(double)`. Code that receives only
such an already-created leaf therefore cannot recover parser source precision.
To avoid several subtly different adapters during the migration,
`ExactRationalDomain` defines one explicit shortest-decimal bridge:

- `legacyDecimalValue(double)` accepts only finite values and interprets the
  existing leaf via `BigDecimal.valueOf(double)` semantics;
- `exactLegacyDecimalDouble(ExactRational)` returns a Double value only if that
  same shortest-decimal interpretation round-trips to the identical rational.

These methods do **not** certify the original source text and do not turn the
result of a floating-point calculation into exact mathematical evidence. A
consumer holding `ExactParsedTerm` must use its parser-issued literal evidence
instead. Nonterminating rationals, overflow and rounded return projections fail
closed. The adapter exists only until exact scalar identity has crossed the
remaining syntax/value boundaries under #661.

## Integration boundary

This slice still does not make `NumberExpr` the authoritative exact scalar or
permit approximate values to authorize exact equality. Matcher inference and
canonicalization may use the shared legacy bridge only after accepting that the
source precision could already have been lost. The next layers must migrate
semantic value identity, numeric patterns, caches, E-Graph insertion,
serialization and remaining numeric producers to explicit exact/approximate
contracts while preserving occurrence identity separately.

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
