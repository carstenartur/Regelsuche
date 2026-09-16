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
- [x] Run current Java-25 focused tests and retain their exact commands/results.
- [ ] Publish a separate PR from merged #1008, request review and inspect ordinary CI.

Relevant files: `TraceBindingModel.java`, `TraceStrategyDispatchLearner.java`,
`TraceBindingModelTest.java`, `BindingAwareStrategyDispatchTest.java`, and
`docs/binding-aware-strategy-dispatch.md`.
