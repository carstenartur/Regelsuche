# Post-formation routing into exact polynomial theory

**Implementation status: 30 August 2026**

## Purpose

A rule candidate formed from search or mining may be mathematically valid and
still contain no new law: it may be one concrete representation already
produced by the exact polynomial factorization subsystem. Regelsuche provides
an explicit opt-in route for this case.

The route starts only after `RuleCandidateMiner` has formed, validated and
de-duplicated an immutable candidate:

```text
RuleCandidate + immutable formation evidence
  -> explicitly injected PolynomialTheoryCandidateObserver
  -> PolynomialTheorySubsumptionClassifier
  -> one caller-selected FactorizationEngine<ExactRational>
  -> exact parser / request / engine / verifier / rendering / reparse
  -> one of five retained theory outcomes
  -> positive only: bounded PolynomialDerivedMacroCache
  -> every outcome: bounded formation-outcome ledger
```

The default miner constructors still use their identity-stable no-op observer.
Nothing in this change silently enables polynomial classification, chooses an
engine or changes a production search profile.

## Explicit configuration

`PolynomialTheoryCandidateObserver` receives three objects from its caller:

1. one already configured `PolynomialTheorySubsumptionClassifier`;
2. one bounded `PolynomialDerivedMacroCache`;
3. one bounded `PolynomialTheoryFormationOutcomeLedger`.

The classifier constructor requires one visible exact-rational factorization
engine. Native, external and experimental engines therefore remain different
configurations. The observer has no default-engine constructor and no hidden
best-of policy.

The observer is injected through the existing
`RuleCandidateFormationObserver` constructor parameter of `RuleCandidateMiner`.
The miner itself needs no second event system, publisher or lifecycle.

## Formation evidence remains independent

The observer receives the already immutable formation evidence:

- applied source-rule IDs;
- source path or generation provenance;
- assumptions;
- validation/equivalence evidence.

This evidence is not rewritten into classifier evidence and is not allowed to
change the candidate. The exact classifier independently parses and evaluates
the candidate's left and right patterns.

The observer rejects an unverified/rejected candidate or an observation without
source provenance before invoking the factorization engine. A configured
observer failure remains fail-closed and propagates to the mining caller.

## Complete outcome retention

`PolynomialTheoryFormationOutcomeLedger` retains every completed
classification separately:

```text
THEORY_SUBSUMED
NOT_SUBSUMED
UNSUPPORTED
BUDGET_INCONCLUSIVE
TECHNICAL_FAILURE
```

The corresponding routing dispositions are:

```text
DERIVED_MACRO_CACHE
RETAINED_NOT_SUBSUMED
RETAINED_UNSUPPORTED
RETAINED_BUDGET_INCONCLUSIVE
RETAINED_TECHNICAL_FAILURE
```

A miss, unsupported expression, exhausted budget or technical error is never
silently dropped and is never converted into a novelty conclusion. The ledger
retains together, but as separate fields:

- the complete immutable `RuleCandidate`;
- the complete post-formation `Evidence`;
- the issuer-owned exact theory `Classification`;
- the routing disposition;
- only for `THEORY_SUBSUMED`, the retained macro entry ID.

Project-inventory novelty, external novelty, proof, interestingness, promotion
and utility remain independent evidence axes.

## Bounded deterministic ledger

The ledger is an in-memory evidence boundary, not a mathematical authority. It
has an explicit capacity, defaults to 1024 entries and rejects invalid capacity
values.

Every observation ID is SHA-256 over length-prefixed UTF-8 material binding all
candidate fields, formation evidence, classifier fields, routing disposition
and optional macro ID. Exact duplicate observations are idempotent and do not
consume another slot. A same-ID/different-content collision fails closed.

When the capacity is full, insertion removes the oldest entry in deterministic
FIFO order and exposes the evicted entry ID. Statistics retain current size,
insertions, exact duplicates and evictions. Eviction or absence has no
mathematical meaning.

## Single cache owner

Only `PolynomialTheoryCandidateObserver` performs the automatic handoff to
`PolynomialDerivedMacroCache`. The outcome ledger does not write to the cache,
and the miner does not contain a second handoff.

A positive classification is retained with:

- the classifier-issued exact canonical source;
- the verifier-derived representation;
- the stable exact transformation method ID;
- the verifier candidate certificate;
- the occurrence-bound application certificate and accounted work;
- the source observation provenance.

The macro lineage deliberately uses
`ExactFactorizationTransformationPipeline.TRANSFORMATION_ID` as its primitive
method identity. Source rules that happened to produce the observation remain
in the formation ledger; they do not replace the verifier-authorized
factorization method as the cached macro's mathematical expansion.

Different source paths or generations may therefore attach separate lineages
to one mathematical macro slot. The application-layer end-to-end integration
test forms the same univariate factorization schema from two alpha-equivalent
paths in successive generations and requires one cache entry, two lineages and
two independently retained formation outcomes.

The native integration test lives in `app`, the composition layer that is
already allowed to depend on both `regelsuche-learning` and
`regelsuche-math-algorithms`. The learning module therefore keeps its declared
architecture direction and does not acquire a test-only implementation
back-edge.

## Relation to executable cache replay

`PolynomialDerivedMacroCache` is the existing bounded theory-derived macro
retention boundary. It is not the stronger executable transition store.

`VerifiedPolynomialTransitionCacheStore` retains the complete primitive
verifier chain and releases a transition only after exact revision-bound replay.
The current post-formation adapter does not reconstruct or manufacture an
`ExactFactorizationTransformationPipeline.Result` from a classification and
does not bypass that store's issuer-owned API.

Connecting ordinary mining observations to executable transition replay is a
separate later slice. It must expose the original verifier-authorized
transformation without deriving authority from a pattern pair or cache entry.

## Threading and failure semantics

One observer instance serializes classification, cache handoff and outcome
retention. This gives a deterministic order for shared bounded stores. It does
not make the callback transactional across arbitrary external observers.

The only normal cache mutation occurs after a positive issuer-owned
classification. Invalid inputs are rejected before classification. Exact
repeated callback delivery is idempotent in both the macro cache and the
outcome ledger.

## Verification

Focused learning contracts:

```bash
./gradlew :regelsuche-learning:test \
  --tests de.regelsuche.mining.PolynomialTheoryCandidateObserverTest
```

Application-layer native integration:

```bash
./gradlew :app:test \
  --tests de.regelsuche.mining.PolynomialTheoryMiningIntegrationTest
```

Complete checkout authorities:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

The tests cover all five outcomes, fail-closed input rejection, exact duplicate
retention, deterministic FIFO eviction, forced identity collision and native
end-to-end routing from candidate formation through exact classification into
one cross-generation macro entry.

## Claim boundary

This integration establishes that an explicitly configured mining observer can
classify formed candidates with one exact engine, retain all outcomes and route
only verifier-subsumed cases to a bounded theory-derived macro cache.

It does not establish that enabling this route improves search, that the cache
should be a product default, that a candidate is externally novel, that the
factorization engine is complete, or that cached macro lookup is already an
executable search transition. Those decisions require the frozen matched-work
profile comparison under issue #748.
