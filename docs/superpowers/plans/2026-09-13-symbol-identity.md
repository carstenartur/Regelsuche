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

- [x] Qualify and merge integration #1003, which preserves the exact source heads
  of #1001 and #1002; verify all initial PRs are merged. Start feature work from
  the resulting main. All repository protection rules remain required.
- [x] Add SymbolId and a bounded SymbolScope with aliases, explicit declarations,
  child scopes and restart-safe allocator snapshots. Reject noncanonical IDs,
  namespace collisions, ordinal reuse and partial allocation on invalid input.
- [x] Integrate IDs into VariableExpr and ExprValueFactory. Preserve legacy
  diagnostics and JSON. Test interning within a factory and stable equality
  across factories without depending on Java reference equality.
- [x] Add source-preserving scoped parsing and SymbolicExpression. Keep the
  original parser evidence; do not relabel certificates. Test every repeated
  occurrence, numeric provenance, immutable display renaming and actual existing
  normalizer/formatter/reparser round trips.
- [x] Add a bounded versioned document codec. Strictly decode UTF-8 and JSON;
  reject duplicate/unknown fields, trailing values, missing/unused bindings,
  malformed IDs and ambiguous display mappings. A document is not proof authority.
- [x] Add typed AST entry points to the existing RulePatternMatcher and exercise
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

## Implementation checkpoint after the merge

All initially open PRs #1001, #1002 and #1003 merged on 13 September 2026 through
merge commit `779bcfaebd7eaa0656202169953f69392e3586b0`. Its tree is exactly the
qualified #1003 head d80fdf tree `c6af6da4f1050bfceed96ecb7a967bc25e914ea6`.
CI run 34752823179 passed all six authorities; no protection was bypassed.
Production symbol changes began only after that merge. The newly appearing
draft #1004 is outside this initial merge set and is qualified independently.

The scoped Java input/document path is implemented and documented in
[scoped-symbol-identity.md](../../scoped-symbol-identity.md). The typed matcher
entrypoint is named `matchExpression`, avoiding ambiguity for existing null
string arguments. Legacy ordinary-name JSON and v2 keys remain characterized.
The `rsym_` identity-transport namespace is explicitly reserved.

Supplementary local verification uses Java 21.0.11: all 766 core tests, 40
e-graph tests and six matcher tests pass (812 distinct cases, no skips).
The original current-main core baseline passed 736 cases. The only main source
omitted from supplementary local compilation is the unchanged Java-25
`VerifiedPolynomialTransitionCacheStore`; no repository build exclusion was added.
The three original behavioral controls failed on the predecessor, then passed.
Eight deliberately broken implementations were detected by the symbol tests.
Missing-new-API compilation checks are recorded separately from behavioral reds.

Publication, independent review and fresh exact-head full Java-25 product CI are
not replaced by these local checks. No protected experiment was executed, no
assumption/proof checker was removed and no performance advantage is claimed.
