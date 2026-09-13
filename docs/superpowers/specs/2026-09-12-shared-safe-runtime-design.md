# Shared opt-in SAFE runtime boundary (#972)

The product adapter uses the occurrence-aware authority introduced by #971 and
corrected in #974. It does not reinterpret SAFE_PREPARATION_V2 evidence and does
not run a new matched-work FINAL TEST or change the default search policy.

## Product boundary

CLI `transform` and Workbench `/api/search` accept an explicit versioned runtime
request. A single application-layer runtime adapter owns the selected profile,
visible rule inventory, applicability coverage, initial assumptions, budgets,
analysis, work receipts and replay/export identity. Existing requests without the
opt-in runtime request preserve their current behavior.

The product controls select `DIRECT_V1` or the explicit
`SAFE_PREPARATION_V4` successor. `SAFE_PREPARATION_V3` remains accepted by the
request/replay API with its existing semantics and identities. DIRECT must
never execute native preparation or construct the unresolved-principal delegate.
Expose the existing V3 direct occurrence traversal and verification as separate
entry points; retain the behavior and work identity of ordinary `analyze` and
`verify` and every historical V2 entry point.

## Inventory and safety

Both profiles receive the identical visible rule objects and source identities.
SAFE principals come exclusively from `RewriteApplicabilitySchema.coverage`.
Rules without an admitted schema remain available through their existing direct
executor; they are never reconstructed as declarative rules. Plugins and learned
rules retain their original guarded/authorized executors. Imported JSON cannot
create rule objects or authorization receipts.

Configuration accepts only source-side information. No target, reference answer,
expected output or automatic profile selection is accepted. Initial assumptions
are normalized once and carried through every retained candidate and replay.
Guarded principals use the existing occurrence-local binding authority.

The separately authorized V4 successor adds preparation inside a concrete
nested source occurrence. It retains the unchanged V3 base evaluation, binds
guards to that occurrence, and independently replays primitive preparation and
principal steps while lifting them into the original source context. Its
successor and contextual work identities are separate from V2 and V3. Bounded
local batches share their traversal across unresolved principals; spent work
and inconclusive outcomes survive later failed or unsupported occurrences.

Every candidate retains `RecordedExecution` observations of the complete actual
transformation provenance, including primitive/theory lineage and work. Replay
obtains fresh trusted transformations and compares the entire artifact; hashes
alone never authorize execution.

## Typed representations

System/matrix requests are an explicit typed branch of the same product adapter.
They retain the full `MatrixPreparationJson` artifact, its
`RepresentationBridge.Relation`, exact formation, downstream solving and concrete
replay. They are not converted into scalar `Expr` equality. The outer runtime
identity binds the typed request, profile and work budget as well.

## Work and outcomes

The versioned product receipt keeps setup, analysis and verification work
separate. V3 occurrence work and delegated V2 work remain separately visible.
The contract describes logical work units rather than CPU timing. Budget
exhaustion, unsupported applicability and technical execution failures retain
different outcomes; spent work remains reported after failed attempts.

## Product characterization

Retain real CLI/HTTP cases for direct parity, a SAFE-only prepared candidate,
nested guarded positive and negative controls, typed matrix replay, explicit
technical/unsupported/budget outcomes and cross-surface export/import/replay.
Tampering with profile, inventories, assumptions, budgets, lineage or typed
relations must fail replay. The UI displays the chosen profile and retained
assumptions and provides the export/replay artifact.

Local focused Java 25 Maven tests and independent review support this change.
Complete current-head Gradle, Maven/Product/Docker, SymPy, JMH and checkout-owned
ciCheck remain integration qualification. SAFE remains opt-in pending a separate
fresh final product decision.
