# regelsuche-solver-ir

This module owns the single current execution contract between Regelsuche discovery/search code and mathematical backends.

It provides four hash-linked artifacts:

1. `SolverIr.Obligation` — typed declarations, structured assumptions, goal, theory, requested evidence and source revision;
2. `SolverTranslation` — explicit source-to-backend term mapping plus lossless, approximated or rejected translation status;
3. `SolverIr.SolverResult` — backend outcome, backend revision, invocation identity, capabilities, model/counterexample and certificate references;
4. `SolverExecution` — the atomic binding of one obligation, one translation and one result.

The module deliberately does not read or emit the removed internal proof-obligation formats. Unsupported assumptions, theories, evidence strengths or expression fragments are rejected before backend execution.

Current backend implementations are:

- `RegelsucheSearchBackend` for bounded production rewrite search;
- `PolynomialNormalFormSolverBackend` for exact rational polynomial proof/refutation within its declared fragment.

Portfolio selection and Z3/cvc5/theorem-prover orchestration belong to issue #234 and must consume these contracts rather than introducing another problem format.

See [`docs/solver-neutral-ir.md`](../docs/solver-neutral-ir.md) for schemas, examples and reproduction commands.
