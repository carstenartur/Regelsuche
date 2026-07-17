# Regelsuche solver portfolio

This Java 21 module implements issue #234 above the canonical contracts in `:regelsuche-solver-ir`.

It provides machine-readable capability profiles, deterministic portfolio policies, budgets, timeouts, cancellation, exact caching, complete attempt traces, explicit conflict aggregation and a real proof-producing Z3 adapter.

The module never treats planner telemetry as mathematical evidence. Existing proof consumers can use `PortfolioSolverBackend`; successful calls return the exact selected backend execution, while conflicts and insufficient evidence remain non-confirming.

See [`docs/solver-portfolio.md`](../docs/solver-portfolio.md) for architecture, safeguards and reproduction commands.
