# Exact polynomial search, replay and learning

## Delivered boundary

`PolynomialSearchIntegration` is the explicit application composition for
issue #763. It connects the existing parser/request/verifier/occurrence
pipeline to `BudgetedTransformationSource`, the rewrite-program interpreter,
and post-formation mining. Activation is opt-in; the standard rule inventory,
kernel and default search configuration are unchanged.

| Profile | Execution | Cache and fallback |
| --- | --- | --- |
| `NO_FACTORIZATION` | No factorization source or control | None |
| `ON_DEMAND_VERIFIED_FACTORIZATION` | Native bounded rational engine | Disabled |
| `VERIFIED_DERIVED_MACRO_CACHE` | Same native engine on an exact-source miss | Session-owned bounded FIFO; verified replay on a hit |
| `SPECIALIZED_BINARY_QUARTIC_CONTROL` | Existing `PolynomialDecompositionSynthesisOperator` | Separate historical hypothesis control, not general exact authority |
| `OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION` | Caller-supplied pinned GraalPy/SymPy rational engine | Disabled; missing, substituted or failed backend never invokes native fallback |

The external engine has caller-owned lifetime. Creating a profile does not
download a runtime, install dependencies or silently activate another backend.
Each session owns its cache; `sourceAt(path)` shares that session's cache across
occurrences, but independent sessions never share entries. Paths are explicit;
the source does not search for an easier occurrence or select a hidden best
engine. Candidate index zero is the explicit production selection policy.

## Search invocation

```java
var session = PolynomialSearchIntegration.open(
    PolynomialSearchIntegration.Profile.VERIFIED_DERIVED_MACRO_CACHE,
    Optional.empty(), 128);
var source = session.sourceAt(List.of(0)).orElseThrow();
var program = RewritePrograms.budgetedSource("exact-polynomial", source);
var result = new RewriteProgramInterpreter().executeBudgetedSource(
    program, "(x^2-1)+(x^2-1)", 40_000_000L);
```

The finite authority in this example admits the existing conservative pipeline
reservations; it is not a measured cost, a utility-study budget or a default
recommendation. `lastWork()` reports the actual raw stage ledger. The source
conservatively caps its raw mechanics by both the offered work and the existing
nested-pipeline ceiling, and does not reset work between lookup, projection,
factorization, verification, replacement or replay. Insufficient admission
returns `BUDGET_INCONCLUSIVE`, not a mathematical impossibility or `NO_MATCH`.

For the actual best-first frontier, use
`session.frontierAt(path).orElseThrow()` as the `SearchExpansionSource` of a
`WorkBudgetBestFirstSearchStrategy.Problem`. The standalone `BudgetedSource`
program lane is not implicitly interchangeable with the mixed-work frontier.
The explicit frontier adapter retains every source observation, maps only a
private-constructor `VerifiedExecution` through the installed
`ExactTheoryEvidenceProvider`, and emits a typed exact-theory transformation
with zero primitive rewrites. Public source-result records, bindings, JSON and
hashes cannot create that capability. The receipt binds the canonical
occurrence execution evidence; its run identity binds the source result.

`TransformationWorkMetrics.delegatedMechanicalWorkUnits` retains the source's
actual mechanics separately from invocations and mathematical path cost. It
survives aggregation, duplicate accounting and canonical search replay. The
frontier checks the charged batch before enqueueing candidates; insufficient
theory authority remains an incomplete expansion. The existing frontier's
batch-accounting limit is not a replacement for the stricter, still-open
canonical runtime admission contract of #748. Historical event-only metrics
retain zero delegated work and their existing scalar totals and replay bytes.

The interpreter retains exact-theory steps separately from primitive rewrite
steps. The transition's mathematical work retains the original factorization
work even on a cache hit. Replay mechanics record only this execution, not a
second charge for the old derivation. The retained seven-stage evidence chain
is proof lineage, not seven invented ordinary search rewrites.

## One primitive, separate occurrence applications

Direct execution, learned identities and replay all refer to
`ExactFactorizationTransformationPipeline.TRANSFORMATION_ID`. The primitive
evidence hash is the original root transformation certificate. Each application
additionally carries the nested certificate binding its root and occurrence
path. Equal subtrees at different paths therefore share primitive authority
without sharing application identity.

The verified store retains the original issuer-owned root result. Only a live,
store-issued replay releases it. Cached strings or publicly constructible
evidence summaries cannot mint that authority. Eviction invalidates an old
lookup even after reinsertion of the same content.

