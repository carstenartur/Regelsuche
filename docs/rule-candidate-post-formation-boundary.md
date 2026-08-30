# Rule-candidate post-formation boundary

## Purpose

`RuleCandidateMiner` now has one explicit boundary after a candidate has been
fully formed and before the public mining call returns it. A configured
`RuleCandidateFormationObserver` receives the immutable `RuleCandidate` and the
formation evidence that produced it.

In this implementation, “post-formation publication” means the synchronous
observer call with that candidate/evidence pair. There is deliberately no
separate `RuleCandidatePostFormationPublisher`, `RuleCandidateDiscoveredEvent`
or polynomial-classifying listener in this slice. Those names describe possible
later adapters, not types introduced here.

This is an integration seam, not a classification or promotion policy. The
default constructors use a private identity-stable no-op observer and therefore
preserve the previous product behavior without constructing unused strict
evidence. A later polynomial-theory integration must inject its observer
explicitly until the general exact classifier has replaced the historical
quartic classifier.

## Evidence

The observer receives four independent evidence axes:

- source-reported applied rule IDs used by the successful transformation paths;
- source path IDs as provenance;
- normalized assumptions;
- equivalence/validation evidence retained by the source paths.

For a configured observer, the evidence is immutable, rejects blank entries,
is de-duplicated in encounter order and is never written back into the candidate.
The default no-op boundary does not impose these additional evidence checks on
historic mining calls. Evidence remains separate from theory subsumption, project
inventory novelty, external novelty, cache utility and promotion status.

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

## Deliberate boundary

This change does not:

- classify candidates with the historical quartic-only
  `PolynomialTheorySubsumptionClassifier`;
- write to `PolynomialDerivedMacroCache`;
- write to the verifier-authorized executable transition cache;
- promote a candidate into the kernel, standard inventory or
  `DynamicCandidateRegistry`;
- enable any default search or Workbench policy.

The next polynomial integration slice must build an observer on the exact
parser → native engine → verifier → rendering/reparse authority. Only after that
classifier is qualified may automatic default routing be considered.
