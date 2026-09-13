# Polynomial execution with unchanged evidence and less allocation

This follow-up to PR #999 preserves mathematical results, work units, serialized
proof material, budget decisions and the public coefficient-domain contract.
Its reference is PR head `5b58870750b5eed5fdc534990d472945d0a50f4b`.

## Execution-local immutable ledger reuse

`PolynomialWorkBudget.ledger()` retains at most one immutable snapshot. A
successful positive `consume` invalidates it. Repeated reads, zero-unit updates,
invalid updates and rejected budget overruns do not rebuild unchanged evidence.
The original `PolynomialWorkLedger` constructor still validates and canonicalizes
every newly issued snapshot. No ledger field or validation has been removed.
Old snapshots remain immutable after subsequent work. There is no global cache,
no cross-run evidence reuse, and no new concurrency contract: mutable work
bookkeeping remains execution-thread-confined, as it was before this change.

## Exact arithmetic on the already bounded prime-field residues

`PrimeField` still accepts arbitrary-size positive and negative `BigInteger`
inputs and only the same deterministically validated positive `int` primes.
An input already in `[0, p)` is reused; other inputs use the original exact mod
operation. Addition and multiplication reduce both arguments before using long
arithmetic. For reduced residues `a,b`, `0 <= a,b <= p-1` and `p <= 2^31-1`.
Consequently `a+b < 2^32` and `a*b < 2^62`; neither can overflow a signed long.
One subtraction suffices for a sum. A product uses the exact remainder modulo p.
Division retains the existing inverse and zero-divisor checks and uses the same
proved-safe product bound. This is not a finite-precision approximation, a wider
modulus range, a probabilistic primality test or a new mathematical rule.

Domain IDs, canonical texts, null/division failure ordering and result types are
unchanged. No algorithm stage, certificate, assumption, verifier or budget is
removed. No default is increased, and the learner is not modified.

## Regression controls

`PolynomialWorkBudgetSnapshotTest` covers snapshot reuse, invalidation on a
same-stage charge, zero/rejected updates, retained immutability, 400 independent
ledger comparisons and the exact long ceiling. `PrimeFieldArithmeticTest`
compares the real field with the old arbitrary-precision formulas exhaustively
on small fields and on boundary/random 4096-bit inputs, including modulus
`Integer.MAX_VALUE`. It covers nulls, invalid primes, zero divisors, domain
identity and concurrent use of the immutable field.

The checks can also be run through their package-local main entry points without
a JUnit runtime. Before publication, the two optimization-specific reuse tests
failed on the original code and passed on the change. Five temporary mutations
(stale snapshot, missing sum reduction, int product overflow, wrong negative
reduction, zero hiding a null multiplier) were all rejected by the real tests.

Fresh local native regression checks and the existing 1,079-observation scalar
audit pass. The entire 185-request canonical native-result stream remains
byte-identical to the reference, including failed budget cases; SHA-256 is
`0172dbf00c5066c22666a59d0f4e7f91d330351e9a0eb7a0c4e6d3df48617ed8`.
This finite regression evidence is not a theorem covering every program input.

## Qualification boundary

Local compilation uses Java 21.0.11, an explicitly partial source reconstruction
and the retained compile-time Jackson annotation bytes, not a full product
checkout. Current-head Java 25 Gradle, Maven/product/Docker, SymPy, JMH and final
ciCheck plus review remain required before merge. Historical CI on the preceding
head does not qualify this change. Profiling is diagnostic; timing comparisons
must retain the exact compiled variants and all cases, not infer speed from a
source diff or passing tests. The retained local comparison is not a sealed
holdout, a learned-search experiment or a claim of general superiority to SymPy.
