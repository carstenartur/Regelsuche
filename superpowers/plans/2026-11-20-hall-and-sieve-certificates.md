# Hall and finite sieve certificates implementation plan

> **For agentic workers** use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to execute the authorized tasks. Steps use checkbox syntax for tracking.

**Goal** — Produce source-bound finite certificates for Hall deficiency and activated-congruence survivor counts, then apply them to a bounded canonical Greedy instance.

**Architecture** — `regelsuche-math-algorithms` supplies deterministic search, bounded arithmetic and verification. `regelsuche-optimization-sdk` delegates to CORE. Primachsenraum constructs the graph and its chronological evidence.

**Tech stack** — Java 25, Maven 3.9.x and the repository's JUnit tests.

**Spec** — [Hall and finite sieve certificates](../specs/2026-11-20-hall-and-sieve-certificates-design.md).

## Global constraints

- Java 25. No new dependencies.
- CORE owns all mathematical algorithms and verification.
- SDK facades map `OptimizationBudget` and `CancellationToken` to `IterationLimits` and `BooleanSupplier`.
- The consumer owns chronological provenance, graph completeness and the interpretation of the bounds.
- Every claim is finite. No analytic theorem is assumed.

## Review focus

1. A maximal matching can underestimate the maximum. Verify equal-size matching and cover on an augmenting-path example.
2. Isolated left vertices count toward the deficit, including when the right side is empty. Account for both dimensions before allocating state.
3. Changing one source edge or one activation must invalidate a transplanted certificate even when the reported answer is unchanged.
4. Large overlaps can make a higher-order partial sum weaker. Tighten bounds using every complete layer of the appropriate parity and [0,N].
5. A missing actual blocker edge can create a false survivor. Check the complete event history independently in the consumer.

## File map

For each CORE source or test below, the path starts at `regelsuche-math-algorithms/src/{main,test}/java/de/regelsuche/math/algorithms/`. Each SDK path starts at `regelsuche-optimization-sdk/src/{main,test}/java/de/regelsuche/sdk/optimization/`.

| Task | CORE | SDK | Capability documentation |
| --- | --- | --- | --- |
| 1 | Add `combinatorics/BipartiteMatching.java` and `combinatorics/BipartiteMatchingTest.java` | Add `combinatorics/BipartiteMatchingSdk.java` and `combinatorics/BipartiteMatchingSdkTest.java` | Add `docs/research/bipartite-matching.md` |
| 2 | Extend `modular/CongruenceCoverage.java` and `modular/CongruenceCoverageTest.java` | Extend `modular/CongruenceCoverageSdk.java` and `modular/CongruenceCoverageSdkTest.java` | Extend `docs/research/congruence-coverage.md` |
| 3 | Consumes Task 1; Task 2 is evaluated independently | Uses the released or locally installed SDK corresponding to the verified CORE revision | Primachsenraum canonical Greedy proof project and accompanying mathematical note, located from the pinned snapshot |

The integration owner updates the [strategy index](../../research/strategies.md), architecture and documentation-index links after the public entry points and test outcomes settle.

## Task 1 — Certify maximum bipartite matching

**Interface** — Define nested `Request`, `Edge`, `Certificate`, `Result` and `Verification` in `BipartiteMatching`. Expose `search(Request, IterationLimits, BooleanSupplier) → Result` and `verify(Request, Certificate, IterationLimits, BooleanSupplier) → Verification`. The SDK facade exposes the same operations with its budget and cancellation types.

- [x] Write CORE tests for the graph with neighborhoods {0,1},{0} (matching size 2), and {0},{0},{0},{1,2,3} (deficit 2). Enumerate every left subset as an independent oracle on small generated graphs. Include empty sides, isolated vertices, repeated edges, invalid endpoints and shuffled inputs.
- [x] Add negative replay tests for transplanting to a changed graph, reusing an endpoint, substituting a nonedge and forging W. Test pre-cancellation, mid-search exhaustion, insufficient verifier budget and oversized edgeless dimensions. Run the Task 1 command below and confirm the new entry points are missing.
- [x] Implement the immutable graph, deterministic augmenting paths and extraction of W. In the verifier scan the expected edges, establish matching membership and disjointness, derive the cover, then check equality of cardinalities. Charge both scans and vertex work to the supplied limits.
- [x] Implement the SDK facade and a test that executes both operations and propagates cancellation or exhaustion. Record the local-bottleneck example and the matching/cover argument in the new research note. Run the Task 1 command again. All selected tests must pass.

