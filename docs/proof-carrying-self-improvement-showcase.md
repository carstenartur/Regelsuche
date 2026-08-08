# Proof-carrying self-improvement showcase

Status: **contract frozen, not run**  
Tracked by: [#597](https://github.com/carstenartur/Regelsuche/issues/597)

## Purpose

This showcase is designed to produce one result that is understandable before a
reader studies Regelsuche's full evidence architecture:

> The system learns a human-readable executable rewrite/search program from
> TRAIN-only observations. The complete program is frozen. Only afterwards are
> concrete FINAL TEST cases generated from verifiable public randomness. The
> learned program and every fixed baseline then receive the same cases,
> information and canonical mechanical-work budget.

A successful run may receive only the status `SHOWCASE_CONFIRMED`. It does not
claim external mathematical novelty, expert-reviewed importance,
publication-grade benchmark validity or universal superiority.

The publication-grade experiment in #521/#533 and the expert-review routes in
#389/#391 remain separate and deferred until qualified independent researchers
can participate.

## Why public future randomness is useful

A human custodian is not needed to hide concrete showcase FINAL TEST cases when
those cases do not exist before candidate selection. The ordering contract is:

```text
TRAIN execution
  -> complete candidate and configuration freeze
  -> finite not-before delay
  -> first eligible verified public-randomness round
  -> deterministic FINAL TEST generation
  -> one paired execution matrix
```

The frozen plan pins the League of Entropy drand `default` mainnet chain:

```text
8990e7a9aaed2ffed73dbd7092123d6f289930540d7651336225dc172e51b2ce
```

The drand documentation identifies this as the chained 30-second `default`
mainnet and recommends client-library verification of every randomness value.
The later receipt producer must therefore use a pinned, signature-verifying
client and bind its chain information and verification evidence. A raw HTTP
response is insufficient.

References:

- <https://docs.drand.love/developer/>
- <https://docs.drand.love/developer/API-v1/drand-http-api/>
- <https://docs.drand.love/developer/API-v1/chain-hash-public-round/>

No randomness round is fetched or consumed by the current contract slice.

## Frozen result threshold

A positive result requires all of the following:

- a candidate that is not exact- or alpha-equivalent to a seed;
- genuine composition and decision topology;
- at least three primitive operations on a retained successful path;
- improvement on at least four generated cases and two structural families;
- at least two newly reached cases, or at least a tenfold median reduction of
  canonical search work;
- zero correctness regressions;
- zero hidden-assumption regressions;
- zero technical failures in the counted positive route;
- complete retention of null, losing, unsupported and budget-exhausted cases;
- two clean checkout reproductions and one pinned-container reproduction.

The stretch objective is either a hundredfold canonical-work reduction on a
retained stress tier or two solved difficulty levels beyond every fixed
baseline.

If the threshold is missed, the required result is a complete
`SHOWCASE_NULL_RESULT`. Neither thresholds nor generated cases may be repaired
after outcomes are visible.

## Frozen comparison matrix

Every generated case is executed against exactly these configurations:

1. primitive-only best-first search;
2. preregistered hand-written program;
3. randomized valid-program baseline;
4. no-composition ablation;
5. no-decision ablation;
6. learned rewrite program.

Canonical primitive and total-work ledgers are authoritative. Wall-clock time
is retained only as an environment-qualified engineering diagnostic.

## Initial structural families

The deterministic generator will produce 24 cases, eight from each family:

- nested rational cancellation and quotient composition;
- factor–cancel–collect ordering problems;
- multi-stage rational/polynomial normalization with explicit nonzero
  assumptions.

Each family has difficulty levels 3 through 6. All configurations receive the
same generated cases. Manual replacement, pruning and selective reruns are
forbidden.

The concrete expression generator and production runner are intentionally not
part of this first contract slice. They are the next implementation stage and
must preserve the identities and thresholds already frozen here.

## Candidate-freeze boundary

Before the public-randomness not-before time, `candidate-freeze.json` must bind:

- repository commit;
- complete TRAIN run and selection evidence;
- candidate exact and alpha-structural identities;
- human-readable program rendering;
- primitive inventory, work budget and evaluation protocol;
- all seed candidate identities;
- program node count;
- required composition, decision and primitive-path properties;
- freeze time and public-randomness not-before time.

The candidate status is fixed as:

```text
CANDIDATE_FROZEN_FINAL_TEST_UNSEEN
```

Exact seed equivalence, missing topology, inadequate path depth and an
insufficient delay fail closed.

## Public-randomness receipt

`public-randomness-receipt.json` must bind:

- showcase plan and candidate-freeze identities;
- network and chain hash;
- round and round time;
- randomness, signature and previous signature;
- chain-info identity;
- pinned verifier identity and artifact hash;
- verification evidence;
- endpoint identity.

Only this status is accepted:

```text
VERIFIED_BY_PINNED_DRAND_CLIENT
```

The round time must be strictly later than the frozen candidate's not-before
boundary.

## Domain-separated seed

The seed is not the raw drand randomness. It is SHA-256 over a versioned,
domain-separated material binding:

- showcase ID;
- plan content hash;
- candidate-freeze content hash;
- drand chain hash and round;
- verified randomness;
- complete randomness-receipt content hash.

Changing any bound input changes the derived seed. The resulting
`showcase-seed-receipt.json` has status:

```text
FINAL_TEST_SEED_DERIVED_AFTER_CANDIDATE_FREEZE
```

## Verification from a checkout

Direct verifier:

```bash
python3 scripts/verify-proof-carrying-showcase-contract.py \
  research/showcase/proof-carrying-self-improvement/showcase-plan.json
```

Checkout-owned Gradle entry point:

```bash
./gradlew --no-daemon \
  -I gradle/proof-carrying-showcase.init.gradle \
  verifyProofCarryingShowcaseContract
```

The verifier checks the exact frozen contract, content hash, strict schema
surfaces and deterministic seed derivation. Its self-test also requires
rejection of a too-early randomness round and sensitivity to substituted
randomness.

A real seed may later be derived with:

```bash
python3 scripts/derive-proof-carrying-showcase-seed.py \
  --plan research/showcase/proof-carrying-self-improvement/showcase-plan.json \
  --candidate-freeze candidate-freeze.json \
  --randomness-receipt public-randomness-receipt.json \
  --output showcase-seed-receipt.json
```

This command does not fetch or verify drand itself. It consumes the output of
the future pinned verifier, revalidates all ordering and identity bindings, and
derives the final generator seed.

## Strict schemas

The current slice adds Draft 2020-12 schemas for:

- the frozen showcase plan;
- the complete candidate freeze;
- the verified public-randomness receipt;
- the derived seed receipt.

All schemas reject unknown top-level fields. Runtime verification additionally
recomputes content hashes and cross-artifact relationships; schema validity
alone is never treated as authority.

## Next implementation stage

The next code tranche is deliberately concrete:

1. implement the three deterministic expression-family generators;
2. add generated-case identity and split-collision tests;
3. adapt the existing rewrite-program population output into the strict
   candidate-freeze artifact;
4. implement the pinned drand receipt producer;
5. keep every stage at `NOT_RUN` until a real candidate is frozen.

Only after those steps and the actual candidate freeze may an eligible future
round be consumed.
