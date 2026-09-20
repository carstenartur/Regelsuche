# Typed primitive candidate reuse

## Purpose

The source-only learning diagnostic in #1042 reaches the same expression sets
through additional path-sensitive states. This opt-in provider reuses one narrow
piece of work: deterministic primitive candidate generation for the same typed
source and premise context. It does not replace the search algorithm, merge
states, prune rules, cache verification, or claim improved learning quality.

## Contract

Construct `TypedPrimitiveCandidateCache` with a descriptor, concrete
`PatternRewriteRule` list, the unchanged transport growth/candidate bounds, an
entry capacity and a maximum retained character count. The immutable rule
inventory and configuration are private to the instance. Rule subclasses are
rejected because their methods may depend on mutable state.

The cache key contains the exact tagged expression, state premises and the whole
`MoveContext` (goal, initial premises, phase). Differing scoped symbol identities,
grouping, assumptions, goals and TRAIN/evaluation contexts are not conflated.
Search depth, previous rule, capabilities and complexity debt remain in the real
search state and are still checked by the unchanged scheduling/admission code.
They do not affect this restricted primitive generator's output.

Create a fresh provider for each independently measured search. There is no static
cache or cross-profile shared state. Calls are serialized to protect bookkeeping.
Capacity zero or character limit zero delegates unchanged to the primitive provider.
Deterministic FIFO eviction changes reuse only: oversized or evicted candidates
are generated and returned normally, never dropped. The entry and character
bounds constrain retained objects/text; the character count is not a JVM heap
measurement and fixed inventory metadata is not duplicated per entry.

Cached proposals keep every transformation, rule identifier, application key,
assumption, attribution and primitive provenance. Their original completeness
flag is preserved, including empty but incomplete relations. Both ordinary
primitive verification and additional selected-path replay stay fresh. The cache
has no verifier method and cannot authorize a proof edge.

## Accounting

Misses pay the original primitive batch work plus a lookup, inspected retained
text fields, insertion and each eviction. Hits pay a lookup and candidate delivery,
with zero claimed new engine/source invocations. Mathematical candidate work is
conservatively retained on every delivered batch; it is not erased to make a
counter look better. These are declared mechanical events, not complete CPU/string
hashing instructions or a guarantee of faster execution. The bookkeeping can
outweigh savings for cheap rules or low reuse, so neither production defaults nor
the frozen v1 external worker is changed.

## Verification record

The initial uncached control at `b58625da24e859131b537a8cca78a812845efc65` compiled
successfully on Java 25 in run `35490026893`. Eight reuse assertions failed as
expected; the other four new contracts and 16 pre-existing search tests passed.
All 43 Python comparison/audit tests passed before the Java test run. This is the
RED evidence, not a passing implementation claim. Its JUnit artifact is
`10598298873`; the later implemented head requires its own GREEN evidence.

The 12 regressions cover reuse with full metadata, exact context separation,
scoped identity/grouping, FIFO and character bounds, no-op sources, disabled
control parity, isolation between instances, subclass rejection, fresh proof
rejection under a different rule inventory, and complete admitted event/state
and selected-replay equality in a real source-only search.

Run the checkout-owned Java-25 command:

```sh
bash gradle/run-typed-external-polynomial-comparison.sh
```

That also executes the unchanged v1 worker and independent audit. It establishes
regression compatibility, not an external speed comparison of cached execution.
A subsequent real-inventory experiment must compare BOTH primitive and learned
execution with/without reuse, charge all setup/storage work, preserve negative
results and freeze a separate evaluation protocol before stronger claims.
