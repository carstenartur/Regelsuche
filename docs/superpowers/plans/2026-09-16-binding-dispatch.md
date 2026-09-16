# Binding-aware strategy dispatch

**Goal:** Use shared multi-state bindings in actual continuation trials and execution,
without replacing the historical v1 control.

**Spec:** `docs/superpowers/specs/2026-09-16-binding-aware-learning.md`.

## Design and boundaries

A package-private `TraceBindingModel` generalizes pairs of observed selection-TRAIN
primitive paths with one expression-pair/placeholder map across every state.
Only formation-admitted gene sequences can contribute. A separately versioned
`trainBindingAware` entry point retains the current strict measured-utility gate.

At application time an expansion-local matching allowance covers all prefix and
complete-path tests. A prefix never locks one substitution: full-path matching
backtracks across all states. A mismatch or cutoff uses the existing primitive
fallback. The original rule engine still generates every retained primitive
step and its source-bound provenance. No template is a correctness authority.

This first integration generalizes whole-state trajectories, not arbitrary new
occurrence placements. CLI/HTTP migration and general tactic learning remain
outside this change. No frozen corpus, reference, policy default, proof check,
existing work threshold or CI gate is changed. The v1 policy JSON/hash and enum
remain unchanged. No learning gain is promised if matching costs defeat utility.

## Implementation tasks

- [x] Add tests for pairwise trajectory abstraction, later binding constraints,
  scoped symbols, limits, deterministic order and rejected non-sequences.
- [x] Observe RED with a minimal compiling helper, implement the model, then GREEN.
- [x] Add a v1 hash regression and actual v2 dispatch/replay/budget fallback tests.
- [x] Integrate model formation and matching work into the current TRAIN trials.
- [x] Run Java-25 focused tests on the original implementation and retain its commands/results.
- [x] Publish PR #1009 from merged #1008 and inspect its original CI and review.
- [ ] Complete ordinary current-head CI and follow-up review after the integrity fixes below.

Relevant files: `TraceBindingModel.java`, `TraceStrategyDispatchLearner.java`,
`TraceBindingModelTest.java`, `BindingAwareStrategyDispatchTest.java`, and
`docs/binding-aware-strategy-dispatch.md`.

## Review hardening: exact literals and structural evidence

The current implementation must preserve equal exact literals, including large
integers and fractions, without rejecting otherwise supported trajectory pairs.
Use immutable fixed-literal seeds with the existing bounded matcher. Free
parameters remain separate and may not first appear after the initial state.

Template keys and supporting trace hashes must bind the actual ordered tree,
including associative grouping and function arity. `TraceBindingIdentity`
provides a typed preorder encoding. The binding-model revision advances to v2;
the old dispatcher identity, matcher's revision and proof authority do not change.

- [x] Publish nine predecessor integrity tests in `4c7c095`.
- [x] Observe actual RED: ordinary CI `35092576139`, Maven job `104782141531`,
  742 learning tests, six expected failures, zero errors/skips.
- [x] Implement fixed seeds and exact typed structural encoding in `ba2575e`.
- [x] Observe the source-pinned learning/dependency compile/test step pass in `35093740379`.
- [x] Add seed immutability, later constants, unequal-number generalization,
  function-boundary and exact-literal normalization controls.
- [ ] Verify the additional controls and current-head full CI before merge.
- [ ] Resolve both original review threads with regression evidence and request a fresh review.

Reproduce the module and its dependencies, without suppressing unrelated tests:

```sh
mvn --batch-mode --no-transfer-progress -Pfull -pl regelsuche-learning -am test
```

The source-pinned supplemental workflow is confined to a separate QA branch;
it is not in this PR and is not a replacement for ordinary full CI. Local
execution is unavailable during this review-fix session.
