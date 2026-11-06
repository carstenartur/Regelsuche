# Checked alternatives in the existing task search

Continue the interrupted source-only search work authorized by Carsten's
“Mach weiter”. A caller may request a bounded collection of structurally
qualifying alternatives rather than ending the existing search at its first
hit. Historical answers and derivations remain unavailable to search.

Use the existing typed frontier, continuation contracts, source-domain checks,
objective accounting, replay verifier and immutable knowledge generation.
The old `solve`/`learnBatch` behavior and persisted knowledge formats remain
compatible. Exploration does not train the library or qualify a new schema.
Knowledge-first exploration can apply the existing checked learned rules.

The result binds the source task, generation and selected strategy, and exposes
distinct nontrivial candidate expressions with complete independently replayed
paths. Selection and every additional replay consume the same total budget.
The caller fixes a positive replay/selection reserve and result limit. Partial
collections explicitly report truncation; overruns or unknown accounting cannot
be reported as within budget. Failed independent checks cannot issue candidates.

Approach: extend the existing online quality search with opt-in continued
exploration, then collect admitted paths. This preserves one search kernel and
avoids searching separately for each target spelling. Selecting a historical
answer during search or supplying an ordered solution path violates the task.

Implementation choices: preserve admission order and structural AST identity;
deduplicate expressions, retain a deterministic result bound, and reuse the
existing automatic routing policy with its routing cost. The alternative
reserve is part of the original budget, not an additional allowance. No new
claim of complete enumeration, original atlas coverage, general strategy
invention, or lifecycle speedup follows from this API.
