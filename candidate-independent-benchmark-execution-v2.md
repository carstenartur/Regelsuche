# Candidate-independent benchmark execution index v2

## Purpose

The v1 execution foundation remains an immutable `NOT_STARTED` accounting
artifact. This v2 index binds that frozen 12-campaign / 72-case-slot matrix to
the three post-freeze canonical challenge executions:

- finite-difference and linear-recurrence candidate forms;
- assumption-aware rational rewrites;
- reusable macros with paired production best-first utility.

It does not rewrite or replace the preregistration. It proves that every frozen
challenge is represented by a byte-reproducible First/Second run pair and that
the challenge-native evidence covers the complete frozen matrix.

## Authoritative command

```bash
./gradlew verifyCandidateIndependentBenchmarkExecutionV2
```

The checkout-local contract:

1. regenerates the unchanged v1 foundation twice;
2. executes the two finite-sequence forms and their retained-conflict aggregate;
3. executes the rational adapter twice;
4. executes the reusable-macro batch twice;
5. generates the v2 index twice and requires byte-identical output;
6. validates all four challenge-specific schemas and the v2 schema;
7. independently recomputes campaign, case-slot and detailed-row coverage;
8. rejects missing challenges, missing campaigns, foundation rebinding,
   unknown fields and publication overclaims;
9. runs as part of root `check` and therefore through the pinned independent
   reproduction container used by `fullCheck`.

## Coverage accounting

The only dimensions aggregated across all challenges are dimensions with the
same scientific meaning:

| Dimension | Value |
|---|---:|
| configured campaigns | 12 |
| executed campaigns | 12 |
| frozen case slots | 72 |
| executed case slots | 72 |
| successful case slots | 52 |
| retained no-result case slots | 20 |
| correctness regressions | 0 |

The challenge-native detailed evidence contains 120 primary rows:

- 24 sequence case aggregates, with 48 candidate-form subrows retained inside them;
- 48 rational task evaluations;
- 48 paired macro task evaluations.

These rows are deliberately not turned into one cross-domain success rate,
because their units differ. The v2 index retains challenge-specific summaries
and the complete source-run content roots.

## Claim boundary

A green v2 execution establishes complete, reproducible execution of the frozen
candidate-independent benchmark adapters. It does not establish:

- formal proof at the benchmark aggregate level;
- external mathematical novelty;
- qualified expert interestingness;
- superiority over information-equivalent baselines;
- amortization of discovery cost;
- publication authorization.

Those claims remain assigned to their independent issues and evidence gates.
