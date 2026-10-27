# Typed computation tasks and replaceable mathematical backends

The word-relation proof and source-task increments are implemented. See the
[implementation status and qualification](../plans/2026-10-26-typed-cas-computation.md#current-status-2026-10-27)
for tested revisions and pending integration gates. The future-backend section
below defines the extension requirements; it does not claim configurable CAS
selection or automatic cross-store rewriting is available.

## Intent and ownership

Connect real Java carry computations to reusable mathematical reasoning without
moving algebra into Sandbox. The user authorized diagnosis and implementation,
with an independent architecture assessment and a future optional CAS such as SymPy.

Sandbox owns JDT binding resolution, Java promotions, source identity, fresh
array-read occurrences, local value versions, observable local commits, array
stores, exception order and source emission. Regelsuche owns typed computation
plans, algebraic candidates, mathematical facts and independent numeric proof.
Neither an expression returned by a CAS nor a provenance hash proves Java equivalence.

## Approaches and decision

1. Direct JDT-to-CAS strings would lose types, effects and proof obligations; reject.
2. Replacing computation plans with SolverIr would lose named outputs, word widths,
   shifts, casts, source traces and cost; reject. SolverIr is an obligation protocol.
3. Reuse JointComputationPlan/OptimizationRequest and retain a separate source
   schedule; extend the existing Regelsuche checker and generator. Selected as the
   implementation decision because it preserves the existing authority boundaries.

Regelsuche already has solver backends, portfolio capabilities and limited SymPy
adapters. Future providers translate only supported mathematical islands, through
a bounded typed mapping, and return proposals to the existing search/proof path.
Unsupported theories, assumptions, integer division semantics or effects must be
reported rather than silently approximated. There is no second CAS framework in
Sandbox and no new Python process or workflow in this change.

## Executable increments

* Compare a real pinned BC Nat256 carry block with a hand-assembled typed plan.
  Keep operation occurrences distinct from expression sharing; expose low words,
  continuing carries and every handler-visible local commit.
* Regelsuche: support constant shifts and primitive integral casts in the existing
  universal bitvector checker. A generic acceptance identity is reconstruction
  from high and low word projections. The generator proposes the simplification;
  an independent per-bit checker proves it for all inputs, not sampled inputs.
* Sandbox: export a complete bounded straight-line value task using its existing
  Java decoder, with fresh reads and an ordered source schedule. Such a task is
  not an automatically applicable edit. Existing cleanup admission stays in force
  until a candidate's schedule-preserving source realization is verified.
* Qualify the Regelsuche artifact using the existing SDK packaging/staging path;
  integrate the exact staged bytes and receipts in Sandbox, with real source tests.

## Contracts and limits

Java 25, Maven/JUnit authority, existing numeric and source proof gates remain.
No AST package restructuring, parallel mathematical engine, disabled tests or
new Python/workflow automation. Ordinary PRs target main; prerequisites must be
integrated first. Keep review slices near or below 1,500 changed text lines.

The bit proof indexes variables by (typed word atom, bit position), respects
sign extension, narrowing and Java's masked shift distance, and charges bounded
preparation/evaluation work. More than eight relevant independent bits for a
single output bit is outside this bounded proof. Cancellation remains effective.

A source task records reads, stores, local commits and returns in original
order. Stores can throw: a later carry assignment must not become visible to a
handler before the original store. Different array names do not establish
non-aliasing. Reads after writes remain fresh symbolic inputs. The numeric plan
alone cannot authorize hoisting or Java edits.

## Future backend contract

Backend selection belongs to Regelsuche. Bind semantics/profile, typed plan,
original operation trace, proven assumptions, backend/configuration revision and
goal to every result/cache entry. Distinguish unsupported, unavailable, unknown,
timeout, cancellation, refutation and verified improvement. Translation, search,
decoding, verification and presentation share the caller's work/time limits.
Existing SolverIr obligations can support lossless projected mathematical
subgoals; external certificates never bypass the independent Java checker.

Final value is judged after Sandbox adds captures, temporaries, guards and
fallback cost, with complete-call benchmarks. Nat256.add may already be efficient;
recognizing its relationships is not a claim of measured runtime improvement.

## Architecture review

An independent read-only review confirmed this ownership split, identified the
existing SolverIr/SymPy facilities, and rejected merely widening extraction
boundaries. Its critical case is handler-visible local state across a throwing
array store. It recommended typed bit projection as a reusable first mathematical
extension, separate from the exact one-input affine-domain API.

The complete-block experiment additionally exposed a transport bottleneck:
the cold codec reconstructs trees, so an unchanged 87-node carry DAG arrived as
1,753 distinct expression objects and exceeded the subsequent preparation cap.
The shared plan resolver now restores structural sharing with shallow exact
keys and child IDs. Every decoded visit and key operation remains charged;
no structural/depth limit is raised. A metered resolver callback replaces the
affine API's former fixed precharge, and source-adapter failure reservations must
include the resolver's documented work bound as well as preparation work.
The schedule builder consumes those canonical identities rather than repeating
structural interning. Failed late trace preparation may follow earlier request
work: known initial-validation failures report earlier charged work plus the
failed preparation's conservative bound. Consumers debit this reported upper
bound, even above the allocation, and can reuse only the actual remainder.
Unknown/legacy failures still reserve the allowance and unreported bound.
Completed bounded searches expose a complete-work accessor, allowing their actual
reported counter to replace the allocation. Partial checker stops remain
conservative; the source adapter does not parse stage strings for this decision.
Only actually constructed candidates reserve the trusted constructor's final
preparation; non-improvement outcomes do not perform that work.
Initial search-envelope and schedule construction share their local resolution.
Derivation capture retains the original source envelope and internally verified frontier states; source
binding, continuity and endpoint checks remain, and external replay still
rebuilds bounds, regenerates proposals and independently verifies each edge.
For Boolean roots, the existing opaque-word proof runs before expensive context
normalization. The bit-projection fallback remains available; changing proof
order avoids spending the budget on unnecessary subterm attempts.

The architecture follow-up also identified an existing separate accounting
limit: the explanation API reports presentation work after its fresh verification,
not that call's full consumed work. A later API/consumer change should report and
debit complete explanation-call work on success and failure. The current file
ledger must not be described as a bound on every actually executed SDK operation.
