# Review invariants for projected polynomial work

The projected native polynomial work boundary is reviewable through five
independent invariants:

1. The raw stage ledger is retained without truncation or replacement.
2. Runtime charging and final evidence validation use one stable projection
   identity and the same stage quanta.
3. The default identity projection preserves the behaviour of every caller
   that does not opt into projected accounting.
4. Nested native stages remain subject to the owning non-resettable authority;
   a projection does not create additional work.
5. A validated multi-occurrence result requires every evaluated occurrence to
   be transformed. Partial success cannot hide a budget-inconclusive,
   unsupported or technical sibling outcome.

The visible development characterization uses its original frozen full
checkpoint. Qualification inputs and candidate-freeze artifacts are not read or
created by this change. An insufficient projected authority must remain
`BUDGET_INCONCLUSIVE`; validators are not weakened to obtain a positive result.
