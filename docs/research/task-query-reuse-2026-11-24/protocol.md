# Repeated query path reuse: public engineering comparison

Revision: `regelsuche.task-query-reuse/v1`. This protocol is written before the
new comparison's first execution. It evaluates explicit repeated-query reuse,
not mathematical transfer learning or a claim against SymPy. The historical
knowledge-lifecycle and polynomial-parity protocols remain unchanged.

## Fixed inputs and procedures

Use the existing `MixedTaskKnowledgeWorkload` seed batches and eight evaluation
roles, with their existing source-only node goals and full query budgets.
Each arm independently acquires both seeds, saves the resulting generation,
strictly restores and prepares it once, and retains that same immutable object.
No further learning or measured adoption takes place in either arm.

Compare the persistent prepared generation with a separate
`TaskKnowledgeQuerySession` holding at most 32 successful nonempty paths and
4,000,000 retained logical characters. Every hit passes its proposals through
the existing candidate verifier and the complete selected-path replay. Retention
never supplies a verified answer. Negative, interrupted and out-of-budget
results cannot enter the retained set. The persistent control uses zero query
capacity and performs the existing fresh solve without cache bookkeeping.

Three scenarios are fixed:

| Scenario | Queries | Purpose |
| --- | ---: | --- |
| COLD_ONE | One block, eight queries | Expose initial lookup, footprint and admission overhead |
| REPEATED_SIXTEEN | Sixteen identical blocks, 128 queries | Measure exact repeated-query reuse |
| DISTINCT_SIXTEEN | Sixteen blocks, 128 queries | Replace every variable token by the same token with `_q<block>` appended; retain original constants and goals; test cache misses on distinct sources |

The already-small constant in the distinct scenario has no variables and repeats,
but its empty path is never retained. Renaming is only a controlled exact-cache
miss; it is not evidence of independent mathematical families. The persistent
and retained arms receive identical ordered requests in each scenario.

Run three repetitions. Within each scenario the two arms alternate order by
`(repetition + scenario.ordinal()) % 2`. There is no extra warmup. This shared-JVM
comparison exposes order/JIT effects; report every paired observation instead of
treating a favourable median as proof of a universal speedup.

## Complete costs and acceptance

Report seed acquisition, strict restoration and preparation, query lookup,
retention footprint traversal, admission and eviction, fresh search/verification,
final replay and an additional exact polynomial/occurrence-node audit. Check
every witness edge, source/endpoint continuity, final equivalence and node score.
Keep all negative outcomes and failures. Unknown work makes accounting incomplete.
Logical work is not bit complexity or a substitute for measured CPU and elapsed
time. Retained-character bounds are not JVM heap limits.

Each arm's wall/process-CPU measurement encloses acquisition through session
shutdown and auditing. An outer owned-process receipt additionally encloses JVM
startup, shutdown and final JSON serialization. These nested costs must not be
added. A quality comparison requires complete accounting, identical requests and
no solved-status or output-score regression per request.

Do not alter workloads, capacities, horizons, quality thresholds or repetitions
after seeing the observations. A benefit requires reported savings after paying
the complete arm, and its scope remains the declared repeated workload. Publish
the cold and distinct controls even if they regress. A result with no total-cost
payback is retained as such.
