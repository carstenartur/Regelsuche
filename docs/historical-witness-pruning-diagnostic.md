# Historical witness-prefix diagnostic

`witness-pruning-diagnostic.json` extends the frozen historical rediscovery atlas with one narrowly scoped question:

> When the target-aware production oracle has a witness but ordinary target-blind scalar search misses the relation, which first witness edge or state is lost, and why?

## Execution boundary

The existing atlas remains the authority for corpus identity, representation, equivalence, reachability and search outcomes. The diagnostic:

1. consumes that exact atlas and frozen corpus;
2. reruns only missed target-blind scalar cases with a passive `SearchObserver`;
3. requires states and complete work counters to equal the retained atlas run;
4. walks the already retained oracle witness until its first missing state;
5. records the matching telemetry event, frontier size, visited count, score and pruning reason.

The target, preferred representation and oracle path are never attached to the scalar `SearchProblem`. They are used only after the ordinary run to classify retained telemetry.

## First-loss taxonomy

The v1 artifact distinguishes at least:

- a transformation rejected by an ordinary safety rule;
- duplicate-state or transposition pruning;
- a generated state left in the frontier when the state budget ended;
- a per-state candidate ceiling reached before the witness edge;
- a depth ceiling at the witness parent;
- an engine that did not generate the oracle edge;
- a witness parent that was queued but not explored or never reached.

Every frozen corpus case is retained as diagnosed, already found, complete-closure miss, budget-inconclusive, not evaluated or correctness-regression evidence. The report binds the exact atlas bytes by SHA-256 and carries its own canonical content hash.

## Claim boundary

This is target-aware diagnostic evidence. It does not establish autonomous rediscovery, proof, global unreachability, mathematical novelty or general search superiority. It is intended to decide whether later work should change inventory, directionality, matcher semantics, search policy or budgets before larger architecture changes are attempted.

## Reproduction

The existing checkout-owned historical rediscovery task now writes both:

- `build/reports/historical-rediscovery/`;
- `build/reports/historical-rediscovery-witness-pruning/witness-pruning-diagnostic.json`.

The schema is `docs/schemas/regelsuche-witness-pruning-diagnostic-v1.schema.json`.
