# Native Expr qualification boundary

Native execution is additive and experimental. The historical `MoveSearch` and
`TypedMoveSearch` interfaces retain their existing contracts. Native `Problem`
continues to reject `PRODUCTION` contexts. Representation registration and an
accepted-looking receipt never establish mathematical authority: default native
verification uses supported registered verifiers and fresh checking; an explicitly
supplied trusted verifier has the same responsibility as the old verifier hook.
Exact schema capabilities remain private and bind the checked model/application.

The current search revision is
`regelsuche.native-expr-move-search/v4-partial-atomic-inventory`; output uses
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
