# Rule-candidate post-formation boundary

## Purpose

`RuleCandidateMiner` has one explicit boundary after a candidate has been fully
formed and before the public mining call returns it. A configured
`RuleCandidateFormationObserver` receives the immutable `RuleCandidate` and the
formation evidence that produced it.

In this implementation, “post-formation publication” means the synchronous
observer call with that candidate/evidence pair. There is deliberately no
separate `RuleCandidatePostFormationPublisher` or
`RuleCandidateDiscoveredEvent`. Those names describe possible adapters, not
additional lifecycle authorities.

This is an integration seam, not a classification or promotion policy. The
default constructors use a private identity-stable no-op observer and preserve
the previous product behavior without constructing unused strict evidence.
Polynomial classification remains opt-in: a later integration must explicitly
inject an observer configured with one visible exact factorization engine.

## Evidence

The observer receives four independent evidence axes:

- source-reported applied rule IDs used by the successful transformation paths;
- source path IDs as provenance;
- normalized assumptions;
- equivalence/validation evidence retained by the source paths.

For a configured observer, the evidence is immutable, rejects blank entries,
is de-duplicated in encounter order and is never written back into the candidate.
The default no-op boundary does not impose these additional evidence checks on
historic mining calls. Evidence remains separate from theory subsumption,
project-inventory novelty, external novelty, cache utility and promotion status.

## Exactly-once and collision boundary

For ordinary clustered mining, each candidate returned by `mine(...)` is
observed once after bucket de-duplication and threshold filtering.

For bulk single-path mining, candidate formation happens without callbacks,
then equal canonical candidates are de-duplicated, their evidence is merged in
encounter order, and the observer is called once per returned candidate. This
prevents duplicate routing when multiple source paths generalize to the same
schema.

A canonical key is never trusted as sufficient evidence of structural candidate
equality. Both clustered buckets and bulk single-path merging retain the
separately canonicalized left and right patterns and compare them before source
paths or observer evidence are combined. A same-key/different-structure
collision therefore fails before any observer receives a candidate; the first
value is not silently retained.

Instance-specific formation descriptions are deliberately not part of that
structural identity. For example, the alpha-equivalent observations
`x + 0 → x` and `y + 0 → y` may carry `A ∈ {x}` and `A ∈ {y}` respectively,
while both form the same canonical schema `A + 0 → A`. They remain one candidate
and retain the combined source-path evidence rather than being rejected as a
hash collision.

Unverified single-path inputs and paths that do not form a returned candidate
do not reach the observer. Ordinary clustered mining preserves its existing
eligibility rules. An observer failure propagates for the affected formation;
the failing candidate is not returned while pretending that configured
post-formation processing succeeded.

## Polynomial classifier relation

`PolynomialTheorySubsumptionClassifier` now consumes the exact
parser → typed request → selected engine → verifier → deterministic rendering →
exact reparse authority. It is no longer backed by the historical specialized
quartic synthesis operator.

That classifier is still not installed by the no-op miner constructors. The next
#748 integration slice must provide an explicit observer adapter that:

- classifies the immutable candidate without changing its formation evidence;
- retains positive, negative, unsupported, budget-inconclusive and technical
  outcomes separately;
- has one clearly owned handoff to the bounded derived-macro cache;
- never promotes a theory-subsumed observation into the kernel or standard
  inventory;
- does not hide engine selection or enable a default search policy.

## Deliberate boundary

The formation boundary itself does not:

- choose a polynomial factorization engine;
- classify a candidate automatically;
- write to `PolynomialDerivedMacroCache`;
- write to the verifier-authorized executable transition cache;
- promote a candidate into the kernel, standard inventory or
  `DynamicCandidateRegistry`;
- enable any default search or Workbench policy.

Automatic routing may be considered only after the observer adapter, retained
outcome ledger, cache ownership and held-out policy experiment have each been
qualified under their own explicit contracts.
