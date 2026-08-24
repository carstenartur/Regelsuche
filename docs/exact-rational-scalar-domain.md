# Exact rational scalar domain v1

## Purpose

Polynomial and representation procedures must not infer exact mathematical
coefficients from binary floating-point values. The first exact rational scalar
domain introduces a narrow, separately versioned boundary for rational values:

```text
source lexeme
  -> finite grammar and size limits
  -> exact arbitrary-precision rational
  -> canonical value identity
  -> source-bound parse certificate
```

The domain ID is:

```text
regelsuche.exact-rational-scalar/v1
```

This is the scalar foundation for the rational-coefficient remainder of issues
#661 and #748. It does not yet change the integer-only polynomial synthesis
profiles.

## Accepted source forms

Version 1 accepts only these textual forms:

- signed integers, such as `-12`;
- explicit fractions of signed integers, such as `6/-8`;
- finite decimal lexemes, such as `0.125` or `-3.50`.

Finite decimals are converted from their source characters. For example,
`0.125` becomes exactly `1/8`; no `double`, epsilon comparison or rounded
intermediate value is involved.

The following remain unsupported rather than guessed:

- scientific notation such as `1e-3`;
- repeating-decimal notation such as `0.(3)`;
- `NaN`, infinities and implementation-specific floating-point spellings;
- algebraic or transcendental coefficients;
- interval or approximate values.

A zero denominator receives its own fail-closed status and never creates a
value.

## Canonical value contract

`ExactRational` stores arbitrary-precision `BigInteger` numerator and
denominator values. Construction establishes all invariants:

- denominator strictly positive;
- numerator and denominator divided by their greatest common divisor;
- every zero represented as `0/1`;
- integer values rendered without `/1`.

Addition, subtraction, multiplication, division, comparison and non-negative
integer powers remain exact. Undefined operations throw rather than returning a
sentinel numeric value.

## Evidence identities

A successful parse exposes two content-addressed identifiers:

- `valueId` depends only on the domain version and canonical mathematical value;
- `certificateHash` additionally binds the accepted source lexeme.

Consequently, `1/2` and `0.50` have the same value identity but different parse
certificates. This permits semantic deduplication without discarding the exact
source provenance used by a proof or synthesis run.

A failed parse exposes neither a value nor either hash.

## Resource bounds

The parser applies explicit limits before constructing large integers:

- maximum source length;
- maximum total decimal digits;
- maximum finite-decimal scale.

The defaults are deliberately generous for normal symbolic work but finite.
Callers may supply smaller limits for untrusted or high-volume inputs. Hitting a
limit yields `LIMIT_EXCEEDED`; it is not evidence that the value is
mathematically invalid.

## Integration boundary

This tranche intentionally does not reinterpret existing AST number nodes or
silently broaden the current integer polynomial theory. The next integration
must be explicit:

1. preserve or recover the original coefficient lexeme without passing through
   `double`;
2. select this domain by a versioned theory profile;
3. bind denominator clearing and result reassembly into the synthesis
   certificate and work ledger;
4. keep integer-only evidence reproducible under its original domain ID;
5. add held-out utility evidence before making rational synthesis a default
   search capability.

The existing integer polynomial synthesizers therefore continue to reject
rational or decimal coefficients until that integration is reviewed.

## Verification

Focused test:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-core -am \
  -Dtest=ExactRationalDomainTest test
```

Full repository contract:

```bash
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Claim boundary

This implementation establishes a canonical exact rational value and a
fail-closed source-literal contract. It does not establish complete rational
polynomial factorization, exact interpretation of arbitrary existing ASTs,
external mathematical novelty or superiority over computer-algebra systems.
