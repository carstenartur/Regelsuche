# Frozen VALIDATION selection for evolutionary search

Issue #220 separates evolutionary learning into three information domains:

1. **TRAIN** may mutate genomes, evaluate fitness and create resumable population checkpoints.
2. **VALIDATION** may choose one genome together with its bounded search parameters.
3. **FINAL TEST** may evaluate that already-frozen choice exactly once; it may not change the choice.

`EvolutionValidationSelection` implements the second boundary. Its canonical artifact uses schema
`regelsuche.evolution-validation-selection/v1` and is validated by
`docs/schemas/evolution-validation-selection-v1.schema.json` plus stricter runtime invariants.

## What is frozen

The artifact binds:

- the preregistered study-plan hash;
- the split-manifest hash;
- the completed TRAIN population-run hash;
- the VALIDATION-suite hash and its complete ordered case-id list;
- every evaluated genome/search-parameter configuration;
- paired baseline and candidate evidence for every VALIDATION case;
- terminal reasons, depths, explored states and candidate-evaluation counts;
- blockers and correctness regressions;
- the deterministic selection policy and selected configuration, or an explicit null result.

The selected unit is a **configuration**, not merely a genome. `maxDepth`,
`maxExpandedStates` and `maxCandidatesPerState` are therefore content-addressed together with the
genome and its alpha-structural identity.

## Deterministic policy

Only configurations without blockers and without correctness regressions are eligible. They are
ordered by:

1. more newly solved VALIDATION cases;
2. more reached VALIDATION targets;
3. fewer explored states;
4. fewer candidate evaluations;
5. the configuration hash as the final deterministic tie-break.

When no configuration is eligible, the artifact records
`selectionOutcome=NO_ELIGIBLE_CANDIDATE` and empty selected hashes. This is a valid transparent null
result; it is not replaced by a retry, a hand-picked fallback or FINAL-TEST inspection.

## Leakage barrier

The data model has no FINAL-TEST case field. Construction also requires all downstream statuses to
remain `NOT_EVALUATED`:

- `finalTestStatus`;
- `proofStatus`;
- `externalNoveltyStatus`;
- `promotionStatus`;
- `publicEvidenceStatus`.

A canonical JSON document with unknown properties, altered aggregate counts, an incomplete or
reordered VALIDATION case matrix, a substituted selected configuration, or any prematurely advanced
status is rejected.

## Split-family semantics

Several cases from one mathematical family may intentionally occur **inside the same split**. The
selection contract therefore requires unique case ids, not unique family names. Leakage prevention
belongs to the split manifest: one family may not cross TRAIN, VALIDATION and FINAL TEST boundaries.

## Current boundary and next step

This contract freezes VALIDATION evidence and selection. It does not claim that the FINAL TEST has
run. The following #220 slice consumes exactly one successful frozen selection, executes the FINAL
TEST once without replacement or retry, retains every final case and terminal reason, and then hands
the immutable evidence to the existing counterexample, novelty, proof, promotion and public-evidence
gates.
