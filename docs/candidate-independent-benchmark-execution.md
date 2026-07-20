# Candidate-independent benchmark execution

Issue #383 evaluates whether Regelsuche can form and evaluate executable rules on a corpus that was frozen independently of any retained production candidate. The benchmark source and selected challenge portfolio are already preregistered. This document defines the first execution slice built on that immutable input.

## Authoritative command

Run the complete checkout-local contract with:

```bash
./gradlew verifyCandidateIndependentExecutionFoundation
```

The task is also part of:

```bash
./gradlew check
./gradlew fullCheck
```

GitHub Actions does not define cases, expected outcomes, metrics, visibility rules or hashes. Central CI only invokes the repository lifecycle and retains `build/reports/**`.

## Frozen execution matrix

The preregistration contains three selected challenge classes:

- `rational-assumption-rewrites`;
- `finite-difference-recurrences`;
- `reusable-search-macros`.

Each class contains two TRAIN, two VALIDATION and two TEST cases. The frozen budget configures four campaigns per challenge. The execution foundation therefore materializes:

- 12 campaign records;
- 72 campaign/case evaluation records;
- 24 case evaluations per challenge;
- complete coverage of every configured campaign against every case of the same challenge.

There is no row dropping. A later adapter must replace the status of each exact record rather than create a smaller success-only result set.

## Generated artifacts

Two isolated generations are written below:

```text
build/reports/candidate-independent-execution/
  first/
  second/
```

Each tree contains:

```text
campaign-batch.json
benchmark-report.json
benchmark-run.json
case-evaluations/
  <campaign-id>--<case-id>.json
```

The independent verifier requires identical file membership and byte content across both generations.

### Campaign batch

`regelsuche.candidate-independent-campaign-batch/v1` binds:

- benchmark, portfolio and source identities;
- all 12 deterministic campaign IDs and seeds;
- exact same-challenge case membership;
- TRAIN-visible and held-out case identities;
- state, candidate-evaluation and proof budgets;
- explicit `CONFIGURED_NOT_EXECUTED` and `INCOMPLETE` status.

### Case evaluation

`regelsuche.candidate-independent-case-evaluation/v1` binds one campaign and one frozen case. TRAIN formation visibility is `ALLOWED`; VALIDATION and TEST formation visibility is `PROHIBITED`.

Until a real challenge adapter executes the record, it must remain:

- `candidateFormationStatus: NOT_RUN`;
- `heldOutEvaluationStatus: NOT_RUN`;
- `executionStatus: NOT_EXECUTED`;
- `outcome: INCOMPLETE`;
- zero resource use;
- `publicationEligible: false`;
- reason `EXECUTION_ADAPTER_NOT_IMPLEMENTED`.

An absent row is not equivalent to an incomplete row.

### Benchmark report

`regelsuche.candidate-independent-benchmark-report/v1` retains all 72 case-evaluation roots and challenge coverage. Every scientific metric remains `NOT_MEASURED`; no zero value is substituted for missing execution.

### Benchmark run

`regelsuche.candidate-independent-benchmark-run/v1` binds:

- source semantic and exact-byte identities;
- campaign-batch and report identities;
- the complete ordered case-evaluation file inventory;
- repository/environment identity;
- `GENERATED_NOT_EVALUATED` status;
- `externalNoveltyStatus: NOT_EVALUATED`;
- `publicationAuthorized: false`.

## Independent verification

`scripts/verify-candidate-independent-execution-foundation.py`:

1. validates all four Draft 2020-12 schemas;
2. rejects duplicate JSON fields and symbolic or non-regular bundle members;
3. compares two clean generations byte for byte;
4. reconstructs the 12-campaign and 72-evaluation matrix from the frozen source;
5. verifies campaign seeds, budgets, case sets and TRAIN/held-out boundaries;
6. binds every case evaluation to its campaign and frozen case;
7. independently recomputes report and run inventories and hash chains;
8. proves that a missing evaluation, TEST formation exposure and an unbacked accepted outcome fail closed.

The retained verification report is written to:

```text
build/reports/candidate-independent-execution/verification/
```

## Claim boundary

This slice establishes complete, deterministic and fail-closed **pre-execution accounting**. It does not establish that any campaign ran, formed a candidate, solved a held-out case, improved search, proved a rule, or discovered externally novel mathematics.

The only authorized status is:

```text
VERIFIED_INCOMPLETE_EXECUTION_FOUNDATION
```

## Next execution slices

The frozen source must not be edited after results are observed. Subsequent work will replace incomplete records through challenge-specific production adapters:

1. finite-difference and recurrence campaigns using the generic sequence domain and retained holdout evaluator;
2. assumption-sensitive rational rewrite campaigns using the normalized assumption and symbolic/counterexample boundary;
3. reusable macro campaigns using real replay formation and paired held-out search utility;
4. aggregate recomputation from raw records only;
5. two clean evaluated runs and one pinned-container reproduction.

Null, rejected, disproved, timeout, unsupported and incomplete outcomes remain first-class results throughout those phases.
