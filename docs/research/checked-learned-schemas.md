# Checked learned polynomial schemas

`CheckedLearnedSchemaModel` adds direct, independently verified applications to
the existing typed frontier. `TraceRewriteStrategyLearner` remains the producer
of training searches. The schema model performs bounded hypothesis formation and
symbolic checking; it introduces no second search engine or target oracle.

## Formation and lifecycle

1. Train the existing learner and retain its `FrozenStrategy`. Formation reads
   actual selected paths with an admitted multistep trace and verified observed
   replay. It does not accept a supplied desired schema.
2. Extract selected source/target endpoints and contiguous path windows of at
   least two steps. Strip equal outer binary operations only when the unchanged
   sibling is structurally identical. No algebraic normalization chooses the
   changed subtree.
3. Pair compatible source shapes from distinct training observations and call
   `TypedPatternGeneralizer`. Its shared subtree-vector bindings span both sides
   of both observations. Repeated variables retain the same placeholder, including
   compound expressions and scoped symbol identities.
4. Check each candidate over distinct symbolic indeterminates using
   `ExactPolynomialAnalysis.requireEquivalent`, which delegates to the existing
   bounded exact residual polynomial arithmetic. Agreement on the concrete
   training substitutions is insufficient. Only proved statements create
   privately constructed immutable `Schema` objects.
5. Create indexed typed providers and use the existing `TypedMoveSearch` or
   `TypedSourceOnlySearch`. Persist with `toCanonicalJson`; restore with `load`
   and the independently supplied expected inventory hash. Restoring needs no
   live `FrozenStrategy` and re-proves every admitted statement.

The public development fixture currently forms three schemas from its actual
observations: the full cancellation to a square, a cancellation suffix, and a
partial continuation. Which of these pays for its use is a separate source-only
selection question. These development cases are not independent transfer data.

## API

The class is in `de.regelsuche.evolution` in `regelsuche-learning`.

| API | Contract |
| --- | --- |
| `learn(FrozenStrategy)` | Forms a model under `Bounds.defaults()` from existing observations. |
| `learn(FrozenStrategy, Bounds)` | Allows tighter bounds; bounds cannot exceed the supported maxima. |
| `load(String json, String expectedInventoryHash)` | Validates canonical structure, revisions, domain, bounds and inventory binding, then re-proves all statements before checking their descriptive proof identities. |
| `schemas()`, `attempts()` | Immutable admitted statements and retained formation outcomes. |
| `providers()` | Returns one indexed typed provider, or an empty list when there is no admitted schema. |
| `providers(int maximumSchemasPerOccurrence)` | Limits the schemas attempted at each structurally relevant occurrence. |
| `providers(int, Map<String, Double>)` | Orders relevant schemas by finite supplied utility, then structural size reduction, then schema ID. Utility is scheduling evidence, never proof authority. |
| `providers(int, Map<String, Double>, Set<String>)` | Indexes only the explicit registered schema subset; unknown IDs are rejected. An empty subset yields no provider. |
| `verifier()` | Verifies a concrete registered application without primitive-path regeneration or discovery at other occurrences. |
| `requiring(List<String>)` | Returns another immutable model with additional normalized caller prerequisites; prerequisites are checked during both generation and verification. |
| `inventoryHash()`, `inventorySemanticsHash()` | Expose the genome binding and its versioned semantic binding. |
| `formationWork()`, `loadWork()` | Expose additional deterministic formation work and fresh load/recheck work separately. |
| `toCanonicalJson()` | Returns the immutable canonical model artifact. |

`TypedLearnedMoveInventory.checkedSchemas()` is a convenience formation bridge.
`newSchemaSearchSession(model, ...)` combines direct schemas with the same
primitive providers and dispatches to the appropriate verifier. Existing compiled
trace programs remain separately available; the schema model itself does not
emit trace fallback applications.

## Supported domain and trust boundary

The theorem domain is the commutative scalar rational polynomial algebra.
Expressions may contain exact rational literals, scalar variables, addition,
subtraction, multiplication, division by a nonzero literal, and nonnegative
literal integer powers. The declared polynomial convention gives every zero
power the value one. Functions, symbolic denominators and symbolic or negative
exponents are excluded. The whole concrete source and instantiated target must
satisfy the domain; an unsupported enclosing function is excluded too.

The initial theorem domain is unconditional. A conditional identity such as
`x/x = 1` cannot acquire authority by omitting its nonzero premise. Generalized
schema assumptions must be empty, including on load. `requiring(...)` adds caller
gates to otherwise unconditional theorems; it does not upgrade the checker to
prove conditional mathematics. Missing caller prerequisites reject both proposal
generation and verification. `ExactTheoryEvidence` therefore retains its existing
unconditional contract.

