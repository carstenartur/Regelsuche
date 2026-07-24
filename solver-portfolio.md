# Capability-aware solver portfolio

Issue #234 adds an execution layer above the solver-neutral IR from #233. It does not introduce another mathematical problem format. Every backend attempt remains a normal `solver-obligation/v1` → `solver-translation/v1` → `solver-result/v1` → `solver-execution/v1` chain.

## Separation of concerns

The portfolio report is explicitly marked `EXECUTION_TELEMETRY_NOT_MATHEMATICAL_EVIDENCE`. It records why a backend was selected, filtered, skipped, served from cache, timed out or cancelled. Mathematical evidence remains in the concrete backend executions and their certificates or countermodels.

A backend declares a `BackendCapabilityProfile` with:

- supported IR schemas, theories, goal and assumption relations;
- sorts, operators and call support;
- supported evidence strengths;
- semantic roles (`SEARCH_GUIDANCE`, `ORACLE_VALIDATION`, `COUNTEREXAMPLE`, `SYMBOLIC_CONFIRMATION`, `FORMAL_PROOF`);
- deterministic/reproducible flags;
- expected cost and semantic configuration hash;
- runtime availability.

Runtime availability is deliberately excluded from the semantic profile hash. A temporarily missing executable therefore cannot change the identity or meaning of a result already cached for an exact backend revision and configuration.

Planning never selects a backend by a hard-coded backend name. The initial profile factory merely declares the capabilities of the existing search engine, exact polynomial normal form and external Z3 adapter.

## Objectives and policies

`PortfolioRequest` carries an explicit backend-independent objective. This allows callers such as #225 to request validation or proof without knowing implementation classes. It also lets an existing proof consumer keep its canonical obligation unchanged while requiring the portfolio to continue to `FORMAL_PROOF`.

`PortfolioSolverBackend` supports both modes:

- derive the objective from the obligation's `requestedEvidence`;
- enforce an explicit objective override, for example formal confirmation of an existing symbolic proof obligation.

Four deterministic policies are implemented:

- `CAPABILITY_FIRST` — prefer a backend that can satisfy the requested objective;
- `COUNTEREXAMPLE_FIRST` — execute falsification and validation stages before expensive confirmation;
- `CHEAPEST_CONFIRMATION_FIRST` — prefer the lowest declared cost;
- `INDEPENDENT_CONFIRMATION` — require two distinct confirming backend IDs and continue far enough to expose disagreements.

Under `INDEPENDENT_CONFIRMATION`, an early refutation does not stop the remaining independent attempt. This makes conflict detection independent of cost and backend ordering.

`UNKNOWN`, a completed search without a target, or a backend that only supplies `SEARCH_GUIDANCE` never satisfies symbolic or formal proof. Search confirmation can satisfy only the explicit `SEARCH_GUIDANCE` objective and is excluded from mathematical proof conflicts.

## Execution safeguards

`SolverPortfolioExecutor` enforces:

- total cost and invocation budgets;
- per-backend cost and timeout limits;
- cooperative cancellation before and during execution;
- exact cache keys over obligation hash, backend semantic profile and attempt configuration;
- cache lookup before runtime availability;
- retention of filtered, skipped, cached and executed attempts;
- explicit `CONFLICT` whenever lossless mathematical `CONFIRMED` and `REFUTED` executions coexist.

Every `REFUTED` result and every `CONFLICT` block automatic promotion, regardless of whether the caller requested validation, counterexample search, symbolic confirmation or formal proof. The portfolio-compatible `SolverBackend` facade returns the exact selected formal execution to existing proof consumers. When no backend reaches the requested evidence level it returns a synthetic non-confirming execution and preserves the complete report separately through `lastRun()`.

`PortfolioRun.write(...)` uses one authoritative evidence layout for an exact request:

```text
<run>/
  obligation.json
  request.json
  report.json
  executions/
    001-backend-id/
      translation.json
      result.json
      execution.json
    002-other-backend/
      translation.json
      result.json
      execution.json
```

Every attempt with an `executionHash` must have exactly one retained directory containing the hash-linked translation, result and execution contracts. Filtered, unavailable, cancelled-before-invocation and budget-skipped attempts remain in `report.json` and do not receive fabricated backend artifacts. Rewriting a run replaces the complete run directory, so stale requests, reports or backend results cannot survive.

## Z3 backend

`Z3SmtSolverBackend` translates the bounded real-arithmetic IR to SMT-LIB 2. It asserts the negation of the goal under all structured assumptions.

- `sat` produces `REFUTED` and retains a model when available;
- `unknown` remains `UNKNOWN`;
- timeout remains `TIMEOUT`;
- unsupported calls, sorts or exponent forms are rejected before process invocation;
- division is accepted as lossless only when every nonliteral denominator is covered by a structured strict-sign or `NOT_EQUALS 0` assumption;
- process errors and `(error ...)` output can never become a certificate;
- `unsat` becomes `CONFIRMED` only after a second successful invocation retrieves a non-empty Z3 proof object, whose canonical payload is hashed as the certificate.

## Reproduction

Install Z3 and run the complete repository-owned verification command:

```bash
bash scripts/run-solver-portfolio-verification.sh
```

The runner:

- executes the normal solver-portfolio JUnit task, including the real Z3 case;
- writes the canonical formal and guidance traces;
- executes the proof-consumer integration test;
- creates a build-local Python environment with `jsonschema==4.25.1` when necessary;
- validates obligation, report, translation, result and execution schemas;
- independently checks all retained hash links and selected executions;
- confirms the exact polynomial-to-Z3 formal path and search-guidance path;
- checks negative schema mutations for invalid refutation and conflict states.

To select only the real external-prover integration through JUnit tags:

```bash
./gradlew :regelsuche-solver-portfolio:test \
  -PincludeTestTags=external-prover
```

The example is written below `regelsuche-solver-portfolio/build/reports/solver-portfolio` with separate `formal/` and `guidance/` runs and a shared `profiles/` directory. GitHub Actions only installs Z3 and calls the same runner; it contains no portfolio assertions or expected outcomes.
