# Proof-carrying self-improvement showcase v1 — terminal result

Tracked by: [#597](https://github.com/carstenartur/Regelsuche/issues/597)

Status: **terminal TRAIN candidate-formation null attempt; no candidate freeze, no public randomness, no FINAL TEST**

## Immutable execution identity

The one-attempt authority was consumed exactly once:

- implementation commit: `5bc037de75053aadaa0924eaa0628a65889957b7`;
- authority commit: `eaf0d7ac4ef9ee131fa41ff88d692f7562463e3e`;
- authority branch: `showcase/train-freeze-authority-v1`;
- GitHub Actions run: `31283046296` (CI #2708);
- run attempt: `1`.

The retained workflow artifact is:

`proof-carrying-showcase-train-freeze-eaf0d7ac4ef9ee131fa41ff88d692f7562463e3e-31283046296`

with SHA-256 digest:

`29b5479bc825525cf2c2eaf6de87050b60723f5be68d9afdc0347146471a8b22`

The authority must not be rerun, recreated or substituted.

## What happened

The complete checkout-owned `ciCheck` succeeded. The authority then entered the
real TRAIN/freeze application path. TRAIN reached deterministic candidate
selection, but no terminal TRAIN alternative satisfied the already frozen
candidate-freeze policy.

The terminal exception was:

```text
java.lang.IllegalArgumentException:
candidate selection requires an eligible TRAIN alternative
```

It originated in
`ProofCarryingShowcaseCandidateFreezer.CandidateSelection.selected()` before
`ProofCarryingShowcaseTrainAndFreezeCommand.write()` was reached.

The result is therefore not a CI failure masquerading as an experiment result:
the ordinary repository gate passed, and the experiment-specific TRAIN
selection itself produced the null boundary condition.

## No candidate freeze exists

Forensic inspection of the retained artifact found no:

- `candidate-freeze.json`;
- `execution-receipt.json`;
- `protocol-bound-train-run.json`;
- `candidate-selection.json`;
- `selected-candidate.json`;
- `selected-program.regelsuche`.

The production command publishes the TRAIN/freeze bundle only after successful
selection and freezes it as one atomic directory. Because selection failed
first, no partial freeze is externally visible.

Consequently:

```text
candidate freeze:     NOT CREATED
public randomness:   NOT CONSUMED
FINAL TEST seed:     NOT DERIVED
FINAL TEST cases:    NOT GENERATED
FINAL TEST execution: NOT RUN
```

A live drand round must not be fetched for v1 because the preregistered ordering
requires a valid candidate freeze before public randomness.

## Frozen eligibility policy

A terminal TRAIN candidate was eligible only if all of these held:

1. no TRAIN evaluator blockers;
2. not exact-equivalent to a seed;
3. not alpha-structurally equivalent to a seed;
4. composition topology present;
5. decision topology present;
6. minimum structural primitive path depth at least three.

Both committed seeds intentionally failed this policy. The all-primitives seed
is a single `Source`, so it has neither composition nor decision topology. The
`FirstApplicable` seed has decision topology, but its alternatives are `Source`
nodes, so it lacks composition topology.

The deterministic mutator can introduce composition by prepending/appending a
source. Starting from the decision seed, one such mutation yields
composition+decision with structural primitive path depth two; at least one
further topology mutation is needed to reach the frozen depth-three threshold.
The retained v1 artifact does not contain terminal per-candidate selection
evidence, so it is not possible to prove after the fact which blocker or
combination of blockers affected every terminal alternative. Re-running TRAIN
to reconstruct that evidence would violate the one-attempt v1 boundary and is
therefore not done.

## Engineering lesson

The repository tests before v1 verified that the production TRAIN
configuration was schema/protocol compatible and that successful publication
would be atomic. They did not execute the exact deterministic production
TRAIN→selection path to establish that at least one terminal alternative was
freeze-eligible.

The failure also showed that null selection evidence must be a first-class
artifact. Throwing before publication discarded the terminal alternatives and
their exact freeze blockers from the retained workflow evidence.

These are engineering issues, not reasons to alter the v1 thresholds or retry
the run. Follow-up work is tracked in
[#609](https://github.com/carstenartur/Regelsuche/issues/609):

- deterministic TRAIN-only preflight before a future one-attempt authority;
- content-addressed selection evidence that represents both eligible and null
  outcomes;
- complete retention of terminal alternatives and blockers;
- no candidate freeze or public-randomness boundary for a null selection.

## Interpretation

v1 does **not** show that learned rewrite programs fail on the intended hidden
FINAL TEST: no hidden test was generated or observed. It shows a narrower and
useful result: under the preregistered TRAIN configuration and strict candidate
formation policy, the one authorized run did not produce a terminal candidate
eligible to cross the freeze boundary.

That distinction is important. Any later showcase must be a new preregistered
version, with its own authority, and must preserve this v1 record unchanged.
