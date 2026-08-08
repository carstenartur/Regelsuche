# TRAIN-only showcase candidate selection and freeze

Status: reversible pre-randomness stage for [#597](https://github.com/carstenartur/Regelsuche/issues/597)

## Purpose

This stage converts the retained terminal TRAIN population into two immutable
artifacts:

1. complete deterministic selection evidence;
2. the complete learned-program freeze that must exist before any public
   randomness or generated FINAL TEST case can become visible.

It accepts no VALIDATION result, drand value, generated case or FINAL TEST
outcome.

## Complete selection evidence

`ProofCarryingShowcaseCandidateSelection` retains every candidate from the
terminal population together with:

- exact and alpha-structural identities;
- genome, plan and final-evaluation roots;
- all raw TRAIN fitness components;
- TRAIN blockers and scalar fitness;
- exact and alpha seed-equivalence decisions;
- node count;
- composition and decision-topology facts;
- a conservative minimum primitive-path depth;
- explicit freeze blockers and eligibility.

The frozen ranking is:

```text
maximum TRAIN scalar fitness
then minimum program node count
then lexicographically minimum candidate hash
```

Only candidates satisfying every condition enter the ranking:

- no TRAIN blocker;
- not exact-equivalent to a seed;
- not alpha-equivalent to a seed;
- genuine composition topology;
- genuine decision topology;
- conservative minimum structural primitive path of at least three steps.

A terminal population without such a candidate fails before a randomness
not-before boundary is created. It cannot be repaired by inspecting future test
material.

## Structural interpretation

The structural analyzer is deliberately conservative:

- `Source` contributes one possible primitive step;
- `Sequence` is composition and sums child minima;
- `Repeat` is composition and multiplies the child minimum by
  `minIterations`;
- `Choice` and `FirstApplicable` are decisions and use the smallest child
  minimum;
- `Require`, `Prioritize` and `Prune` are decisions and preserve their child
  minimum.

This proves only a topology/path lower bound. A later positive showcase result
must still retain an actually executed successful path with at least three
primitive operations.

## Complete candidate freeze

`ProofCarryingShowcaseCandidateFreeze` binds:

- the fixed showcase and plan identity;
- exact repository commit;
- retained TRAIN population and selection roots;
- selected exact and alpha identities;
- human-readable learned-program hash;
- primitive inventory, work-budget and evaluator-protocol roots;
- every seed candidate identity;
- structural facts;
- freeze time;
- randomness not-before time;
- status `CANDIDATE_FROZEN_FINAL_TEST_UNSEEN`.

The v1 not-before boundary is at least 300 seconds after the freeze time. Both
the freeze constructor and the later seed-derivation verifier enforce the
delay. No round number is chosen here.

## Determinism and substitution controls

The freezer requires:

- retained population study hash equals the supplied frozen TRAIN study;
- retained seed roots equal the supplied seed candidates exactly;
- VALIDATION and FINAL TEST remain `NOT_EVALUATED`;
- every final candidate has a retained final evaluation;
- the selected candidate equals the deterministic eligible winner;
- the selected complete payload equals the selection identities.

Repeated construction from the same retained TRAIN evidence, repository commit,
inventory/work roots and freeze timestamp is byte-identical.

## Schemas

```text
docs/schemas/regelsuche-proof-carrying-showcase-candidate-selection-v1.schema.json
docs/schemas/regelsuche-proof-carrying-showcase-candidate-freeze-v1.schema.json
```

Both use JSON Schema Draft 2020-12 and reject unknown top-level fields. Runtime
records additionally recompute identities and cross-artifact relationships.

## Claim boundary

This stage does not:

- establish that the learned program improves search;
- fetch or verify drand;
- generate FINAL TEST;
- run any fixed baseline or ablation;
- establish `SHOWCASE_CONFIRMED`;
- establish external novelty, expert-reviewed importance or publication-grade
  benchmark validity.

After a real freeze has been persisted from a clean committed TRAIN run, the
next allowed stage is the pinned drand receipt producer. It may consume only the
first verified round strictly after the frozen not-before boundary.
