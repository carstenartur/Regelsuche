# Exact-one-candidate paired task utility

## Purpose

`regelsuche.paired-task-utility/v1` executes the frozen downstream stream from Phase 2 of #384 twice for every task:

- once with the frozen `macro-primitives/v1` baseline inventory;
- once with that same inventory plus exactly one retained candidate.

The candidate is selected before the downstream stream is read. Selection uses TRAIN formation evidence only and follows the fixed policy:

`MAX_TRAIN_SUPPORT_THEN_CANONICAL_HASH_THEN_MACRO_ID`

The policy first maximizes the number of supporting frozen TRAIN traces. Stable canonical hash and macro ID are deterministic tie-breakers; no held-out target, outcome or resource result participates in selection.

## Exact-one inventory boundary

The earlier reusable-macro batch formed and enabled three macros per campaign. It remains useful historical evidence, but it cannot satisfy the exact-one comparison required by #384.

This phase therefore rebuilds a `FormationResult` containing only the selected candidate and passes that object to the existing production best-first evaluator. Every retained task records the single enabled candidate ID. The independent verifier rejects any row containing zero, two or more enabled candidates and rejects any learned-macro step whose ID is not the selected candidate.

## Frozen task binding

The runner consumes `regelsuche.downstream-task-stream/v1` rather than reconstructing or sorting tasks after evaluation. It retains the stream order and binds every row to:

- stream task content hash;
- source and target;
- assumptions;
- search depth and state budget;
- split and structural cluster;
- comparison policy.

The baseline and candidate-enabled searches use the same input, target, assumptions, primitive rules, search strategy and budget. The only inventory difference is the one selected candidate.

## Retained evidence

The canonical artifact contains:

- all four TRAIN replay records;
- all three formed candidate identities and lineage;
- the exact-one selection receipt;
- all twelve baseline and candidate-enabled searches;
- every outcome, including no-result, unchanged and regressing cases;
- per-task explored-state, generated-candidate and path-step differences;
- balanced configured, executed and remaining campaign resources;
- aggregate outcomes and resource differences.

Two clean executions are canonicalized and must be byte-identical.

## Independent verification

```bash
./gradlew verifyPairedTaskUtility
```

The independent verifier:

- validates the strict Draft 2020-12 schema;
- verifies all source and preregistration bindings;
- checks that formation and selection occur before the downstream stream is read;
- recomputes the TRAIN-only candidate choice;
- recomputes every task outcome from the retained search results;
- recomputes every per-task and aggregate resource difference;
- requires that all twelve frozen tasks remain in order;
- requires that the selected candidate is actually exercised;
- rejects an extra enabled candidate, outcome inflation, post-hoc candidate substitution, task reordering and publication authorization.

## Claim boundary

This artifact establishes paired computational utility for one deterministically selected candidate on one independently frozen task stream. It does not by itself establish complete lifecycle amortization.

The following remain separate phases of #384:

- complete validation, counterexample, project-novelty, formal-proof and qualification cost accounting;
- optional preregistered scalar cost profiles and sensitivity analysis;
- cumulative full-lifecycle break-even or `NO_BREAK_EVEN_OBSERVED` reporting;
- pinned-container reproduction of the final combined report.

Formal proof and external mathematical novelty therefore remain `NOT_EVALUATED`, and publication authorization remains false.
