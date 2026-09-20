# Typed primitive candidate reuse

## Purpose

The source-only learning diagnostic in #1042 reaches the same expression sets
through additional path-sensitive states. This opt-in provider reuses one narrow
piece of work: deterministic primitive candidate generation for the same typed
source and premise context. It does not replace the search algorithm, merge
states, prune rules, cache verification, or claim improved learning quality.

## Contract

Construct `TypedPrimitiveCandidateCache` with a descriptor, concrete
`PatternRewriteRule` list, unchanged transport growth/candidate bounds, an
entry capacity and a maximum retained character count. The convenience constructor
rejects subclasses because their methods could depend on mutable state.

For actual compiled inventories, `forDeterministicTransport` is an explicit
caller contract: the transport must retain fixed rules/configuration and produce
the same ordered steps and complete metadata for equal source ASTs. It must not
be used with mutable, random, history-dependent or externally reconfigured rules.
This property is not proved by the factory. `TypedLearnedMoveInventory` meets the
contract through the compiler's private final, immutable genome/pattern rules;
it reuses the existing transport, not a rewritten or simplified imitation.

The cache key contains the exact tagged expression, state premises and the whole
`MoveContext` (goal, initial premises, phase). Differing scoped symbol identities,
grouping, assumptions, goals and TRAIN/evaluation contexts are not conflated.
Search depth, previous rule, capabilities and complexity debt remain in the real
search state and are still checked by the unchanged scheduling/admission code.
They do not affect this primitive generator's output.

There is no static cache or cross-profile entry sharing. Calls are serialized to
protect bookkeeping. Capacity zero or character limit zero delegates unchanged.
Deterministic FIFO eviction changes reuse only: oversized or evicted candidates
are generated and returned normally, never dropped. The entry and character
bounds constrain retained objects/text; the character count is not a JVM heap
measurement, and fixed inventory metadata is not duplicated per entry.

Cached proposals retain transformation metadata and primitive provenance. Their
original completeness flag is preserved, including empty but incomplete relations.
Both ordinary primitive verification and additional selected-path replay stay
fresh. The cache has no verifier method and cannot authorize a proof edge.

## Actual learning-inventory integration

```java
var inventory = frozenStrategy.typedMoves();
var session = inventory.newSearchSession(true, 64, 1_000_000);
// Use session.providers() and session.verifier() in the typed search problem.
// Inspect session.primitiveCaches() for measured hits/misses/retention.
```

The boolean includes or excludes the already learned programs. The bounds are
PER primitive provider (eight providers in the current example), not per whole
inventory. Every call creates empty caller-owned caches. Include construction in
setup timing and use a fresh session for each independently measured query.
No genome recompilation is introduced per session. The original providers and
frozen v1 worker remain unchanged unless this new API is explicitly selected.
The actual learned program objects and their verifier are unchanged; this step
does not cache macro-internal generation or any proof verification.

## Accounting and limits

Misses pay the original primitive batch work plus a lookup, inspected retained
text fields, insertion and each eviction. Hits pay a lookup and candidate delivery,
with zero claimed new engine/source invocations. Mathematical candidate work is
conservatively retained on every delivered batch; it is not erased to make a
counter look better. These are declared mechanical events, not complete CPU/string
hashing instructions or object-allocation costs. Session construction belongs in
measured setup time. The bookkeeping can outweigh savings for cheap rules or low
reuse. No production default or frozen v1 benchmark is tuned by this change.

The integration regressions retain their original/cached work diagnostics even
when caching costs more. Equality of complete paths/events is checked with an
ample shared budget; under a tight budget, honestly charged cache overhead may
change which states are affordable. No unconditional finite-budget quality or
speed guarantee is implied.

## Verification record

The initial uncached control `b58625da24e859131b537a8cca78a812845efc65` compiled
on Java 25 in run `35490026893`. Eight reuse assertions failed as expected;
the other four new contracts and 16 existing search tests passed. Its retained
JUnit artifact is `10598298873`.

The first cache implementation `213c332fb31b5533c63223347a76f659fb882e6c` passed
the full specialized command in run `35490226824`, including the unchanged v1
matrix and independent audit (artifact `10599310668`). This does not establish
an external speedup: the frozen v1 worker deliberately remains uncached.

Five real-inventory integration tests were then added against an uncached session
scaffold at `e2c8ab9d449f2c7edd61e27e4bcb0347ff2760eb`. In run `35490404729`,
the three expected missing-cache/reuse assertions failed, while the other 15
selected learning tests, all 28 selected search tests and all 43 Python tests
passed. Artifact `10599320965` retains this RED evidence. The implemented
integration head requires its own GREEN run; no local execution is claimed.

The checkout command now runs ALL search and learning module tests, emits their
JUnit totals and actual cache diagnostics, and executes the unchanged v1 matrix
and independent audit:

```sh
bash gradle/run-typed-external-polynomial-comparison.sh
```

This does not replace the full repository's product, JMH, Docker or quality gates.
A separate frozen comparison of primitive/learned execution with and without
reuse, including setup/CPU/storage costs and negative outcomes, remains necessary
before stronger performance or learning-amortization claims.