## Task 2 — Certify bounded survivor counts

**Interface** — Add `bounds(Request, int order, IterationLimits, BooleanSupplier)` and `verifyBounds(Request, int order, BoundsCertificate, IterationLimits, BooleanSupplier)` to `CongruenceCoverage`, returning a separate bounds result and verification, respectively. The SDK facade supplies overloads taking `OptimizationBudget` and `CancellationToken`.

- [x] Write tests comparing the lower and upper endpoints with direct finite enumeration for several orders, including zero, exhaustion of the effective rules, repeated congruences, incompatible noncoprime moduli, signed progression origins, empty progressions and activation exactly at a candidate. Check an overlap example where a later same-parity sum is weaker.
- [x] Encode the eight-prime control from the spec. Require exact-union exhaustion at a 128-term limit, successful first- and third-order lower bounds of 20,617,871,161 and 22,976,886,861, and inclusion of the independent exact answer 22,992,076,800. Verify rejection after changing an activation, order or layer sum. Run the Task 2 command below and confirm the new entry points are missing.
- [x] Reuse the existing normalization, CRT intersection and finite ray count. Enumerate complete layers over distinct rule positions using bounded state. Prune empty intersections. Record exact layer sums, compute the tightest available bounds, and replay against the expected source and order. Reject negative order. On interruption discard the incomplete result.
- [x] Delegate through the SDK, add a cancellation regression that interrupts inside a layer, and document the bounds interpretation and the reproducible eight-prime control. Run the Task 2 command again. Bounds and existing exact-coverage tests must pass.

### Regelsuche test commands

Use Java 25 and Maven 3.9.x from the repository root. Run these commands for Tasks 1 and 2 respectively.

```sh
mvn -pl regelsuche-optimization-sdk -am -Dtest=BipartiteMatchingTest,BipartiteMatchingSdkTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl regelsuche-optimization-sdk -am -Dtest=CongruenceCoverageTest,CongruenceCoverageSdkTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## Task 3 — Primachsenraum integration at 3f80f9ac

- [x] Pin the consumer to `3f80f9ac1a78ef349ee3ca9af20c452ad150b3de`. Reuse `docs/research/greedy-counting/src/check_derived_nonactivation.py` and the Java integration under `integrations/regelsuche-modular-coverage`; add `GreedyHall`, its tests, `check_hall_certificates.py`, a finite manifest and `docs/research/greedy-hall-certificates.md`.
- [x] Construct one finite p-class below D from dated events, account for every exclusion cause, and justify the span bound for each larger blocker. Replay the reference independently to assert that every actual blocking edge is present and the certified lower bound is at most the observed survivor count.
- [x] Call the SDK search and verification from the consumer's normal entry point. The separate bounded-sieve capability has its own periodic-sieve comparison; only use it in this consumer when it yields a meaningful smaller-rule count. Include strict activation and deadline endpoint tests, source mutations and a removed actual edge.
- [x] Run the pinned consumer's reference and tests. In its mathematical note report the graph definition, capacity proof, finite counts, source revisions and the universal claim that remains open. Record Regelsuche's verified outcomes in the strategy and architecture indexes.

## Observed integration validation

- Java 25 Maven install of the seven-module SDK reactor: 3,075 tests, no failures or errors; one existing optional external-study test skipped.
- Packaged Primachsenraum consumer against that installed SDK: 60 tests, no failures, errors or skips.
- The new workflow step ran unchanged locally: 10 Python tests in normal mode and 10 under `-O`, then four Java SDK certificates and independent replay status `VERIFIED_FINITE_CANONICAL_HALL`.
- Independent reviews of matching, Bonferroni bounds and chronological graph reconstruction approved the specification and mathematical implementation.
