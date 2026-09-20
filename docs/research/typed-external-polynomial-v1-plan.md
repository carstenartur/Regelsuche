# Typed external polynomial pilot: design and implementation plan

**Goal:** Connect the actual typed trace learner and TRAIN-selected policies to a source-only external comparison, retaining the complete cost of learning, unsuccessful search, replay and independent verification.

**Architecture:** Extend the existing typed adapter with an explicit source-only context; do not invent an unreachable target or replace MoveSearch. Reuse the existing trace learner, policy training, primitive regeneration, independent Fraction polynomial judge, native SymPy competitors and process supervisor. Keep the historical external pilot and all protected studies unchanged.

**Environment:** Production Java 25 and existing pinned dependencies; SymPy 1.14.0. Local Java 21 subset checks, when used, are development checks and must not be described as the supported full build.

## Frozen experiment contract

`config/benchmarks/typed-external-polynomial-v1.json` is committed before implementation or evaluation. Its 19 sources include 16 polynomial cases and three unsupported controls. Seven profiles, two logical work budgets and three repetitions produce 798 rows, not 798 independent mathematical tasks. Controls and familiar families are intentional; the new combinations are not an independently curated or blinded family benchmark. No protected FINAL TEST is read.

The fixed controls receive exactly the same primitive rules as the learner. TYPED_PRIMITIVE uses their declared order; TYPED_REORDERED uses the fixed order recorded in the protocol. Neither runs the learner. Selected policies use existing TRAIN endpoints discovered by the target-free trace learner, never evaluation endpoints. The learned fixed profile pays formation but not policy selection. Each selected profile pays formation, its own TRAIN searches, memory updates and every profile-selection trial. Setup and compilation CPU are retained independently of logical work. No ranking or macro is selected after evaluating this corpus.

Each Java worker is initialized for one profile before any evaluation request. The supervisor writes the complete returned model/evidence and its hash before sending evaluation inputs. The model remains immutable in memory, with fresh per-request ranking caches. This is a procedural in-process freeze, not a claim that an imported model alone has proof authority. Every application must regenerate its primitive path. Only source, profile and declared budget enter requests; reject targets and other extra fields.

The surface arithmetic objective and independent exact shared-fragment verifier are unchanged from the historical pilot. Search may return its best admitted incumbent, but any overrun including the additional selected-path replay rejects a budget-respecting success claim. Export every event and receipt, even when no improvement is found. Common request deadlines include transport, output parsing and exact verification. Native CPU, request wall time, setup/TRAIN, warmup and all failure rows remain separate. Report quality on all tasks before describing timing on explicitly identified equal-quality subsets. Report cumulative totals, not a fabricated universal break-even estimate.

## Implementation steps

- [ ] Add regression tests for an explicit typed source-only context, including no false target hit, retained scoped/exact ASTs, preserved assumptions, and unchanged target-directed/null-rejection contracts. Add `Context.sourceOnly(assumptions, phase)` and encode absence only at the existing MoveContext boundary.
- [ ] Reuse primitive preparation without invoking TRAIN for fixed controls. Test equality with the learner's primitive providers, zero formation cost for fixed controls and unchanged existing typed study artifacts. Share the existing TRAIN helper without changing its protocol or outputs.
- [ ] Add TypedExternalPolynomialWorker: initialize once per profile, freeze full evidence, reject extra request fields and TRAIN overlap, search without a target, choose a deterministic lowest-cost admitted state, regenerate the selected path and account for its cost. Test real learned execution, rejected tampered requests and over-budget results.
- [ ] Add a separate Python runner importing the existing supervisor, judge and native competitors. Verify the protocol hash, full matrix, source-only payloads, model hashes, all cost components, truthful failure handling, and cumulative per-profile costs. Refuse overwriting output. Test malformed/tampered/incomplete bundles and negative results.
- [ ] Run the frozen pilot only after the harness tests pass. Retain complete raw evidence and a compact report, including unfavourable results; do not revise the corpus, models, budgets or algorithm based on this run.
- [ ] Add the new runner and tests to the existing external comparison CI lane without replacing or weakening any existing job, threshold or historical result. Run affected tests, inspect the final diff and open a PR with exact verification boundaries.

## Acceptance and review focus

Success of this implementation means an executable, honest comparison, not a predetermined competitive win. Review especially source-only information flow, fixed controls that really avoid TRAIN, primitive-depth parity, identity transport, model immutability, selected-path replay cost, initialization failure rows, logical overruns, complete matrices, and setup amortization. A hosted green build does not establish superiority; a faster row does not establish amortized benefit.

The separate premise-admission fix #1039 and optional rule-shape index #1041 remain separate changes. This experiment's learner admits unconditional polynomial rules only; no claim about conditional-rule safety or index performance is added here.
