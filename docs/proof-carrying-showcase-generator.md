# Deterministic showcase FINAL TEST generator

Status: **Java implementation complete; development characterization only**  
Tracked by: [#597](https://github.com/carstenartur/Regelsuche/issues/597)

## Purpose

`ProofCarryingShowcaseCaseGenerator` turns an already-authorized
`ProofCarryingShowcaseSeedReceipt` into the complete 24-case future showcase
suite. It performs no network access and accepts no tuning input.

The seed receipt must already bind:

- the frozen showcase plan;
- the frozen candidate;
- a verified future drand receipt;
- the pinned chain and round;
- the domain-separated derived seed.

The output remains:

```text
FINAL_TEST_GENERATED_NOT_EXECUTED
```

Generation does not establish `SHOWCASE_CONFIRMED`, mathematical novelty,
importance or publication-grade validity.

## Java and JUnit boundary

The generator is production Java code in `regelsuche-learning`. JUnit calls it
directly. No test starts Python, invokes a shell, or maintains a second
implementation of hashing, family construction or stage semantics.

The same Java records that create the artifacts also validate strict JSON
roundtrips:

```text
ProofCarryingShowcaseGeneratedCase
ProofCarryingShowcaseGeneratedFinalTest
```

Unknown fields, duplicate fields, trailing tokens, substituted hashes,
reordered cases, duplicate identities and inconsistent aggregate roots fail
closed.

## Frozen surface

The plan fixes three families with eight cases each and two variants at every
difficulty level 3, 4, 5 and 6:

1. `nested-rational-cancellation`
   - chains quotients with shared denominator factors;
   - requires flattening, cancellation and explicit nonzero assumptions.
2. `factor-cancel-collect`
   - combines difference-of-squares factorization, cancellation and collection
     over a shared denominator.
3. `multi-stage-rational-polynomial`
   - alternates mixed-denominator ratios and factorable polynomial quotients;
   - requires several transformations in the correct order.

Every generated fixture case is evaluated by the production
`RationalFunctionNormalFormEquivalencePortAdapter`. JUnit requires:

```text
status = CONFIRMED
missing assumptions = []
unsupported assumptions = []
```

## Determinism and identity

Each case is derived from domain-separated SHA-256 material binding:

- the final derived seed;
- family identity;
- difficulty level;
- variant.

The output retains:

- input and target expressions;
- normalized explicit assumptions;
- bounded coefficient vector and block topology;
- structural fingerprint;
- seed-bound case ID;
- per-case content hash;
- family roots and one complete case-content root.

The suite rejects duplicate case IDs, content hashes, inputs and structural
fingerprints. Cases must appear in the frozen family/difficulty/variant order.
Manual replacement, pruning and selective regeneration are forbidden.

## Verification

Run the ordinary JUnit slice:

```bash
./gradlew :regelsuche-learning:test \
  --tests 'de.regelsuche.evolution.ProofCarryingShowcaseCaseGeneratorTest'
```

Or execute the complete repository contract:

```bash
./gradlew test
./gradlew check
./gradlew --no-configuration-cache ciCheck
```

The tests require:

- byte-identical repeated Java generation;
- a changed suite root after randomness substitution;
- exactly 24 cases;
- exactly three balanced eight-case families;
- every frozen difficulty level in every family;
- strict JSON/content-hash roundtrips;
- exact mathematical equivalence and complete assumptions for every case.

## Real generation boundary

The Java generator may be called only after the real candidate freeze and
verified future randomness have produced an authorized seed receipt. The
resulting suite may be inspected and reproduced but not replaced. The next
allowed operation is the single complete paired execution matrix against all
frozen configurations.

The language-neutral schema remains:

```text
docs/schemas/regelsuche-proof-carrying-showcase-generated-final-test-v1.schema.json
```

It documents the interchange surface; Java constructors and JUnit own the
executable invariants.
