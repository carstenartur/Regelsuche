# Native Expr qualification boundary

Native execution is additive and experimental. The historical `MoveSearch` and
`TypedMoveSearch` interfaces retain their existing contracts. Native `Problem`
continues to reject `PRODUCTION` contexts. Representation registration and an
accepted-looking receipt never establish mathematical authority: default native
verification uses supported registered verifiers and fresh checking; an explicitly
supplied trusted verifier has the same responsibility as the old verifier hook.
Exact schema capabilities remain private and bind the checked model/application.

The current search revision is
`regelsuche.native-expr-move-search/v7-partial-atomic-ownership`; output uses
`regelsuche.native-legacy-export/v3-partial-atomic-ownership`. Both have the fixed
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

P04 completion requires a bound inventory of the supported execution paths and
the complete native public P03 differential, fresh module suites, independent
full review and exact-head CI. Focused corrections of the atomic ownership
boundaries below do not, by themselves, authorize a total-accounting claim.
No performance, learning or P05 proof-reuse claim is made by this foundation.

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

Each index operation keeps its actual scratch ownership append-only, with a
full observation after at most 4096 scratch-growth events and at completion.
At each growth event, a constant-time check rejects scratch reference
slots or encoding bytes exceeding the store's finite limits; this lower-bound
guard also runs without an enclosing observer. Exceptional exits observe still
live scratch before releasing it, preserving the original failure. These local
checks do not replace the enclosing observer's full ownership/scan accounting.
Exact-integer encodings and their hashes are reused by scalar object identity
within that operation, avoiding repeated buffers for shared rational values.
All frames, comparison pairs, memo entries, guards, observations and release
remain paid. No full search-graph scan is required at every child edge; these
logical limits still make no fixed JVM heap-size guarantee.

V5 is deliberately a new measurement revision: extra paid work and scratch
observations can exhaust a previously sufficient diagnostic budget. It does
not retroactively qualify V4, change old receipts, or claim an economic gain.
Frontier/goal/replay equality outside this store and the other atomic regions
listed above remain outside this slice. The public completion flags remain
false. Deep-store tests are not a claim that every downstream AST consumer is
stack-safe.

## V6: retained direct AST validation

Direct expression/history validation keeps its existing occurrence, depth, text,
Unicode and canonical-byte limits without serializing AST JSON. Iterative preorder
visits replace the recursive counter. The inspection owns its input, current visit,
append-only visit arena and current completed numeric/symbol rendering through the
existing retention scope. Arena insertion, removal/lookup, bounds and release are
paid execution work; completed rendering characters are charged separately from
the subsequent text scan. Node/text validation units retain their previous definition.

Each state's arena contribution is limited to 10,000 visits, including queued
occurrences. This prevents nested wide functions from multiplying the queue before
the traversal reaches its node limit. Histories still contain at most nine states.
Arena growth is observed within 256 insertions and before cleanup; completed scalar
renderings are observed before replacement, including after a failed validation
debit, then released before visiting another node. An already observed terminal
graph with no subsequent ownership growth is not scanned twice. Cleanup
releases scratch, remains paid even after failed scope acquisition and preserves the original throwable
when the observer repeats it. Input observation failures enter the existing kernel's
resource-result path so no paid attempt is lost before frontier initialization.

This is additional diagnostic accounting, not a speedup claim. Keeping visits to
the end of an inspection has a measured logical retention cost. Internal temporary
allocations inside JDK scalar formatting, other validators and the atomic regions
above still prevent total qualification. All public completion flags remain false;
V6 cannot reinterpret V5 or establish a learning/economic advantage.

## V7: explicit atomic ownership boundaries (qualification pending)

V7 gives the integrated atomic-ownership changes a new measurement identity.
The search and output revisions are separate; historical V6/V2 reports and
numeric budgets retain their original meaning. Public completion and budget
flags remain false until the full supported path is qualified.

The supported paths reuse the existing iterative expression identity operation
for observed frontier, goal and replay comparisons. Local rewrite ancestors,
monomial inference, exact-scalar intermediates, assumption normalization,
formatter/JSON buffers and application-proof wrappers now expose their own
live objects to the existing observation scope. An allocation is handed to
that scope before a later debit can abort. Unchanged siblings, checked schema
objects and original scalar values remain shared. Mathematical application and
delegated work receipts retain their distinct ownership; observation does not
create another mathematical checker or search algorithm.

Finite result construction after a recorded limit can finish its already
determined metadata graph while still paying for observations and copies.
This does not resume providers, mathematical checks or search after exhaustion,
and cannot restore a successful budget flag. Cleanup retains the original
failure even when the observer throws that same object again.

These are declared logical work and retained-graph units. Private implementation
details of JDK arithmetic/collections, JVM headers, stacks, allocation rates,
garbage collection and resident memory are not physical measurements supplied
by this contract. The complete module/corpus/review/CI evidence is a separate
release requirement; the new revision alone establishes no speedup or economic
advantage.

## 2026-10-09 reviewed checkpoint

The supported atomic ownership inventory now has a complete local module run,
the public native differential and independent review, retained in
[the source-bound checkpoint](evidence/work-replacement/p04-native-ownership-20261009/README.md).
This closes the concrete implementation findings in that inventory without
promoting V7/V3's public PARTIAL contract.

A remaining release boundary is explicit admission of known execution paths.
Public native problems can accept arbitrary provider, rule, ranking, scoring,
objective and verifier callbacks. Describing their retained graph or registering
their mathematical checker does not qualify the work performed by their code.
A future complete logical-accounting revision must bind every used callback
and delegation to a reviewed implementation; unknown extensions remain partial.
Recorded observation/cursor failures, independent final replay, work and retention
limits, and the original kernel completeness relation still apply independently.
