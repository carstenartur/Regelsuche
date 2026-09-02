# Review invariants for polynomial utility work

The native on-demand adapter is reviewed against the following independent
invariants:

1. The complete raw stage ledger is retained without truncation, replacement or
   post-hoc removal of unsuccessful work.
2. Every occurrence receives disjoint primitive, mechanical and factorization
   authorities before execution. The native engine receives a non-resettable
   backend cap no larger than that occurrence's frozen factorization authority.
3. After execution, the complete raw ledger is partitioned exactly once into the
   frozen study dimensions and validated by
   `PolynomialTheoryUtilityCanonicalWorkProjection/v2`. Unknown native stages
   remain visible and are charged one-for-one as factorization work.
4. The final canonical projection is an evidence-validation boundary, not a
   second source of runtime authority. Unused work is not reallocated and no
   nested stage receives a reset budget. The current native runtime cap remains
   raw and is therefore conservative wherever the final projection removes an
   explicitly frozen implementation multiplier.
5. A validated multi-occurrence result requires every planned occurrence to be
   transformed. Partial success cannot hide a budget-inconclusive, unsupported
   or technical sibling outcome.

This slice deliberately does **not** claim identity between runtime charging and
the final canonical projection. Such a change would have to propagate one
versioned projection through every independently constructed polynomial work
budget, including square-free decomposition, finite-field factorization,
Hensel lifting and recombination, and would require its own pre-execution review.
It is not inferred from successful formation cases.

The visible development characterization keeps the original frozen checkpoints.
Qualification inputs and Candidate-Freeze artifacts are neither read nor
created. Insufficient authority remains `BUDGET_INCONCLUSIVE`; validators and
budgets are not weakened to obtain a positive result.
