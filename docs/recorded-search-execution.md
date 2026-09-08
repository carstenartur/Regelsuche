# Recorded search execution and artifact replay

Issue #900 uses the same canonical `TransformationProvenance` for live execution,
common search states, persisted discovery paths and graph/replay views. Primitive
rewrite sequences and verifier-backed exact theory remain distinct. Exporting or
loading their descriptions never grants execution authority.

## Data and authority

`RecordedExecution.capture(source, transformations)` snapshots the original
source-bound provenance, including composed inner steps, exact expressions,
application keys, assumptions, pack/license metadata, evidence JSON, receipt/run
references and mathematical work. Its `regelsuche.recorded-execution/v1` envelope
retains edge count, primitive rewrites, theory steps and theory work separately.
The SHA-256 identity covers the complete canonical UTF-8 representation.

`fromCanonicalJson` is bounded observational decoding, not a verifier. It checks
the version, shape, path continuity, endpoints and derived work counters, rejects
unknown fields and inconsistent envelope bytes, and retains the original nested
provenance. Its limits are 8 million code units, 64 nesting levels and a cumulative
decoding allowance. A syntactically valid description is still untrusted data.
Neither this object nor its JSON, hash or a public evidence binding is accepted
by `ExactTheoryEvidence.fromVerified`.

`requireReplay` compares the full observation against independently obtained
transformations. It does not construct transformations or select a solver result.
Installed source/provider/verifier code remains part of the trust boundary.

## Shared state and persistence

| Surface | Retained execution |
| --- | --- |
| Ordinary best-first, beam, structural diversity and Monte Carlo states | Actual ordered transformations, including real macro primitive lineage and assumptions |
| Mixed work-budget frontier | `State.toSearchState(scorer)` transfers the same typed transformations and separate work |
| Common-state artifacts and telemetry | Canonical full-path execution observation; state artifacts also bind all state metadata and scores |
| Discovery export/import and JSON-file restart | Per-step observation and assumptions survive round trips |
| Graph session JSON/file/Neo4j repository | Edge and replay observations, macro atomic steps, assumptions and statistics survive round trips |
| Neo4j expression edges | Complete scalar properties and execution JSON; relationship identity includes the execution hash |
| Semantic graph view | `sourceEdgeIds` distinguish provenance; `executionObservations` retains the underlying records even when layout hides or collapses edges |
| HTTP replay and transformation DTOs | The same versioned, non-executable execution observation |

Hashing and serialization are outside the search hot path. States retain the
original immutable transformation list. Root states have an empty, zero-work
execution. Historical descriptions and synthetic e-graph/explanatory paths which
never retained actual transformations remain explicitly **observation-only**:
their execution/work is absent, not a fabricated primitive sequence or a free
theory step. They may be displayed but cannot pass artifact execution replay.

Layout edge counts, hidden-step counts and path depth remain presentation counts,
not primitive work. The recorded `primitiveRewrites`, `exactTheorySteps` and
`exactTheoryWorkUnits` are the separate mathematical dimensions. Multiple semantic
observations are not necessarily one sequential execution and must not be charged
as if they were a single path.

When an observation-only edge is saved to a legacy Neo4j store, relationships
with the same directed endpoints and rule and with neither an execution identity
nor execution JSON are assigned the empty observation identity in the same query.
The subsequent identity-aware merge reuses them. Existing recorded executions
remain separate and unchanged; saving a recorded execution never promotes a
legacy observation to that execution. This is a lazy compatibility migration,
not deletion or consolidation of duplicates already present in a store.

## Loading and independently replaying artifacts

```java
var reference = SearchReplayArtifact.describe(result.toCanonicalJson());
// Store the canonical UTF-8 JSON and its separately retained reference.
var replayed = WorkSearchReplay.verifyArtifact(file, reference, () -> {
    // Rebuild sources through the complete artifact/verifier/replay pipeline.
    return independentlyVerifiedProblem;
});
```

`SearchReplayArtifact.load` checks exact byte length, SHA-256 and strict UTF-8
under a 32 MB limit **before** requesting expensive source reconstruction. It
reads only the caller-selected file; serialized receipt/run strings are not
filesystem paths and are never followed automatically. A changed digest reference
does not establish trust: the subsequent fresh search must still reproduce every
configuration, state, decision, provenance and work field.

`SearchStateReplay.verifyArtifact` applies the same byte gate and complete
comparison to a common state produced by an independent replay. It rejects
observation-only states. `RecordedPathReplay.verify` handles imported
`DiscoveredTransformation` paths: missing/discontinuous observations fail before
source reconstruction, and a fresh explicit work-budget search must reproduce
each actual outer edge, its complete provenance and its scores. Merely reaching
the same endpoint with another certificate or a flattened primitive shortcut is
insufficient. Importing JSON itself never triggers a solver or resumes a search.

The callback owns the explicit replay problem and its primitive/theory/global
budgets; it must rebuild verified sources, not reinterpret public JSON as trusted
evidence. The original primitive mechanical v1 and mixed v2 search-work formulas,
finite-plan evidence identities and frozen experiment artifacts are unchanged.
Other algorithms still reject theory execution without explicit work authority.

## Verification and limits of the claim

Tests use the real finite-polynomial solver, exact artifact-byte verification,
independent replay confirmation and verifier-issued candidate evidence. They
cover mixed-state transfer, file restart, full export/session round trips,
distinct evidence at identical expressions, native primitive macro lineage,
assumptions, tampered work/evidence/byte references and missing lineage. A public
hash recomputed over altered JSON still fails fresh verifier/search comparison.

`Neo4jExpressionGraphStoreTest` runs against a pinned Neo4j 5.26 container in the
Maven `full` integration-test profile (also available through Gradle
`:app:dockerE2eTest`). It covers legacy and empty identities, repeated writes,
parallel recorded executions, reconnecting and unrelated legacy relationships.

This completes a storage and replay boundary, not a new proof engine. Display
metadata and imported validation labels are not proof authority. It does not
invent an e-graph primitive derivation, authorize learned-plan promotion, change
search defaults, qualify tactic utility or establish mathematical novelty.
