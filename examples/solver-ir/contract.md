# Atomic solver execution contract

For every backend attempt, Regelsuche retains exactly four semantic layers:

```text
SolverIr.Obligation
→ SolverTranslation
→ SolverIr.SolverResult
→ SolverExecution
```

`SolverTranslation` is the only source of truth for translation status, translation issues and source-to-backend term mapping. `SolverResult` contains only the backend outcome. `SolverExecution` proves that both artifacts belong to the same obligation and backend revision.

A lossless translation must map at least `goal.left` and `goal.right`. Rejected translations must expose issues and produce an `UNSUPPORTED` result without backend execution. A proof report may authorize `SYMBOLICALLY_VERIFIED` only for a `LOSSLESS` translation and a `CONFIRMED` result linked by the same execution hash.
