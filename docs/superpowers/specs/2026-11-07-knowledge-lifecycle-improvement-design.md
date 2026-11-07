# Prepared knowledge and paid self-improvement

Carsten authorized the proposed improvement of Regelsuche's existing learner:
make knowledge cheaper to prepare/reuse, select it by actual total cost, retain
successful compositions and reject unprofitable improvement attempts. The goal
is measured saved work on recurring families, with the existing search and proof
kernel. This is an implementation request; routine design choices below are the
implementer's, not a request for another approval round.

## Starting point and approaches

Main is c3644ec29a0ae061c3f7980638f0680b21799c8d. The polynomial profile is merged;
the independent historical experiment remains in PR1150 and will not be changed
or included as a dependency in this PR. Existing knowledge is immutable, loading
reproves schemata, matching is already compiled per generation, and learnBatch
already composes previous knowledge and validates a bounded source-only router.
It currently selects by declared logical units. Its prepared task path does not
use the existing indexed matcher option.

Only increasing training/rule counts risks paying more matching/formation work.
Only increasing benchmark lengths would demonstrate amortization without improving
the implementation. The chosen approach is an explicit prepared session plus a
measured adoption controller around the same CumulativeTaskKnowledge APIs. Default
and historical APIs remain controls; there is no second search/learning kernel.

## Prepared execution

Add opt-in indexed preparation to CumulativeTaskKnowledge and route it through
CheckedSchemaMatcherPlan.prepareIndexed. Preserve the mathematical inventory,
exact checking, search frontier, primitive fallback and all budgets. Carry this
runtime preparation choice through later expression/recurrence generations.
The existing canonical snapshot format and default behavior stay compatible.
Index preparation work is actually counted in setup/acquisition receipts.

TaskKnowledgeSession is caller-owned, bounded by maximum retained generations
and canonical JSON characters. It loads through the existing strict snapshot
reader and the indexed preparation path. A hit reuses only an already verified
immutable generation bound to the same inventory and identical complete canonical
bytes. Hash equality alone never authorizes knowledge. Every request still reads
and checks the snapshot; changed or malformed content is independently loaded or
rejected. LRU eviction, over-capacity uncached loads and close release retained
references. No process-global cache or query-answer memoization is introduced.
Receipts distinguish I/O/lookup, actual restore/preparation work and cache reuse.

## Paid improvement cycle

TaskKnowledgeSelfImprover uses the real existing learnBatch for each bounded
lesson/validation batch. It then measures previous and proposed generations on
a separate caller-supplied promotion task set, with alternating execution order.
Promotion tasks must not be polynomial alpha-renamings of lessons or validation;
this is development validation, not a claim of a sealed independent-family test.

Use monotonic elapsed time and whole-JVM process CPU where available. The adoption
gate accepts only complete measurements, no per-task solution/quality regression,
and projected savings over a caller-declared future-use horizon exceeding ALL
actually paid formation/evaluation overhead in both CPU and wall time. This
projection is an explicit prediction, never a measured end-to-end speedup claim.
Missing CPU or an exhausted work/time budget prevents cost-based adoption. Pure
gate arithmetic is exact under large counts. Failed/rejected attempts retain paid
receipts and leave the previously active immutable generation unchanged.
The controller can run several supplied batches: later accepted lessons use the
last accepted generation, exactly as the existing cumulative learner does.

The controller does not invent mathematical procedures outside that learner's
domain. It supplies a reusable, measured improvement loop and adoption decision.
Existing source-derived generalization/composition remains the rule-learning
mechanism. Future equation/proof capabilities (including Euclid/Ferrari) are not
silently counted as completed by this cost-improvement work.

## Qualification and evidence

Tests first for cache correctness/eviction/corruption/inventory binding, indexed
result equivalence and paid setup, net-benefit arithmetic, individual regressions,
missing resource measurements, budget exhaustion and failed/no-op learning.
Run the complete affected Java learning reactor and existing Python comparison
suite, and the unchanged semantic coverage/complexity gates.

Freeze a small before/after lifecycle experiment before execution. Compare direct
strict repeated loads with caller-owned prepared-session loads, both including
initial acquisition, first independent restore, all later reads/lookups, queries,
rejected improvement attempts and shutdown. Include one-load controls and actual
repeated-load streams. Retain actual CPU/RSS, outputs, source/runtime identities
and independent exact polynomial audits; no timing assertion belongs in unit
tests. Reuse primary comparison procedures if SymPy is measured; preserve old
experiments. Report negative results and application-dependent amortization.

Publish one focused PR against current main and a reproducible report. A fresh
whole-change review checks mathematical authority, costs and failure paths.
