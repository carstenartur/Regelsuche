# Independent review of the accepted-predecessor baseline proposal

**Decision: APPROVE the exact proposed byte-for-byte copy only.** No material finding blocks promoting the retained accepted-P03 `metrics-snapshot.json` to `ai-knowledge/complexity-baseline.json`, using the accepted-predecessor procedure in `docs/ai-knowledge.md`. This decision does not approve P04 implementation, its failing draft, performance claims, or a merge. The earlier P04 code review is unchanged.

The approved input is the snapshot in this proposal directory, SHA-256 `2df07604122be739198f4258797379097df6df27e993d706daebd39707ae61c0`. It is byte-identical to `/workspace/scratch/7ff736a22947/p04-p03-predecessor-ai-evidence-source/build/ai-knowledge/metrics-snapshot.json`. Approval does not cover edits to its numbers, a snapshot from another run/source, or changes to thresholds, selectors, hotspot exceptions, tests or verification tasks. Retain the preceding baseline and rejected P04 evidence with their original identities.

## Independently verified identity and qualification

- Accepted source HEAD: `ee6dd6892d899662c857c1f2bc2861e233e68563`; tree: `e1bee2a7712ff95f14b2c3513f565ff6c26bde8b`. The source checkout is detached and currently clean, with only expected ignored `.gradle/` and `build/` output directories. The receipt records clean source state before and after the measurement.
- Extractor HEAD and exact `v0.1.10` tag: `b409bed957c31d63ce7b6ef37205890f0f0ebd9a`; tree: `0cb8e41b91c831705f9c86633580181372da6e14`. Its current status is clean; ignored paths are Gradle/build output. Its receipt likewise records clean before/after state.
- Fresh read-only GitHub API calls on 2026-09-28 confirm PR [1051](https://github.com/carstenartur/Regelsuche/pull/1051) merged into this accepted commit. PR head `be423b3f99c5f1e3bb4ea159e2610ecc5e63596a` is a merge parent and has the same tree. A fresh `main` read still points at that commit/tree.
- Main [CI run 36360112301](https://github.com/carstenartur/Regelsuche/actions/runs/36360112301), attempt 2, is completed/successful on the exact accepted SHA. The attempt-specific API returned all 12 jobs; each of the following required authorities and aggregate passed on that SHA and attempt:

| Job | Job ID | Conclusion |
| --- | ---: | --- |
| Checkout-local Gradle authority | 108741202446 | success |
| Complete Maven product and Docker authority | 108741204711 | success |
| Isolated JMH authority | 108741180202 | success |
| External polynomial comparison | 108741208195 | success |
| Isolated SymPy runtime authority | 108741179466 | success |
| Typed source-only learning comparison | 108741180481 | success |
| Checkout-local ciCheck | 108741752739 | success |

The four optional/manual JMH study jobs were skipped; report publication succeeded. Those skips are not represented as executed qualification. [CodeQL run 36360112513](https://github.com/carstenartur/Regelsuche/actions/runs/36360112513), attempt 1, is also completed/successful on the accepted SHA/tree. These fresh observations agree with the retained `live-source-qualification.json`.

## Measurement and policy checks

The retained runner creates a new detached checkout at the supplied exact SHA, requires the pinned clean extractor, runs the complete `aiKnowledgeCheck`, records before/after identities and hashes, and copies generated reports without editing them. Its command has no task-exclusion or policy-relaxation argument. The retained log ends `BUILD SUCCESSFUL in 1m 26s` and `12 actionable tasks: 12 executed`; it also shows all eight coverage controls passing and all 17 hotspot checks passing. The process receipt records exit 0 and 86.92662317799841 seconds.

The reports agree with one another and the old committed baseline:

| Quantity | Old baseline | Accepted predecessor | Result |
| --- | ---: | ---: | --- |
| Estimated context tokens | 648025 | 655685 | +7660, below unchanged 15000 allowance |
| Concept radius | 94 | 95 | +1, below unchanged 3 allowance |
| Normalized context debt | 16.09 | 15.95 | Same `context-footprint-v3` model; below unchanged 21.7 absolute limit |

`check.json` and `trend.json` both report success and zero violations. The six existing nonfatal warnings remain disclosed. All four retained generated reports are byte-identical to the corresponding files in the clean predecessor's `build/ai-knowledge/` directory. `trend.current` equals the full proposed snapshot, and its baseline values equal the retained preceding snapshot.

The source's committed baseline, its live baseline file, the proposal's `previous-baseline.json`, and the current P04 working checkout's baseline all still hash to `b2d65ffe25f98ebbac0ca4e493d1b056d8e521cecb034d6b320277eb4e6d584b`. No promotion has been applied by this review. The clean source tree contains no P04 native implementation, and the approved generated values are those of accepted P03, not the rejected `b9fa5833a4eb7a78243ec862d997d917447caadf` feature.

I checked the unchanged consumer gate configuration, lifecycle wiring, extractor version pin and existing initializer. The initializer only selects JDK 25 for the extractor; the pinned extractor still specifies `options.release = 17`. The runtime adapter does not replace tasks or alter policy. Its Gradle 9.7.1 selection matches the source wrapper pin. The gate file, initializer, adapter, runner, JDK release file and Java executable hashes all match the raw receipt.

## Verified SHA-256 hashes

| File | SHA-256 |
| --- | --- |
| `metrics-snapshot.json` | `2df07604122be739198f4258797379097df6df27e993d706daebd39707ae61c0` |
| `previous-baseline.json` | `b2d65ffe25f98ebbac0ca4e493d1b056d8e521cecb034d6b320277eb4e6d584b` |
| `receipt.json` | `4d6b766330b675fe3bf9adbb1eed9566d7235296ac4e51d1683a2e9abbc2ce68` |
| `aiKnowledgeCheck.log` | `6faac42fa069060be429f29623e828d1ccaef3c1d3f1850d20feadcbb4b1d096` |
| `check.json` | `5c9f537acd43dda90b49298e21ed8a9f48bb4765cd7c7d6e69dadb93a15d15a3` |
| `trend.json` | `48e8dd88dfa805c268b5f7fb87a17c54bd39c31bb80e73a07d680c2b1918bcb7` |
| `complexity.json` | `153e6b9af98d1644e0b618b94c2bd3a2827f2800231203f48e0f74eecbdb72aa` |
| `run-clean-ai-gate.py` | `3800f352e47fef9e1b59bc3bbecf75d908d62a7579d98f4eb075c8af160a9d1f` |
| `live-source-qualification.json` | `1ed40d9a44402522d208156c2d692ee99bdfa0faa0e26e035760a629150d6512` |
| `proposal.json` | `5212b6623017a71760c989df2b55acf1a1a083047c4b34e9429e4a42d47c646e` |
| Accepted source `docs/ai-knowledge.md` | `3472b55ac74f2bbf241119b21eb9465eade430d5356302999e1f5c5e36120923` |

## Limitations and acceptance boundary

This was an independent read-only evidence review, not another gate execution. I independently checked current cleanliness and raw file hashes; the process's historical before/after state and elapsed time are recorded evidence, not events I personally observed. I did not download a hosted accepted-source artifact or independently redownload the rejected P04 artifact. The proposed baseline derives from the retained local predecessor measurement; hosted API status qualifies its source. The rejected P04 artifact and its root-verified ZIP hash are diagnostic context only and did not supply any approved value.

No source, baseline, earlier review, external PR or task state was changed, and no build/test was run. The eventual baseline copy must be verified to retain the exact approved hash. P04 remains unqualified and still requires its own complete unchanged current-head CI/gates and final independent code review after all implementation fixes and integration are complete.
