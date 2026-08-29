# Rule-candidate post-formation boundary

## Purpose

`RuleCandidateMiner` now has one explicit boundary after a candidate has been
fully formed and before the public mining call returns it. A configured
`RuleCandidateFormationObserver` receives the immutable `RuleCandidate` and the
formation evidence that produced it.

This is an integration seam, not a classification or promotion policy. The
default constructors use `RuleCandidateFormationObserver.none()` and therefore
preserve the previous product behavior. A later polynomial-theory integration
must inject its observer explicitly until the general exact classifier has
replaced the historical quartic classifier.

## Evidence

The observer receives four independent evidence axes:

- primitive rule IDs used by the successful transformation paths;
- source path IDs as provenance;
- normalized assumptions;
- equivalence/validation evidence retained by the source paths.

The evidence is immutable, de-duplicated in encounter order and never written
back into the candidate. It remains separate from theory subsumption, project
inventory novelty, external novelty, cache utility and promotion status.

## Exactly-once boundary

For ordinary clustered mining, each candidate returned by `mine(...)` is
observed once after bucket de-duplication and threshold filtering.

For bulk single-path mining, candidate formation happens without callbacks,
then equal canonical candidates are de-duplicated, their evidence is merged in
encounter order, and the observer is called once per returned candidate. This
prevents duplicate routing when multiple source paths generalize to the same
schema.

Rejected, unverified or otherwise unformed paths do not reach the observer.
An observer failure propagates: the lifecycle fails closed rather than returning
a candidate while pretending that configured post-formation processing
succeeded.

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
