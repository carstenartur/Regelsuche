# Learned task completion

## Intent
Turn learned and independently proved schemata into a reusable task-solving path:
prepare once, try learned applications first, stop at the caller's declared quality
criterion, and leave primitive providers available for an unresolved remainder.
A successful learned application is not by itself a solved task.

## Decision
Use `TypedSourceOnlySearch.searchUntil` and the existing shared frontier, staged
incremental cursors, checked-schema model and final independent replay. Do not
introduce another search algorithm or learner. The optional shape index is not
required: its earlier small-model diagnostic was negative.

Alternatives considered: changing every exploration call to early stopping would
silently change existing experiment semantics; a proof-result cache would need a
separate authority/lifetime contract and is not part of this increment. Keep all
existing exploration APIs and frozen comparison workers unchanged.

## Contract
A typed task binds source, carried assumptions, a caller-supplied measured quality
objective and threshold, and one existing search budget before execution. Success
requires QUALITY_REACHED, the requested quality, and `withinBudget()` after final
replay. Every invocation starts with fresh cursors. Immutable model/preparation
may be shared, but no successful query or query-specific assumption is cached.
Primitive and learned comparisons use the same objective, frontier, node-count
valuation and final replay. The primitive control uses its cheaper batch scheduler;
the learned arm uses its lazy cursor so unused candidates are not materialized. No hidden target expression reaches search.

## Scope and limits
Expose a prepared solver through the existing `TypedLearnedMoveInventory` API.
Provide real learn/save/load tests, a multi-step lesson and unseen compositions,
including already-solved, missing-premise, unsupported and residual-work cases.
Separate training, model restoration and compilation from per-task receipts;
retain losing and failed tasks. The logical work contract remains the historical
typed one: not exact CPU or heap accounting, not complete native P04 qualification.
This is a development comparison, not a sealed cross-domain or SymPy superiority claim.
