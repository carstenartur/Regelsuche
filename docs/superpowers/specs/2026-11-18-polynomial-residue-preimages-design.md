# Polynomial residue preimages

The authorized continuation needs all parameter residues satisfying an integer
polynomial congruence. Primachsenraum uses quadratic expressions for activated
candidate exclusions and linear expressions for cofactor divisibility. A Bouncy
Castle sieve qualification can use the same operation for affine candidate
indices. Arithmetic belongs in the Regelsuche math core; SDK methods only map
budgets and cancellation.

Expose `PolynomialCongruence.Request(List<BigInteger> coefficientsAscending,
int modulus, BigInteger residue)`. Coefficients are nonempty, immutable, and may
be signed; modulus is positive. The result is the complete sorted set of integers
0 <= x < modulus with P(x) == residue (mod modulus), including repeated-root,
constant, zero-polynomial, composite-modulus, and modulus-one cases.

Use a bounded complete residue scan: normalize exact coefficients once, use long
Horner intermediates for int residues, and respect IterationLimits. BigInteger
input widths above MAX_RUNTIME_BITS, coefficient/result sizes above maximumTerms,
work exhaustion, cancellation and elapsed-time exhaustion produce UNAVAILABLE
without a partial solution. A COMPLETE empty set means proven absence of roots.

Expose an immutable Solution bound to its exact Request and a verifier that
checks canonical residue order and completeness by replaying the bounded scan.
The solution is finite enumeration evidence, not a symbolic proof for arbitrary
moduli. No discovery, primality assumptions, or application semantics enter this
API. Existing finite-field factoring remains a possible later backend.

Approaches considered: application-local enumeration would duplicate reusable
arithmetic; prime-field factorization cannot handle composite moduli directly.
The bounded generic scan is the implementation choice for the observed small
moduli. No new root search for large fields or bitmap backend is required.
