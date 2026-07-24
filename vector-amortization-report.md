# Vector-only amortization report

## Purpose

`regelsuche.amortization-report/v1` combines the Phase-1 discovery-cost ledger with the Phase-3 exact-one paired utility stream without converting heterogeneous resources into one universal score.

The authoritative profile is frozen as:

`vector-only-no-conversion/v1`

It retains explored states and candidate evaluations as separate dimensions. The profile contains no scalar weights, and the report therefore has no scalar break-even decision.

## Source bindings

Every report is bound to:

- the canonical discovery-cost ledger content hash;
- the exact-one paired-task utility content hash;
- the independently frozen downstream task-stream content hash;
- the frozen vector-only profile content hash;
- the selected candidate identity and content hash;
- the repository revision used for generation.

The Phase-1 macro formation cost must equal the formation cost retained by the exact-one paired run. Any mismatch fails generation and independent verification.

## Authoritative vector accounting

The current report computes two dimensions independently:

- `EXPLORED_STATES`: TRAIN formation states versus the per-task difference between baseline and candidate-enabled expanded states;
- `CANDIDATE_EVALUATIONS`: TRAIN formation candidate evaluations versus the per-task difference in best-first generated transformations, using the mapping already declared by the Phase-1 ledger.

For each dimension the report retains all twelve tasks in frozen stream order and records:

- per-task saving or regression;
- cumulative saving;
- cumulative net saving after formation cost;
- whether the current cumulative value covers formation cost;
- the first observed frozen-order break-even index, or `NO_BREAK_EVEN_OBSERVED`;
- the final cumulative and net saving.

Path-step savings are retained as a diagnostic only. They are not treated as a discovery-cost dimension.

## Ordering sensitivity

The frozen downstream order remains authoritative. To expose ordering sensitivity without selecting a favorable order after observing outcomes, the report also computes deterministic bounds:

- best-case permutation: descending per-task saving with task ID as a stable tie-breaker;
- worst-case permutation: ascending per-task saving with task ID as a stable tie-breaker.

These sensitivity orders never replace the preregistered stream order. Final total saving is checked as order-invariant.

## Complete-lifecycle boundary

A dimension-specific crossing based on formation cost does not establish end-to-end amortization. The Phase-1 ledger still marks validation, counterexample, project-novelty, formal-proof or qualification stages as embedded, unexecuted or configured but not executed rather than completely and separately metered.

The authoritative overall status is therefore fixed to:

`NOT_ESTABLISHED_INCOMPLETE_LIFECYCLE_COST`

The schema rejects promotion to a complete-lifecycle `BREAK_EVEN_OBSERVED` result. Formal proof and external novelty remain `NOT_EVALUATED`, and publication authorization remains false.

## Reproduction

```bash
./gradlew verifyVectorAmortizationReport
```

The checkout-local lifecycle:

1. verifies the Phase-1 cost ledger and Phase-3 paired utility;
2. generates two reports and two run receipts;
3. requires byte-identical clean outputs;
4. validates the strict Draft 2020-12 profile, report and run schemas;
5. independently recomputes every cumulative row, break-even decision, sensitivity order and source binding;
6. rejects task-saving changes, frozen-order changes, lifecycle-coverage inflation, complete-lifecycle break-even inflation, scalar weights in the vector-only profile and publication authorization.

## Remaining work for #384

This phase establishes the authoritative vector calculation over currently metered formation and downstream search resources. It intentionally does not close #384.

A complete final result still requires:

- separate execution and accounting for all required validation, counterexample, project-novelty, formal-proof and qualification costs;
- configured, executed, skipped and remaining balance for every newly covered resource role;
- optional preregistered scalar profiles with weight sensitivity, when such profiles are introduced;
- one pinned-container reproduction of the combined canonical report;
- a final complete-lifecycle `BREAK_EVEN_OBSERVED` or `NO_BREAK_EVEN_OBSERVED` decision.
