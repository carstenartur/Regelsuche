# Capability-aware solver portfolio

Issue #234 adds an execution layer above the solver-neutral IR from #233. It does not introduce another mathematical problem format. Every backend attempt remains a normal `solver-obligation/v1` → `solver-translation/v1` → `solver-result/v1` → `solver-execution/v1` chain.

## Separation of concerns

The portfolio report is explicitly marked `EXECUTION_TELEMETRY_NOT_MATHEMATICAL_EVIDENCE`. It records why a backend was selected, filtered, skipped, served from cache, timed out or cancelled. Mathematical evidence remains in the selected backend execution and its certificate or countermodel.

A backend declares a `BackendCapabilityProfile` with:

- supported IR schemas, theories, goal and assumption relations;
- sorts, operators and call support;
- supported evidence strengths;
- semantic roles (`SEARCH_GUIDANCE`, `ORACLE_VALIDATION`, `COUNTEREXAMPLE`, `SYMBOLIC_CONFIRMATION`, `FORMAL_PROOF`);
- deterministic/reproducible flags;
- expected cost and configuration hash;
- runtime availability.

Planning never selects a backend by a hard-coded backend name. The initial profile factory merely declares the capabilities of the existing search engine, exact polynomial normal form and external Z3 adapter.

## Policies

Four deterministic policies are implemented:

- `CAPABILITY_FIRST` — prefer a backend that can satisfy the requested objective;
- `COUNTEREXAMPLE_FIRST` — execute falsification and validation stages before expensive confirmation;
- `CHEAPEST_CONFIRMATION_FIRST` — prefer the lowest declared cost;
- `INDEPENDENT_CONFIRMATION` — require two distinct confirming backend IDs and continue far enough to expose disagreements.

`UNKNOWN`, a completed search without a target, or a backend that only supplies `SEARCH_GUIDANCE` never satisfies symbolic or formal proof.

## Execution safeguards

`SolverPortfolioExecutor` enforces:

- total cost and invocation budgets;
- per-backend cost and timeout limits;
- cooperative cancellation before and during execution;
- exact cache keys over obligation hash, backend semantic profile and attempt configuration;
- cache lookup before runtime availability, so later unavailability does not change the meaning of an already cached result;
- retention of filtered, skipped, cached and executed attempts;
- explicit `CONFLICT` whenever lossless `CONFIRMED` and `REFUTED` executions coexist.

Conflict blocks promotion automatically. The portfolio-compatible `SolverBackend` facade returns the exact selected formal execution to existing proof consumers. When no backend reaches the requested evidence level it returns a synthetic non-confirming execution and preserves the complete report separately through `lastRun()`.

## Z3 backend

`Z3SmtSolverBackend` translates the bounded real-arithmetic IR to SMT-LIB 2. It asserts the negation of the goal under all structured assumptions.

- `sat` produces `REFUTED` and retains a model when available;
- `unknown` remains `UNKNOWN`;
- timeout remains `TIMEOUT`;
- unsupported calls, sorts or exponent forms are rejected before process invocation;
- `unsat` becomes `CONFIRMED` only after a second invocation retrieves a non-empty Z3 proof object, whose canonical payload is hashed as the certificate.

## Reproduction

Install Z3, then run:

```bash
./gradlew \
  :regelsuche-solver-portfolio:test \
  :regelsuche-solver-portfolio:z3IntegrationTest \
  :regelsuche-solver-portfolio:writeSolverPortfolioExample
```

The example output is written below `regelsuche-solver-portfolio/build/reports/solver-portfolio`. The `Solver Portfolio` workflow validates the report against `docs/schemas/regelsuche-solver-portfolio-report-v1.schema.json` and checks the real multi-stage path from exact polynomial validation to a Z3 proof object.
