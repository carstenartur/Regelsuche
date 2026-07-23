# Discovery cost ledger and bounded amortization reference

## Purpose

The `regelsuche.discovery-cost-ledger/v1` artifact maps the completed candidate-independent benchmark into an explicit vector resource taxonomy. It answers which resources were configured and executed without converting heterogeneous dimensions into one universal score.

The ledger is generated only from the canonical #383 evidence roots:

- finite-difference candidate-form execution;
- linear-recurrence candidate-form execution;
- rational-assumption formation and held-out evaluation;
- reusable-macro formation and paired held-out utility;
- the complete v2 benchmark execution index.

## Vector accounting

The authoritative dimensions are retained separately:

- explored states;
- generated successors where the production adapter retained them;
- candidate evaluations;
- proof attempts.

Configured, executed and remaining work are reported independently. A dimension without a configured bound remains `null` rather than receiving an invented limit.

The current frozen benchmark totals are:

| Dimension | Configured | Executed | Remaining |
|---|---:|---:|---:|
| Explored states | 36,000 | 756 | 35,244 |
| Candidate evaluations | 7,200 | 760 | 6,440 |
| Proof attempts | 1,200 | 0 | 1,200 |

Sequence formation and evaluation are retained as one combined meter because the existing adapters do not separate them. Rational formation is reconstructed only by subtracting independently balanced task ledgers from campaign totals. Macro formation and paired search are retained directly.

## Partial macro amortization reference

The reusable-macro challenge provides the only currently information-paired downstream stream in #383. Every one of the four campaigns has the same result:

- formation cost: 25 explored states and 13 candidate evaluations;
- twelve frozen downstream tasks;
- final downstream saving: 8 explored states and 28 candidate evaluations;
- candidate-evaluation break-even at task 1;
- no explored-state break-even within the frozen stream.

This is a dimension-dependent partial result, not an end-to-end discovery break-even. Counterexample, project-novelty, proof and qualification costs were not executed as separately metered stages in this benchmark. The authoritative overall status therefore remains:

`NOT_ESTABLISHED_INCOMPLETE_LIFECYCLE_COST_AND_SINGLE_CANDIDATE_STREAM`

## Verification

```bash
./gradlew verifyDiscoveryCostLedger
```

The checkout-local task generates two ledgers from the two clean challenge-run sets, requires byte-identical JSON, validates the strict Draft 2020-12 schema and independently recomputes:

- every challenge resource dimension;
- rational and macro stage splits;
- aggregate configured/executed/remaining values;
- all twelve cumulative macro savings;
- both dimension-specific break-even decisions;
- source-content bindings and root hashes.

Mutated publication authorization and inflated overall break-even claims are rejected.

## Claim boundary

This phase establishes resource accounting and one bounded candidate reference only. It does not complete #384. A full end-to-end result still requires independently frozen downstream streams for retained candidates, complete validation/counterexample/novelty/proof/qualification costs, weighted-profile preregistration where used, sensitivity analysis and a final reproducible `BREAK_EVEN_OBSERVED` or `NO_BREAK_EVEN_OBSERVED` decision for the complete lifecycle.
