# PR #1076 — bounded P04 validation ownership evidence

This packet binds the validation-retention continuation to published source
`151826e648da62784574573b0980da42d3b68beb`, tree
`eb052f81047973154d99105273de5851b0c9b9a3`, against main
`8a98a14e6713225801832918befd7c12d5cb74ba`.
Local implementation commit `b0883b6d3c7d968ef292c927ed5a6fdc7eb1bb53`
has exactly the same tree. It is not a different implementation.

PR: https://github.com/carstenartur/Regelsuche/pull/1076
Exact-head CI: https://github.com/carstenartur/Regelsuche/actions/runs/37938454239

## Boundary

The iterative validator exposes its actual bounded visit arena and completed
numeric/scoped-symbol buffers to the existing paid observer. Queued plus visited
occurrences are bounded per state. Acquisition, attempted work and cleanup remain
paid; primary failures survive repeated cleanup failures. Early input exhaustion
enters the existing kernel's receipt path. No second search, new mathematical
authority or AST-JSON transport is introduced.

Native measurement revision is `regelsuche.native-expr-move-search/v6-partial-validation-retention`.
`PARTIAL_ATOMIC_INVENTORY`, public `INCONCLUSIVE`, false `withinBudget` and false
completion remain in force. This does not finish P04 or establish a learning,
runtime, physical-heap or economic advantage. JDK formatting internals and other
documented atomic regions remain unqualified. Sealed holdouts were not accessed.

## Evidence inventory

- `source-binding.json`: exact commits/tree, runtime, counts and public-probe hashes.
- `source.patch`: full eight-file source/documentation change against the base.
- `logs/`: every retained implementation attempt, including failed attempts.
- `attempts/`: retained intermediate RED/GREEN XML; explicitly not the final suite.
- `final-xml/`: core 1,058, search 604, learning 1,058 tests: 2,720 total,
  zero failures, errors or skips; includes twelve new validator regressions.
- `complexity/`: unchanged v0.1.10 authority, PASS with 17 hotspots; no ratchet
  or threshold changed. Extractor commit is `b409bed957c31d63ce7b6ef37205890f0f0ebd9a`.
- `review/`: independent whole-branch review contract, result and probe source.
  No Critical, Important or Minor findings. Independent fault injection passed
  1,366 callback sites x two failure modes, plus 2,000 expression/history codec
  differentials. The reviewer did not execute the public P03 or remote CI checks.
- `public-p03/`: unchanged historical generator/baseline, exact-published-head
  fresh compilation, two fresh JVMs, all 15 output files byte-identical, plus the
  prior same-tree local-commit run retained separately.
- `execution-ledger.md`: decisions, limitations and negative-result chronology.
- `sha256.json`: SHA-256 of every payload file except this manifest itself.

## Negative results are retained

Initial regressions exposed missing scalar/scratch ownership and work, raw input
resource failure before the kernel, oversized queued traversal, unpaid acquisition
cleanup and excessive scalar lifetime. Tests were observed failing before fixes.
The unchanged learned-inventory test initially exhausted its 10,000,000-unit
budget at 10,001,396 units. Removing only a duplicate ownership scan restored
TARGET_REACHED at 9,977,168 units; its budget and assertions were not relaxed.

`p04-validation-scalar-work-red.log` is a Gradle journal-lock infrastructure failure,
not behavioral RED. Later `scalar-red2` contains the actual two failing regressions.
Concurrent Gradle processes shared a namespace PID; subsequent runs were sequential.
The independent probe's initial ordinal-zero generator failure is disclosed in
`review/probes/result.txt`; it required no production change.

## Reproduction and provenance

Use the exact source tree above, JDK 25.0.2+10, Gradle 9.7.1 and the pinned
AI Knowledge Extractor v0.1.10 checkout. The retained Gradle runner derives proxy
settings without storing credentials; its optional init script only selects the
available JDK for the local extractor compiler and preserves upstream release 17.
The final local command runs core/search/learning tests and the unchanged
`verifyComplexityHotspots` authority. Hosted CI remains the complete product gate.

Commands and scripts retain the original absolute workspace/runtime paths for
auditability. To reproduce elsewhere, adjust only those paths and use a fresh
output directory; do not change probe source, pinned dependency hashes, baseline,
budgets or assertions. Generated class files, Gradle binary reports, downloaded
dependencies and transient runtime directories are omitted as reproducible data.

The historical public baseline was recovered from PR #1065's earlier evidence
archive, SHA-256 `cea7eb10b8d7181f42a9dfff1764899ebe24ac268ccd7898b1cf1089b817f0dc`.
Its old source binding is kept unchanged, not relabeled as #1065's later final
head or as this PR. The public probe SHA-256 is
`6b291db8cca63f4a79c8a766a0aec2af51249081797d58991b5d0fe8246ef377`.

At packet creation, hosted CI is pending and no merge qualification is asserted.
Later integration status must be bound separately to the exact head and checks.

## Retained archive

Archive: [pr1076-p04-validation.tar.gz](pr1076-p04-validation.tar.gz) (797,173 bytes).
SHA-256: `d9a898fac77030aca0e200f7494d3711cfcd3517fd5a92e9b48ed61e535f6fd2`.
Git blob: `4c6933115787af2e31dd99a0636fb9c5872cc9fa`.