The artifact carries the model revision, checker revision, explicit arithmetic
profile, domain, bounds, inventory hash and inventory semantics hash. The
inventory binding includes the genome and typed transport revisions. Loading
rejects stale bindings and unsupported fields or limits. It checks the symbolic
statement before comparing stored theorem digests. Origin strategy hashes and
supporting observation IDs describe provenance; they do not independently prove
that a serialized history occurred, and they never authorize an identity.

`CheckedSchemaTheoryEvidenceProvider` is the installed core SPI bridge. It accepts
only the model's privately issued immutable application capability. Public JSON,
`ExactTheoryEvidence.Binding` records, hashes, scores and decoded observations
cannot construct that capability. A direct schema application is represented as
one exact-theory step with zero primitive steps; it does not claim a fictitious
primitive macro expansion.

For an application, exact structural matching determines a consistent binding
map. `TreePosition` replaces the selected occurrence and preserves the rest of
the typed AST. Application evidence binds the full encoded source and target,
occurrence path, substitutions, schema and model identities, revisions and work.
The independent verifier navigates the supplied path, repeats exact matching,
checks the substitutions and domain, reconstructs the full target and compares
the bound evidence and registered move metadata. It neither enumerates primitive
traces nor treats a stored digest as sufficient evidence.

Search capability deltas belong to state assessment, not to the polynomial
identity. `MoveSearch` calculates them from its `StateValue` result and overwrites
provider annotations before retaining a witness. The schema verifier excludes
only this delta from its mathematical comparison. Consequently a legitimate
assessed capability survives independent witness replay, while a provider's
invented capability does not enter the assessed successor state.

## Negative outcomes and bounded selection

`attempts()` retains `PROVED` and `TRACE_ONLY` outcomes, plus explicit
`EXTRACTION_LIMIT` and `FORMATION_LIMIT` receipts when work remains unexamined.
Examples without an admitted multistep trace, hypotheses without a common
source-bound abstraction, unsupported domains and unsuccessful symbolic checks
are not promoted. Failed checks retain the work already observed. Unexamined
hypotheses are not classified as false.

Default structural limits are 512 expression occurrences, 128 pattern nodes,
depth 64, 256-bit rational literals and exponent 32. Formation retains at most
128 selected examples, checks at most 256 compatible pairs, and admits at most
32 schemas. A provider attempts at most 256 matches and emits at most 32
candidates in a batch. The symbolic checker also has its separately persisted
exact arithmetic limits, including polynomial degree, terms and products.

An immutable index selects schemas by root operator before exact matching. A
nonempty provider using only a subset of registered schemas reports an incomplete
relation. Per-occurrence suppression, matching or candidate exhaustion, and
unsupported target instantiation likewise prevent a completeness claim.
Reference evaluation must retain all registered schemas and alternatives; an
explicit budgeted profile may choose a subset or smaller per-occurrence cap.
Even the default provider does not claim completeness if a hard bound is reached.

## Proof and reuse costs

The logical counters are deterministic event receipts, not a complete count of
CPU instructions, allocation, hashing or arbitrary precision arithmetic cycles.
Wall time, process CPU, allocation and memory therefore remain separate measured
dimensions in the comparison worker.

| Cost | Accounting boundary |
| --- | --- |
| Existing learner training | Remains payable separately; `formationWork()` does not replace the learner's search, replay, exact audit or minimality work. |
| Schema formation | Counts extraction and pairing events, generalizer invocations, explicit structural visits and exact symbolic node/term work, including failed attempts. |
| Persistence and restoration | Artifact I/O and process setup belong to end-to-end measurements. `loadWork()` counts fresh decoding/checking events and symbolic reproof; serialized formation counts grant no authority. |
| Provider setup and selection | Building the chosen immutable index and paid TRAIN utility comparisons belong to setup/selection measurements. |
| Generation | Counts domain visits, occurrence/index visits, actual matcher steps/branches and the application work represented by emitted exact-theory edges. |
| Failed or unchanged application | Keeps the partial substitution and target-validation work in the delegated mechanical ledger because there is no emitted edge to carry it. |
| Independent verification | Pays fresh premise/domain checks, path navigation, matching, substitution checks and full-target reconstruction. Source-only selected-path replay is an additional paid verification. |

The immutable model's canonical hash is computed once during construction and
reused in application bindings. This preserves the exact bytes and identities
while avoiding repeated hashing of the complete model for each application. It
is not a new cache and does not reuse application authorization. Every receiving
verification still checks the concrete source, path, bindings, prerequisites and
target.

`CheckedLearnedSchemaModelTest` and `CheckedSchemaProofBoundaryTest` cover direct
formation and reuse, false repeated bindings, distinct symbolic indeterminates,
exact rational coefficients, scoped compound bindings, missing prerequisites,
conditional/unsupported domains, proof and target tampering, stale revisions,
inventory mismatches, bounded arithmetic and ASTs, selection subsets, rejected
work accounting, and the separation of mathematical evidence from assessed
capabilities. Passing these contracts is not a runtime speedup claim.
