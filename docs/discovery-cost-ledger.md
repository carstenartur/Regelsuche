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

The frozen benchmark inputs and accounting policy are unchanged. Under the corrected single-pass signed-term canonicalizer, the reproducible totals are:

| Dimension | Configured | Executed | Remaining |
|---|---:|---:|---:|
| Explored states | 36,000 | 744 | 35,256 |
| Candidate evaluations | 7,200 | 760 | 6,440 |
| Proof attempts | 1,200 | 0 | 1,200 |

Sequence formation and evaluation are retained as one combined meter because the existing adapters do not separate them. Rational formation is reconstructed only by subtracting independently balanced task ledgers from campaign totals. Macro formation and paired search are retained directly.

## Partial macro amortization reference

The reusable-macro challenge provides the only currently information-paired downstream stream in #383. Every one of the four campaigns has the same result:

- formation cost: 22 explored states and 13 candidate evaluations;
- twelve frozen downstream tasks;
- final downstream saving: 8 explored states and 28 candidate evaluations;
- candidate-evaluation break-even at task 1;
- no explored-state break-even within the frozen stream.

The single-pass canonicalizer correction reduced the measured formation cost from 25 to 22 explored states per campaign and the aggregate benchmark total from 756 to 744 explored states. Candidate-evaluation and proof-attempt totals are unchanged. This is an engine-semantics correction, not a change to the benchmark sources, budgets, task stream or vector-accounting policy.

The exact formation measurements are intentionally not encoded as JSON-Schema constants. The schema enforces their structure and non-negative domain; the independent verifier reconstructs both values from the bound reusable-macro campaign evidence. Historical v1 artifacts therefore remain schema-valid, while every current run is still required to match its own source evidence exactly.

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
- the reusable-macro formation cost;
- all twelve cumulative macro savings;
- both dimension-specific break-even decisions;
- source-content bindings and root hashes.

Mutated publication authorization and inflated overall break-even claims are rejected.

## Claim boundary

This phase establishes resource accounting and one bounded candidate reference only. It does not complete #384. A full end-to-end result still requires independently frozen downstream streams for retained candidates, complete validation/counterexample/novelty/proof/qualification costs, weighted-profile preregistration where used, sensitivity analysis and a final reproducible `BREAK_EVEN_OBSERVED` or `NO_BREAK_EVEN_OBSERVED` decision for the complete lifecycle.
