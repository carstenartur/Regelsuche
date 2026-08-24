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
  -> semantic evidence replay
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

A zero denominator has its own fail-closed status and never creates a value.

## Resource limits

The domain applies finite limits to:

- raw source characters;
- total decimal digits;
- finite-decimal scale.

The raw source-length check runs **before** whitespace trimming. Padding cannot
therefore bypass the configured character limit. Evidence retains at most the
configured source-character bound.

Version 1 caps serializable limits at 4,096 source characters, 1,024 digits and
256 decimal places. Callers may choose smaller limits. Limit exhaustion yields
`LIMIT_EXCEEDED`; it is not evidence that a mathematical value is invalid.

The selected limits are part of every evidence object and of every successful
parse certificate.

## Evidence identities and semantic verification

A successful parse exposes:

- `valueId`, depending only on domain version and canonical mathematical value;
- `certificateHash`, additionally binding limits and the accepted source lexeme.

Thus `1/2` and `0.50` share a value identity while retaining different source
certificates.

`ExactRationalParseEvidence` has no public constructor. For serialized evidence,
`ExactRationalEvidenceVerifier` replays the source under the declared limits and
checks:

- status and detail code;
- canonical reduced value;
- value identity;
- source- and limit-bound certificate.

This semantic verifier is required because JSON Schema can constrain spelling
but cannot prove GCD reduction or recompute hashes. Non-exact outcomes expose no
value or exact hashes; over-limit failures are verified as bounded fail-closed
failure shape because the rejected suffix is deliberately not retained.

The schema additionally rejects noncanonical textual forms such as `-0`, `01`
and `1/1`. A syntactically canonical-looking but reducible value such as `2/4`
is rejected by semantic replay.

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
RationalExactScalarAdapterTest test
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
