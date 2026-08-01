# Frozen flagship baselines and ablations

Status: pre-execution comparison-plan contract for #533 and #521

`EvolutionRewriteProgramBaselineAblationPlan` fixes every comparison track before
TRAIN, VALIDATION or FINAL TEST results can influence its configuration.

## Shared comparison surface

Every track is bound to the same:

- split manifest and information surface;
- evaluation protocol;
- primitive inventory;
- program grammar and mutation catalog;
- primitive and total-work budget policy;
- matched-work accounting contract.

A track may differ only through its declared kind and content-addressed
implementation/configuration identities. No track may silently receive a wider
case surface, a cheaper work ledger or a weaker correctness evaluator.

## Required tracks

The plan requires exactly one of each:

1. fixed primitive best-first search;
2. equality saturation on the declared shared fragment;
3. randomized valid rewrite program with a frozen random seed;
4. mutation-only candidate search without topology evolution;
5. a hand-written program frozen before results;
6. no-composition ablation;
7. fixed-guard ablation without weakening correctness validation;
8. a flattened-program outer-search control.

Only the randomized track carries `randomSeed`. Only the hand-written track
carries `candidateProgramHash`. Every other semantic choice is part of the
track's immutable `configurationHash`.

## Canonical identity

Tracks are sorted by `TrackKind`; duplicate kinds and IDs are rejected. The plan
stores only hashes and `NOT_STARTED` state. It neither executes a baseline nor
contains a result field.

Changing any implementation, configuration, shared information surface or work
policy changes the plan identity or causes `requireInputs` to fail.

## Optimization boundary

Performance optimizations may be used within a track only when the frozen
implementation/configuration identity names them and they preserve canonical
evidence and matched-work metrics. Faster hardware or an optimized backend does
not grant a different mechanical budget.

## Claim boundary

This contract establishes fair, preregistered comparison identities. It does not
run the tracks, select a candidate, expose held-out data or establish that any
baseline or learned program is superior.
