# Candidate-independent autonomous-discovery benchmark

Issue #383 introduces a preregistered benchmark that is frozen before any evaluated campaign is run. Its purpose is to test whether Regelsuche repeatedly forms, validates and reuses executable rules across structurally different problem families without adapting the corpus after observing a retained candidate.

## Current phase

This pull request implements **phase 1: protocol and corpus freeze** only.

The authoritative source is:

```text
research/benchmarks/candidate-independent/benchmark-source.json
```

It binds the evaluator-backed challenge portfolio from #390 and freezes:

- three independent challenge classes;
- 18 cases with six cases per split;
- TRAIN-only candidate-formation visibility;
- validation and final TEST cases hidden from formation;
- fixed resource budgets and separate metrics;
- explicit accepted, rejected, disproved, no-result, timeout, unsupported and incomplete outcomes.

The preregistration deliberately remains:

```text
executionStatus: NOT_STARTED
publicationAuthorized: false
```

No benchmark success and no external mathematical novelty are claimed.

## Local verification

The complete verification entry point is repository-owned and works from a plain checkout:

```bash
./gradlew verifyCandidateIndependentBenchmark
```

The verifier rejects duplicate JSON keys, unknown top-level fields, portfolio substitution, split-count drift, TEST or expected-answer exposure, incomplete terminal-outcome accounting and structural-cluster coverage drift.

GitHub Actions calls this Gradle task, but it does not contain the benchmark rules or pass/fail logic. A later consolidation can attach the task to a repository-wide verification lifecycle once that lifecycle has one unambiguous root aggregate task.

## Follow-up phases

Later changes under #383 will add deterministic campaign execution, retained null and failure outcomes, independent held-out evaluation, aggregate reporting and pinned-container reproduction. Those phases must consume this frozen identity rather than rewrite it after observing TEST results.
