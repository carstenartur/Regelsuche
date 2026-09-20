# Registered and staged incremental providers (P02)

`MoveSearch.Scheduling.STAGED_INCREMENTAL` is an explicit experimental v2 contract
inside the existing `IncrementalMovePicker` and `MoveSearch.SearchRun` frontier.
It does not change `STAGED`, `EAGER_CONTROL` or `INCREMENTAL_NATIVE_ORDER`.
The native-only mode still refuses registered providers and non-inventory policies.
The four accepted-predecessor native v1 JSON hashes and the earlier eager/staged
hashes are frozen in `IncrementalNativeMoveReplayTest`.

## Admission and transport

The sealed `IncrementalMoveProvider` admits the existing native implementation
and `RegisteredIncrementalMoveProvider`. Its general `contractDefinition()` and
`openSession()` methods expose the v2 contract; the native v1 methods remain
native-only. A registered provider requires an exact match in an immutable
`IncrementalProviderContract.Registry`: provider ID, contract revision, kind,
model revision, semantics revision, transport and mathematical-work kind.
An unsupported revision, absent registration or changed binding is rejected.
Native definitions alone contain real native rule inventories. Registered schema
metadata never invents native rules, primitive steps or mathematical authority.
All emitted moves still pass the existing independent `MoveVerifier`.

`PARSER_TEXT` and `TYPED_AST_JSON` are explicit, different transports.
`TypedMoveSearch` admits registered providers only with the latter declaration;
native parser-text providers are never silently marked typed. Existing explicit
`TypedProvider` primitive batches are also supported by staged v2. Their complete
batch is generated only when that lane is first pulled. The batch's full work and
all generated candidates remain counted even if only one candidate is consumed.
Opaque batches and non-primitive typed batches need an explicit registered
contract. P02 supplies this boundary; it does not implement learned schema
execution, AST transport replacement, learning or new proof authority.

## Pull, scheduling and continuation

A session starts OPEN without executing a factory. `next(0)` opens no provider
and emits nothing. Positive pulls pay admission, opening and pull mechanics;
factories receive the cumulative meter before they execute, including before an
open can fail. Each pull emits at most one candidate. Progress stays in the same
source object. Matching/loading/delegated mechanics and actual `ExecutionWork`
are charged by that source; atomic operations may overshoot their allowance.
The actual work is retained and the search cannot report budget success after
an overrun. The meter rejects negative units and checks declared mathematical
kind and that emitted mathematics is covered by cumulative paid mathematics.

Providers are stably ordered by policy stage, descending provider score, then
inventory order. The picker pays one provider-order unit per lane plus
`policy.contextWork`. Candidates retain their source order; candidate scores are
not evaluated or sorted. After two learned emissions an available primitive lane
gets the next opportunity. This is emission fairness, not a guarantee about
atomic-call latency or arbitrary non-emitting delegates. The parent continuation
uses the next selected stage in the same frontier and remains suspendable.
A promising child can finish before any later lane is opened.

## States and work receipts

| State | Meaning |
| --- | --- |
| OPEN | Handle exists; provider factory has not run. |
| READY | Same source can produce another candidate. |
| LIMIT | Pull allowance depleted; a positive pull resumes this same source. |
| EXHAUSTED | Source declares its admitted relation complete. |
| INCONCLUSIVE | Terminal partial relation, including native matcher/candidate limits. |
| FAILED | Delegated exception/contract violation; known work survives, accounting is incomplete. |
| CLOSED | An unfinished session was abandoned. No drain or restart occurs. |

Close is idempotent, paid once by the wrapper and retains any additionally
reported delegated cleanup work. Exhausted/inconclusive/failed terminal reasons
survive close, with a separate closed flag. Exceptions add an abort operation and
mark accounting incomplete; unknown delegated remainder is never guessed as zero.
The mathematical validity of previously verified evidence remains distinct from
whether a total-work or economic claim is justified.

Native v1 `WORK_EXHAUSTED` remains terminal. Only the explicit
`PreparedAstRewriteTransformationEngine.openResumableCursor` entry point resumes
that status on a positive pull, using the same traversal and revision
`regelsuche.resumable-native-transformation-cursor-work/v2`.

General receipts use `regelsuche.incremental-provider/v2`; staged execution uses
`regelsuche.staged-incremental-move-search-work/v2` and
`regelsuche.stage-provider-score-native-order-two-learned-burst/v2`.
`StagedIncrementalMoveExecution` records every definition, source expansion,
stage, original provider index and final cursor receipt. Unopened lanes have null
cursors. `LearnedSchedulingArtifacts` emits the new field only for the new mode;
frozen native v1 records and JSON shape are unchanged.

Mechanical work enters the existing delegated-mechanics channel. Primitive and
exact mathematics enter `candidateWork` exactly once through `Ledger.collect`;
exact theory keeps its real step count and work units with zero invented
primitive rewrites. Verification stays independently charged. These declared
logical units make no runtime or universal speedup claim.

Incomplete accounting invalidates target/quality budget success, including
source-only `withinBudget` and the P01 lifecycle journal. The improved expression,
valid proof and known query/replay/output work may still be retained for diagnosis.
A lifecycle stream reports an incomplete-account row and preserves later queries
as NOT_RUN instead of allocating them from an unknown remainder.
