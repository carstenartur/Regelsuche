# Automatic exact mathematics: search and checked use

## Intent and authorization

Carsten requested implementation of all recommended follow-ups on 25 November
2026. The accepted recommendation covers bounded LP witness generation, genuine
large-prime polynomial factorization, automatic operator residual proposals,
automatic scalar-center search, and a separate Primachsen chronology study.
The complete new asymptotic algorithms were explicitly deferred.

## Architecture and decisions

General mathematics belongs in `regelsuche-core` and
`regelsuche-math-algorithms`; the optimization SDK only maps budgets and delegates.
Prime-sequence assumptions and experiments remain in `primachsenraum`.
Inputs are typed immutable Java values. Successful mathematical claims carry
source-bound independently checked evidence. Exhausted search is inconclusive.

The existing verifier-first design is retained. A native bounded exact LP
producer avoids making a numerical solver part of the trusted base. A typed
large-prime field and independent verifier precede a standard square-free,
distinct-degree and randomized equal-degree factorization path. This is an
engineering choice for immediate general usefulness, not an implementation of
the new uniform deterministic complexity theorem. Selected code need not be
copied from a third party: standard algorithms can be implemented against the
existing arithmetic and tested against independent references.

For operators, source-derived identity, diagonal, zero and visible simple
predictors are candidates; exact elimination factors their residual. Strong
direct evaluation remains a candidate. For rational identities a bounded
deterministic rational grid supplies candidate scalar centers; all inversions
in both original trees must be defined. Neither search silently enlarges the
domain on which an identity is claimed.

## Deliverables

1. LP search producing the existing infeasible, optimal or unbounded certificate
   shapes, plus an affine-inequality implication consumer. Degenerate and
   unrestricted-variable cases must be represented correctly.
2. A `BigInteger` prime-modulus domain with explicit primality assumptions,
   separate bounded factorization verification, and a complete factorization
   search with multiplicities and reproducible randomness. Small fields remain
   supported. No enumeration proportional to a large modulus.
3. Automatic bounded operator proposal discovery, exact residual factorization,
   checked selection and reusable execution. Acquisition, verification and
   repeated execution are separately measured against sparse/diagonal/direct
   baselines. No JVM speedup is inferred from scalar operation counts.
4. Bounded scalar-center search and checked rational comparison without a
   manually supplied center. Original-domain obligations remain visible.
5. A Primachsen consumer that extracts compact chronological implications or
   exclusions from proved earlier conditions, with independently replayed
   evidence and an experiment on previously unresolved models. A negative
   research outcome is documented honestly; no universal deadline is promised.

## Verification and constraints

- Java 25; warnings are errors in changed main and test code.
- Work, memory, bit growth, time and cancellation limits are checked before
  costly allocations and during loops; nested calls share or debit budgets.
- A probable prime is not silently promoted to an unconditional field proof.
- Factorization accepts only independently checked complete results; failure
  to split does not prove irreducibility.
- Tests include independently derived examples, corrupted evidence, source
  mismatches, resource boundaries and structurally different held-out cases.
- No answer injection, benchmark-specific production branches or hidden
  future Greedy data. No performance claim without complete measured costs.
- Update the strategy index and capability documentation in the same PRs.
- Publish small coherent PRs; leave unrelated branches and files intact.

## Review focus

Degenerate LPs with no vertices; objective sign and free variables; primality
assumptions and composite moduli; large-p inseparability and exponent overflow;
resource accounting across nested searches; original rational domains;
operator preparation amortization; chronological witnesses from strictly
earlier offsets; finite evidence versus parameter-uniform statements.
