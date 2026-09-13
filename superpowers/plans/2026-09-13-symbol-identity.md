# Scoped symbol identity implementation plan

## Goal and authorization

The user requests a plan, merging the currently open PRs, then implementation of
symbol identity independent of display names. Preserve mathematical capabilities,
primitive proofs, occurrence provenance and historical evidence. This document
records the plan only; it does not claim implementation or benchmark improvement.

## Design

Use an immutable identity comprising an allocation-scope UUID and local ordinal.
Names are resolved at the input boundary. Distinct AST occurrences refer to the
same symbol identity but retain their own source positions. The existing
owner-scoped ExprValueFactory remains the value interner. Mathematical equality
uses IDs, not display labels or process-local object addresses; alpha-renaming and
equality under assumptions remain separate relations.

Existing text-based engines receive reserved canonical symbol identifiers, not
display names. The parser restores those IDs when reparsing. New typed callers use
symbol(); legacy name() remains the mathematical identifier accessor. A bounded
SymbolicExpression retains the original ExactParsedTerm, occurrence mappings,
source bindings and independent presentation labels. Renaming a display label
must not alter equality, value keys or identity transport.

Preserve historical unscoped text/JSON and v2 value keys. Scoped values and their
parents use an additive v3 key revision. The new scoped input path is explicit;
a wholesale default CLI/HTTP migration requires a separately versioned request
and replay contract and is not silently claimed here. Do not add a parallel
search engine or global mutable symbol registry.

## Ordered implementation

- [ ] Qualify and merge integration #1003, which preserves the exact source heads
  of #1001 and #1002; verify all initial PRs are merged. Start feature work from
  the resulting main. All repository protection rules remain required.
- [ ] Add SymbolId and a bounded SymbolScope with aliases, explicit declarations,
  child scopes and restart-safe allocator snapshots. Reject noncanonical IDs,
  namespace collisions, ordinal reuse and partial allocation on invalid input.
- [ ] Integrate IDs into VariableExpr and ExprValueFactory. Preserve legacy
  diagnostics and JSON. Test interning within a factory and stable equality
  across factories without depending on Java reference equality.
- [ ] Add source-preserving scoped parsing and SymbolicExpression. Keep the
  original parser evidence; do not relabel certificates. Test every repeated
  occurrence, numeric provenance, immutable display renaming and actual existing
  normalizer/formatter/reparser round trips.
- [ ] Add a bounded versioned document codec. Strictly decode UTF-8 and JSON;
  reject duplicate/unknown fields, trailing values, missing/unused bindings,
  malformed IDs and ambiguous display mappings. A document is not proof authority.
- [ ] Add typed AST entry points to the existing RulePatternMatcher and exercise
  existing e-graph insertion/extraction. Test same-symbol versus distinct-symbol
  cancellation, aliases, identical labels in distinct scopes, renamed displays
  and composite placeholder bindings.
- [ ] Document the API and its explicit product boundary, run regression and
  adversarial tests, publish the tested patch, read back the files and require
  fresh exact-head Java-25 CI plus review.

## Verification rules

Write failing tests before production changes. Retain the baseline core test
results, then run unchanged core tests and focused learning/e-graph integration.
Report actual runtime/module coverage: a Java-21 partial local reconstruction is
not a full Java-25 Gradle/Maven/Docker build. Full hosted authorities remain required.

No checker, proof, work charge, CI threshold, budget or protected study is removed
or relaxed. Do not execute a protected FINAL TEST or precision-study retry. Symbol
identity alone does not repair the rule-ID-only strategy dispatcher: it must still
retain or test the cross-step binding relationship. No speedup over SymPy is
claimed by this architecture change.

## Merge checkpoint

At plan publication, head 42f5050a28d07fe81bef6fe897b7694699d8f79f passed all six
ordinary authorities in run 34748910602, but GitHub refused merge because the
Java CodeQL configuration had no completed analysis. Its autobuild failed in
GraalPy resource processing with `artifact.downloadAttestation is not a function`.
GitHub also rejected both targeted job and failed-run retry requests. This
planning commit changes no build/runtime policy; the newly published head must
receive its own normal CI and CodeQL qualification before merge.
