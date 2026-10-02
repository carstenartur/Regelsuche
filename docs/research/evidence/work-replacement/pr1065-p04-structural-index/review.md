# PR #1065 independent review and disposition

Reviewed range: da1f33195339efe08380b47c36e83455c1e3815f..a68a35a99acf90cfda1d089368861e33a5ede0de.
Final corrected head: 596afa383d9605a3dbd656512ad8844c4c032e38.
Independent read-only reviewer: /root/p04_index_review (gpt-6-astra, high).
Initial verdict: Request changes; no Critical findings, one Important finding.

## Important: unbounded append-only scratch

The reviewer reproduced two valid-input JVM failures before terminal observation:

- 20,000 distinct NumberExpr nodes sharing one 65,537-bit rational: input 20,001 nodes / 19,731 characters / 40,006 references; at -Xmx96m, OOM after 191,964,008 store-work units and only the initial checkpoint.
- Equal DAGs with sharing arranged differently: input 14,344 nodes / one character / 40,988 references; at -Xmx64m, OOM allocating comparison pairs after 15,546,785 units and only the initial checkpoint. At 96 MiB, the terminal scanner could itself run out of memory.

Disposition: fixed with per-operation identity-keyed scalar encoding/hash reuse, local reference/byte guards at every scratch growth (also without an observer), periodic complete observations, and failure observation before release. New regressions for shared scalar amplification and differently shared DAG bounds failed before this correction and pass after it. No limit or assertion was weakened.

The original scalar probe succeeds at 96 MiB after the correction. A first bounded implementation with full observations every 256 growth events timed out at 180 seconds on the original pair probe; this failed attempt is retained. Batching full observations every 4,096 growth events, while preserving per-growth local guards, makes the original pair probe exit by retention-stop at 64 MiB. It reports 182 checkpoints and 5,142,840 store-work units. These are diagnostic probe values, not a learning or lifecycle-performance claim, nor a general fixed-heap guarantee.

## Minor: cancellation only covered acquisition

The original cancellation test aborted during retain() acquisition. Added coverage permits acquisition and cancels populated hash and comparison scratch at the terminal observation. It verifies observed scratch, paid traversal/cleanup, release, original exception preservation, unchanged ownership and subsequent session reuse. This test passed on the pre-fix code; it closes a coverage gap rather than demonstrating a separate production bug.

## Other review conclusions and boundaries

The reviewer found structural identity, scoped identifiers, ordered children, exact rationals, FIFO eviction and owned-root preservation sound. The review inspected successful module logs and the exact-head historical comparison, but did not rerun all suites. No second review was requested after the one required correction pass.

The reviewer declined to judge remaining frontier/replay equality, other unfinished P04 atomic regions, total native accounting or P05 economic/proof-reuse claims. Public coverage remains PARTIAL_ATOMIC_INVENTORY, outcome INCONCLUSIVE and completion false.

## Independent CI finding

Hosted Gradle on a68a35a rejected SearchExpressionStore.intern() as a new complexity hotspot (cognitive 39 / cyclomatic 27). Lookup, index capacity planning and index mutation were separated into helpers. The unchanged complexity gate passes locally with pinned extractor v0.1.10, commit b409bed957c31d63ce7b6ef37205890f0f0ebd9a. This environment lacks javac 17, so only that local dependency was compiled with javac 25 and its unchanged --release 17. Final hosted CI remains a separate integration requirement.

## Verification

- Full rebuild: 2,705 tests (core 1,058; search 589; learning 1,058), zero failures/errors/skips. Post-batching affected-suite rerun also succeeds.
- Final published head: the unchanged public P03 probe freshly compiled, run in two fresh JVMs; all 15 historical output files byte-identical.
- Historical corpus, sealed holdouts, quality gates, learned integration budgets and mathematical verifiers unchanged.

Initial failures, successful reruns, probe sources, source bindings and XML receipts are retained in the adjacent evidence archive. Runtime/heap values are diagnostic only; no full-roadmap or full-P04 completion is claimed.
