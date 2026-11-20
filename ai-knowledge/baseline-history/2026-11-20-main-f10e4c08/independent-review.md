# Independent review of the accepted-predecessor baseline

Date: 2026-11-20

Reviewer: separate review agent `/root/ci_context_fix/baseline_review`

## Decision

Approved for promotion of **only** the complete retained
[`metrics-snapshot.json`](metrics-snapshot.json), SHA-256
`2417ce7b401bc5b41b006425ba1b415d77af59fdc3f8e6fb57f17833f7cfa982`.
It is the exact native snapshot of accepted main
`f10e4c0875e1e76288b2332191bba5b78b2f53dc`, tree
`e199300eb814dbe2e829ae0128f6746900d05f87`, measured using extractor
`b409bed957c31d63ce7b6ef37205890f0f0ebd9a`, tree
`0cb8e41b91c831705f9c86633580181372da6e14`.

At the initial review, the active baseline still matched the retained previous baseline,
SHA-256 `334b262e0b10f9c7ca5802af866d94114d94c960fa5a00744960f1e9df61554c`.
That review approved the subsequent exact copy. The retention follow-up below
confirms that the copy has since occurred; verification of the resulting
integration revision remains a separate gate.

## Independently checked evidence

- Read the baseline protocol and reviewed the proposed November 20 section in
  `docs/ai-knowledge.md`. Its claims are supported by the retained evidence;
  its statement that the active baseline is the new snapshot became effective
  with the approved copy subsequently confirmed below.
- Read live GitHub metadata for [main CI run 44712656489](https://github.com/carstenartur/Regelsuche/actions/runs/44712656489):
  attempt 1, `push` on `main`, the exact source commit and tree above, completed
  successfully. Compared every retained job and artifact metadata field in
  [`source-qualification.json`](source-qualification.json) against the live
  responses. All seven authorities, `Checkout-local ciCheck` and publication
  succeeded. The four optional JMH study jobs were skipped.
- Rehashed the supplied original `quality-acceptance-evidence` ZIP, artifact
  `14621363258`: 29,205,335 bytes, SHA-256
  `f46c71950dfd711affa26ec8bc6e92ffcb17e3735b1a9e501134489aef5b3416`, matching
  the live API digest. Verified lengths and SHA-256 hashes of all 290 indexed
  files. The archived index equals the decompressed bytes of
  [`original-ci-index.json.gz`](original-ci-index.json.gz) and binds collection
  to the exact clean accepted tree.
- Confirmed that original CI `check.json` and `complexity.json`, retained copies
  and present native output are byte-identical. Their SHA-256 hashes are,
  respectively, `ec4dc1ea3cc21093298bcb5839600e55666b28dd3e0f10525f8c2d1f9dd013aa`
  and `b04fb2633b3ac6a7564841c6c1e7642057ba99bdecbce0fa57d5bf91b121c95c`.
- At the initial review, verified all 13 retained-file hashes and lengths and all 39 raw-report member
  hashes and lengths recorded in [`provenance.json`](provenance.json). All 38
  extractor outputs match the present accepted worktree output; the additional
  hotspot report matches its native report path. Parsed all 12 retained JSON
  inputs with duplicate-field rejection; the CI index is now retained compressed.
- Independently checked accepted source and extractor commit/tree identities
  and clean Git status. Verified all 14 repository bindings and 12 extractor
  bindings in provenance. The 14 repository files are also byte-identical in
  the candidate checkout: selectors, claims, coverage policy, quality limits,
  hotspot exceptions, verification entrypoints and dependency pin are unchanged.
  The accepted commit is an ancestor of rejected main `c621a194`.
- Recounted the raw inventories: 1,924 classes, 1,416 tests, 572 documents,
  330 dependencies and 17 capabilities. The unchanged extractor weights
  `260`, `140`, `180`, `35` and `80` reproduce 814,350 context units. All 3,912
  class/test/document source paths are tracked, and their current bytes match
  their committed index blobs. No untracked source path contributes to these
  inventories.
- Compared every snapshot metric with the native complexity report and the
  unchanged `TrendAnalyzer.snapshot` field mapping. Rechecked the accepted
  trend's baseline/current values and deltas. The proposed snapshot is also
  byte-identical to the generated snapshot still in the accepted worktree.

## Gate result and review limits

The retained [`native-gate-receipt.json`](native-gate-receipt.json) and
losslessly compressed [`aiKnowledgeCheck.log.gz`](aiKnowledgeCheck.log.gz) record the complete root
`aiKnowledgeCheck`, exit 0, 14 actionable tasks executed, eight coverage
controls and 17 hotspot checks. This reviewer inspected that existing execution
evidence and did **not** rerun or personally observe its original execution.
The source/tool cleanliness before and after that execution is recorded in the
receipt; current cleanliness was independently rechecked during this review.

The accepted measurement passes against its original 802,120-unit baseline:
814,350 units, an increase of 12,230 within the unchanged 15,000 allowance.
Concept radius rises from 112 to 113 within the allowance of 3. Comparable
normalized context debt falls from 14.25 to 13.88; the allowance remains 10.
The retained native check and trend report pass with no violations. The hotspot
report passes all 17 changes with no violations or active exceptions.

The later rejected measurement remains separately retained at 818,250 units,
16,130 above the previous baseline and 3,900 above this accepted snapshot.
It supplies no value to the approved baseline. This approval qualifies only
the accepted predecessor as a comparison reference. It does not approve later
features, replace final-revision quality gates or CI, or establish a performance
claim. No blocking issue was found in this promotion's evidence or policy scope.

## Retention follow-up on 2026-11-20

Independently decompressed the two newly compressed evidence files and verified
their original lengths and SHA-256 hashes against the initially reviewed bytes:

| Retained file | Decompressed bytes | Decompressed SHA-256 |
| --- | ---: | --- |
| `aiKnowledgeCheck.log.gz` | 2,554 | `88639f6a4db008299e8eb517bb687d2f8968b72799c22cc35bce4bb9f5639966` |
| `original-ci-index.json.gz` | 517,914 | `2376b47e6cf492477cd80183a154ac493166da85e24534fa019f4edd04db3707` |

Compression preserves every original byte, including the native log's trailing
whitespace. The active baseline now has the approved snapshot SHA-256
`2417ce7b401bc5b41b006425ba1b415d77af59fdc3f8e6fb57f17833f7cfa982`.
This narrow follow-up changes no qualification or approval decision and does
not repeat or extend the original audit.
