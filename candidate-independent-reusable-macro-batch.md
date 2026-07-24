# Candidate-independent reusable-macro batch

## Purpose

This execution completes the evaluated adapter for the frozen
`reusable-search-macros` challenge. It uses the production replay, target-free
formation and paired best-first utility components. The frozen case corpus and
primitive profile are read without modification.

## Authoritative command

```bash
./gradlew verifyCandidateIndependentReusableMacroBatch
```

The repository-owned Gradle task:

1. independently verifies the frozen corpus and formation profile;
2. executes four deterministic campaigns;
3. reproduces all four TRAIN traces without exposing held-out evaluation input;
4. forms exactly three reusable macros;
5. evaluates all twelve frozen tasks with the production `BestFirstSearchStrategy` twice;
6. changes only the addition of the formed macros between baseline and candidate runs;
7. retains complete paths, rules, terminal outcomes and resource use;
8. repeats the complete batch and requires byte-identical JSON;
9. validates a strict Draft 2020-12 schema and every nested semantic hash;
10. independently recomputes paired outcomes, aggregates and resource balances;
11. rejects leakage, missing rows, hidden regressions, unbalanced resources,
    unknown fields and publication overclaims.

GitHub Actions invokes this checkout-local task; the scientific contract is not
implemented in workflow YAML.

## Frozen result

Every campaign retains all twelve paired tasks and the same measured frontier:

| Outcome | Per campaign | Four campaigns |
|---|---:|---:|
| `IMPROVED` | 2 | 8 |
| `REACHABILITY_GAIN` | 0 | 0 |
| `NO_IMPROVEMENT` | 6 | 24 |
| `NO_RESULT` | 4 | 16 |
| `CORRECTNESS_REGRESSION` | 0 | 0 |
| `CANDIDATE_NOT_FORMED` | 0 | 0 |

The improvements are the two held-out binomial-expansion tasks. The TRAIN-derived
macro

```text
(A + B)^2 -> A^2 + 2*A*B + B^2
```

reaches each target in one macro step with lower paired search cost. Subtraction
is matched as addition of a negated operand, so the same macro applies to
`(a-4)^2` without seeing its held-out target during formation.

Normalization, distributive collection and equality-substitution tasks are
reached by both paired searches without additional measured macro benefit.
Cross-family rational cancellation and guarded partial-fraction tasks remain
explicit `NO_RESULT` rows. They are not removed from the aggregate.

## Resources

The preregistered per-campaign limits remain:

- 3,000 explored states;
- 600 candidate evaluations;
- 100 proof attempts.

The report distinguishes:

- TRAIN replay/formation work;
- baseline and macro-enabled paired-search work;
- configured, executed and remaining campaign resources.

Formal proof is outside this slice. All configured proof attempts therefore
remain explicitly unexecuted.

## Retained artifacts

```text
build/reports/candidate-independent-reusable-macro-batch/
  first/run.json
  second/run.json
  verification/
    verification.json
    first-run.json
    second-run.json
```

Each task row retains frozen case identity, split, structural cluster, source,
target, assumptions, task budget, both complete production-search results,
recomputed utility outcome and correctness-regression status.

## Claim boundary

A green run establishes reproducible target-free macro formation and paired
held-out search utility on this frozen challenge. It does not establish:

- universal macro usefulness;
- formal theorem proof;
- external mathematical novelty;
- expert-rated importance;
- superiority over information-equivalent discovery baselines;
- publication authorization by itself.