Replay also validates the current structural and candidate limits. A profile
requesting independent completeness rejects a retained product-only or
backend-claimed certificate; it cannot upgrade that claim on a cache hit. An
original independently certified complete result remains replayable under the
same evidence requirement without invoking the engine again.

`ExactNestedFactorizationTransformationPipeline.replay` projects and validates
the new occurrence against the original exact source, then uses the same local
replacement and surrounding-node/replay checks as direct execution. It does not
invoke an engine or reconstruct a certificate from a string pair. `TreePosition`
and the nested pipeline now live in `regelsuche-core`, retaining their package
names; there is no second search-owned implementation.

Cache binding is deliberately exact-syntax-sensitive: `x^2-1` and `(x^2-1)` do
not authorize each other's replay merely because they are algebraically equal.
Unsupported, stale or changed source evidence fails closed. Surrounding source
text is spliced using parser-issued ranges and the exact replacement is
parenthesized and reparsed. Surrounding numeric literals such as
`9007199254740993` are never reconstructed through `double` or an AST formatter.

## Mining handoff

`Session.learningObserver(macros, outcomes, retentionWorkLimit)` composes the
typed classifier with the same session's executable cache. Pass it to the
existing `RuleCandidateMiner` constructor. After formation and validation:

- `THEORY_SUBSUMED` is retained in the bounded macro cache and complete outcome
  ledger, handed off with its original verifier authority, and diverted from
  the ordinary promotion result;
- all other classifications remain in the outcome ledger and may proceed to
  ordinary review; no failure is converted into novelty;
- duplicate observations hand off their actual classification, not a mutable
  "last outcome" lookup;
- insufficient retention authority fails closed before insertion or eviction.

The formation observer remains the sole automatic macro-cache owner. Its
optional verified handoff is invoked only for a positive classification, after
outcome retention. The default miner still uses its no-op observer. Learning
classification and retention have separate caller-owned limits; their work is
not borrowed from a later search call. The source index keeps its first exact
source authority; additional generation lineage remains in the macro/outcome
ledgers, not an unbounded secondary index.

## Work and qualification boundary

`lastWork()` retains named raw stages for source parsing and matching, source
evidence validation, native work, verification, rendering, exact reparse,
reconstruction, occurrence replacement, cache lookup, insertion, eviction and
replay. Cache insertion uses an explicit conservative code-unit charge
(source + replacement + provenance + 1024), not a claim to count CPU operations.
`PolynomialTheoryUtilityCanonicalWorkProjection.partition` assigns every stage
exactly once under the unchanged frozen v2 ownership rules. Unknown stages stay
one-for-one factorization work, never disappear into a cheaper category.

`PolynomialStudyAdmissionCompatibilityTest` checks the 360 frozen input
envelopes for the three general profiles, using their first explicit occurrence
and the entire mechanical ceiling as a conservative raw ceiling. All stop before
an engine request. Even that ceiling cannot cover the current component-wide
preflight reservations. Giving later occurrences fresh authority would violate
the study contract.

This is an admission diagnostic, **not** the 600-row, five-profile study. It
does not execute or compare external mathematics, establish cache amortization,
open qualification, freeze candidate results or justify a product default.
The formation, plan, budgets, projection identity and sealed qualification bytes
remain unchanged. Completing #748 still requires a sound runtime mapping of
canonical stage authority, all measured profile adapters, the complete
candidate freeze before qualification, and two clean-checkout plus one pinned
container reproduction. Arbitrary budget multipliers, resetting work and
post-hoc budget increases are not substitutes for that boundary. Issue #763
therefore remains open for its comparative qualification requirements.

## Verification

```bash
mvn -Pproduct-reactor -pl app -am test \
  '-Dtest=*Polynomial*,*Factorization*,*TreePosition*,*RuleCandidateMiner*,*BudgetedTransformationSource*' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Regression tests cover original-authority identity, distinct occurrence
applications, stale/changed/evicted replay, exact surrounding literals, actual
miner-to-program integration, profile isolation, backend absence/failure,
finite work, FIFO bounds and unchanged canonical stage partitioning.
The best-first integration tests also reach a concrete target without primitive
rewrite credit, reproduce an independent cold-run replay byte-for-byte, retain
cache mathematical lineage, reject public-data evidence substitution, and
charge delegated mechanics before a search candidate can be enqueued.
