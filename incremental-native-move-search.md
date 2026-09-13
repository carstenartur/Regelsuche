# Incremental native move generation (#696)

This is an explicit experimental scheduling capability. It suspends a native
rewrite traversal before the next rule application or occurrence; it does not
wrap an already generated list. Existing `STAGED`, `EAGER_CONTROL`, measured
engine adapters, product defaults and historical work/receipt identities keep
their existing behavior.

## Callable boundary and order

`PreparedAstRewriteTransformationEngine.openCursor(source)` accepts inventories
whose runtime classes are exactly `PatternRewriteRule`. Subclasses and custom
rules are rejected during preflight, so overridden dispatch cannot silently be
bypassed. The engine's existing `transform(source)` method remains unchanged.

The cursor holds a DFS stack with a rule index and a child index per frame.
Occurrences are visited in preorder: root, binary left/right, function arguments
in their original order. Every rule at one occurrence is attempted in inventory
order. Replacement uses the shared iterative `TreePosition.replaceAt` authority.
The source AST stays fixed throughout a cursor: every candidate is one rewrite
of that source, as in the prepared batch engine.

`NativeIncrementalMoveProvider` adapts this capability to `MoveSearch` using
`Scheduling.INCREMENTAL_NATIVE_ORDER` and `MovePriorityPolicy.INVENTORY_ORDER`.
Provider order is copied from the problem; one provider's native traversal is
consumed before the next provider is opened. No move score, provider score, stage
sort or global transformation sort is evaluated. The new scheduling mode rejects
batch providers and arbitrary priority policies before execution. Conversely,
the native incremental provider rejects batch use. State/frontier priorities and
the existing admission, assumption, depth and proof checks still apply.

For example, select the built-in `ast_add_zero_right` rule, construct a prepared
engine with that one-rule inventory, wrap it in `NativeIncrementalMoveProvider`,
and pass it to a `MoveSearch.Problem` with the new scheduling enum and inventory
policy. The learning module supplies
`PrimitiveReplayMoveVerifier.nativePatterns(List<PatternRewriteRule>)` for an
explicit native inventory with unique rule IDs. It re-executes each claimed
primitive and independently checks exact polynomial identity. It does not infer
correctness from a construction flag.

## Versioned evidence and work

The new result carries `IncrementalMoveExecution`, including the complete
provider/rule definitions in order, cursor bounds, source states, checked/rejected
provider assumptions and each opened cursor's final snapshot. Unopened lanes
remain explicit with no cursor. Rule definitions retain typed pattern trees and
the recognition profile. Placeholder and literal names are escaped structured
values; display strings are not used as a structural identity.

| Contract | Revision |
| --- | --- |
| Native cursor work | `regelsuche.native-transformation-cursor-work/v1` |
| Native occurrence order | `regelsuche.native-occurrence-preorder-rule-inventory/v1` |
| Move-search work | `regelsuche.incremental-native-move-search-work/v1` |
| Provider/native order | `regelsuche.provider-inventory-native-occurrence-order/v1` |

Each named cursor event contributes one mechanical unit: open, parse, format,
canonical size, occurrence visit, rule match, target instantiation, source hash,
candidate filter, candidate emission and close. The matcher's reported branch
count and the replacement authority's copied ancestor count contribute their
actual counts. A changed instantiated subtree contributes one primitive rewrite,
including when growth or duplicate filtering later discards the candidate.
Unchanged instantiations retain mechanical work but add no primitive rewrite.

The existing `TransformationWorkMetrics` is only the transport: cursor mechanics
occupy its delegated mechanical field and rewrites occupy `candidateWork`.
Provider assumption checks/rejections and existing frontier/admission work are
added once. Prefix differences collect new work after every pull and close;
successful, unsuccessful and unconsumed work is retained. Primitive replay and
independent exact verification remain in `MoveSearch.Metrics.verificationWork`.
They do not enter the generation cursor's counters.

These are declared operation counts, not complete runtime profiling. Parsing,
formatting, canonicalization/size, hashing, template instantiation and snapshot
serialization are not charged by every internal node, byte, allocation or bit
operation. Match branches and copied ancestors are the explicitly counted inner
operations. Consequently totals from this new revision must not be compared as
if they used the historical STAGED/EAGER measurement scale. There is no walltime,
CPU or general speedup claim.

`LearnedSchedulingArtifacts.resultJson` includes the new evidence only for the
explicit new mode. Legacy payloads omit it entirely. Fixed historical native
EAGER/STAGED JSON controls prove the previous bytes remain unchanged; no old
policy, protocol, budget or fixture is migrated.

## Limits and termination

A pull produces at most one distinct transformation and checks its remaining
allowance before starting the next attempt. Initial setup and an individual
match/instantiation/candidate operation are atomic and may overrun the allowance;
their actual work is retained. There is no pause inside AC backtracking. Existing
recognition profiles may run atomically, and `matchDetailed` limits remain
explicit `MATCH_INCONCLUSIVE` attempts rather than ordinary negative matches.

Every visited attempt retains its occurrence path, rule index/ID, matcher branch
count, outcome and diagnostic. Invalid input and failed target instantiation are
retained incomplete outcomes. Unexpected exceptions still propagate after
search cleanup. Candidate caps, budget exhaustion, incomplete matching and early
close cannot establish a complete bounded relation. Reaching a candidate cap is
conservatively incomplete even when no further candidate might exist. Growth
filtering remains part of the explicitly retained prepared-engine bounds.

MoveSearch closes every opened native cursor on target, work/state limit, normal
termination and exceptions. Close is idempotent and never drains the remaining
stack. Its work is included before returning a result; an overrun caused by
cleanup prevents a budget-respecting success claim, while prior events and
verification evidence remain retained. A proof-rejected move likewise retains
its generation and verification work.

## Public component evidence and remaining scope

The native rule on `((a + 0) + (b + 0)) + 0` reaches its outer-rewrite target after
one emitted transformation, one rule attempt and one target instantiation. The
two inner rewrites are not executed by generation. Real primitive replay still
uses the existing batch verifier, and its additional work is separately retained.
The corresponding historical generation emits all three candidates. This is a
mechanical avoidance of two generation applications, not a runtime speedup.

Component tests also compare a full drain with the raw prepared-engine sequence,
including binary/function positions, multiple rules, duplicates and filters, and
compare a complete small bounded closure with the historical search relation.
Budget, setup, failed-match, INCONCLUSIVE, state-limit, close and forged-proof
controls exercise the same public path.

AC-internal suspension, arbitrary global priority order, plugin/learned/theory
providers, product defaults, large search studies and runtime qualification are
outside this slice. This work does not close the full #696 issue or claim that
the admitted-work diagnostics from #620 constitute full runtime profiling.
