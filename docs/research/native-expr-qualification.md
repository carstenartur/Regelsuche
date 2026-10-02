# Native Expr qualification boundary

Native execution is additive and experimental. The historical `MoveSearch` and
`TypedMoveSearch` interfaces retain their existing contracts. Native `Problem`
continues to reject `PRODUCTION` contexts. Representation registration and an
accepted-looking receipt never establish mathematical authority: default native
verification uses supported registered verifiers and fresh checking; an explicitly
supplied trusted verifier has the same responsibility as the old verifier hook.
Exact schema capabilities remain private and bind the checked model/application.

The current search revision is
`regelsuche.native-expr-move-search/v5-partial-structural-index`; output uses
`regelsuche.native-legacy-export/v2-partial-atomic-inventory`. Both have the fixed
coverage `PARTIAL_ATOMIC_INVENTORY`. There is no option or test switch that promotes
them to complete accounting.

| Observation | Public contract |
| --- | --- |
| Search `outcome()` | Always `INCONCLUSIVE` while coverage is partial |
| Search/Accounting completion and `withinBudget()` | Always false |
| `observedOutcome()` | Actual diagnostic execution/resource outcome; no total-budget qualification |
| `observationsComplete()` | Installed observers completed without their recorded failures; missing observers still exist |
| Witness, independent verification, quality incumbent | Actual mathematical execution remains available and independently checked |
| `totalWork()`, export work, peak | Paid observed units only; neither complete cost nor a complete heap/retention bound |
| Explicit export `artifactAvailable()` | Diagnostic projection was materialized, independently of accounting qualification |
| Export `complete()`/accounting completion | Always false; export cannot qualify the originating search |

Existing observers and finite limits still run, including atomic overruns,
suspended cursors, external captured ownership, result handoff, output buffers and
cleanup. Real unknown-type, work/retention exhaustion and rejected-final-replay
reasons remain intact. Observation incompleteness does not erase a paid attempt or
turn a rejected proof into a valid one. Logical AST-node, scalar-character and
reference units are not JVM byte measurements. Separate external ownership may
overlap result ownership and must not be added as a disjoint total.

The explicit `exportLegacy(workBudget, limits)` runs the existing serializers and
returns an immutable paid output receipt. A materialized projection is always
`INCONCLUSIVE` with `completeBoundedRelation=false`; its old cursor-specific
accounting flag is not a native total-accounting qualification. A real export
resource failure still yields no projection. The no-argument convenience export
uses finite defaults (10,000,000 work units and default retention limits) and
throws `ExportFailure`, carrying its attempted receipt and any diagnostic artefact.
Search costs remain unchanged by export. End-to-end observed accounting adds the
separate search and output phases exactly once and remains incomplete.

Default retention limits remain 1,000,000 unique AST nodes, 16,777,216 scalar/text
characters and 2,000,000 references/collection entries. A reported observed peak
below these limits cannot certify the missing atomic graph regions.

The follow-up P04 completion must cover recursive rewrite rebuilds and validation
queues; canonicalization/normalization maps and intermediate expressions;
matcher/backtracking and schema-domain/instantiation temporaries; formatter and
feature buffer capacity/growth; application-side export helpers; and remaining
result/receipt assembly overlaps. It must then pass the complete native public
P03 differential, fresh module suites, independent full review and exact-head CI
before a new revision may claim total accounting. No performance, learning or
P05 proof-reuse claim is made by this foundation.

## V5: paid structural store index

The optional `SearchExpressionStore` index now hashes and compares expressions
iteratively. Per-operation identity memoization visits shared DAG nodes/pairs
without expanding all occurrences; the scratch maps, frames and exact-scalar
byte encodings are exposed to the existing paid retention observer. Integer
hash buckets only select candidates: full structural comparison still decides
identity, including scoped symbols, grouping, ordered arguments and exact
rationals. AST equality and the historical entry points are unchanged.

Storage work includes visited labels, child/memo/queue operations, inspected
name characters, exact integer encoding bytes and byte comparisons, collision
probes, bucket shifts and scratch release. These are declared logical units,
not CPU instructions or JVM bytes. A hash is computed once per lookup and
saved on the resulting session reference for eviction; collection insertion
and eviction never recursively hash an expression. An index limit of zero
does no structural index work. Index references include bucket keys/values,
backing slots and members; eviction still leaves the owned roots alive.

Each index operation keeps its actual scratch ownership append-only until its
final observation, then releases it. The terminal scratch graph therefore
contains every earlier frame, comparison pair and encoding buffer: the final
peak observation does not require a full search-graph scan for every child
edge. This deliberately retains more local scratch during the atomic operation;
those retained objects, their peak and their eventual release remain paid.

V5 is deliberately a new measurement revision: extra paid work and scratch
observations can exhaust a previously sufficient diagnostic budget. It does
not retroactively qualify V4, change old receipts, or claim an economic gain.
Frontier/goal/replay equality outside this store and the other atomic regions
listed above remain outside this slice. The public completion flags remain
false. Deep-store tests are not a claim that every downstream AST consumer is
stack-safe.
