# Finite search knowledge and deterministic control

Implement the outstanding #696 iterative-guidance, finite tablebase and deterministic portfolio contracts using the existing reciprocal four-rule factor/distribution fragment. This is an explicit finite-language feature, not a replacement for arbitrary search or a universal work metric.

1. Red tests cover exact context separation, independent primitive replay, tampered persisted tables, exhaustive reference agreement, short-work rejection and deterministic serial/parallel merge.
2. A bounded tablebase records source-defined finite closures and actual primitive identities. Reload reconstructs from its declared source plan, not trusted IDs or serialized success flags. Misses mean only absence from this table. Acquisition, reload, lookup and fresh verification costs remain separate.
3. A new iterative-guidance mode retains prior-pass ordering/transposition observations, never unsound prior-pass pruning. Repeated primitive calls and independent replay remain charged to the existing shared allowance. Expose its versioned pass receipts.
4. A portfolio freezes per-lane resource allocations and merges only after all declared lanes reach their terminal barrier. Serial and parallel modes preserve canonical lane order and selected mathematical witness. Every lane's work counts even if another succeeds first.
5. The local closure plan uses the existing typed RewriteProgram with an explicit primitive-only, finite nonexpanding inventory and repetition/candidate/work limits. Primitive expansion and assumptions remain visible.
6. Execute comparison against existing full bounded closures and retain wall time/allocation separately from primitive calls, search work and reachable results. Update the strategy index and add the actual controls to clean/pinned verification. Broader #750 held-out outcomes are not consulted during implementation.
